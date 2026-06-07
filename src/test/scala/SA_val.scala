package Pipe

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
class SimpleTest extends AnyFlatSpec with ChiselScalatestTester {

  def toFP16(f: Float): Long = {
  // use Java's built-in float16 conversion via short bits
  val bits = java.lang.Float.floatToRawIntBits(f)
  val sign = (bits >>> 31) & 0x1
  val exp  = ((bits >>> 23) & 0xFF) - 127 + 15
  val man  = (bits >>> 13) & 0x3FF

  // handle underflow/overflow
  val clampedExp = if (exp <= 0) 0 else if (exp >= 31) 31 else exp
  ((sign << 15) | (clampedExp << 10) | man).toLong & 0xFFFFL
}

    def fp16BitsToFloat(fp16: Long): Float = {
  val sign = (fp16 >> 15) & 0x1
  val exp  = ((fp16 >> 10) & 0x1F) - 15 + 127   // unbias FP16, rebias FP32
  val man  = (fp16 & 0x3FF) << 13                // 10-bit mantissa → 23-bit
  val fp32 = (sign << 31) | (exp << 23) | man
  java.lang.Float.intBitsToFloat(fp32.toInt)
}
  def toFloat(bits: Long): Float = {
  val signed = if (bits > 0x7FFFFFFFL) bits - 0x100000000L else bits
  java.lang.Float.intBitsToFloat(signed.toInt)
}

    def toFP32Bits(f: Float): Long =
  java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  
"TOP tiled 200x100x50" should "match PyTorch output" in {
  test(new Top(4, 4)).withAnnotations(Seq(VerilatorBackendAnnotation))({ dut =>

    def loadMatrix(path: String): Array[Array[Float]] =
      scala.io.Source.fromFile(path).getLines()
        .filterNot(_.trim.isEmpty)
        .map(_.trim.split("\\s+").map(_.toFloat))
        .toArray

    val A       = loadMatrix("src/test/scala/test_files/A.txt")
    val B       = loadMatrix("src/test/scala/test_files/B.txt")
    val X       = loadMatrix("src/test/scala/test_files/X.txt")
    val biasMatrix = loadMatrix("src/test/scala/test_files/bias.txt") 

    val M    = A.length
    val K    = A(0).length
    val N    = B(0).length
    val rows = 4
    val cols = 4

    val PIPE_DEPTH  = 3
    val MAX_SKEW    = (rows - 1) * PIPE_DEPTH
    val GAP         = PIPE_DEPTH + MAX_SKEW
    val totalCycles = K * GAP + MAX_SKEW

    val C_out = Array.ofDim[Float](M, N)

    for (tileRow <- 0 until math.ceil(M.toDouble / rows).toInt) {
      for (tileCol <- 0 until math.ceil(N.toDouble / cols).toInt) {

        val rBase    = tileRow * rows
        val cBase    = tileCol * cols
        val tileRows = math.min(rows, M - rBase)
        val tileCols = math.min(cols, N - cBase)

        // ── Reset ──────────────────────────────────────────
        dut.io.res.poke(true.B)
        dut.io.en.poke(false.B)
        dut.io.mode.poke(true.B)
        dut.io.load_bias.poke(false.B)
        for (i <- 0 until rows) dut.io.row(i).poke(0.U)
        for (j <- 0 until cols) { dut.io.col(j).poke(0.U); dut.io.bias(j).poke(0.U) }
        dut.clock.step(1)
        dut.io.res.poke(false.B)

        // ── Stream ─────────────────────────────────────────
        for (c <- 0 until totalCycles) {
          val k       = c / GAP
          val offset  = c % GAP
          val driving = offset < PIPE_DEPTH && k < K

          dut.io.en.poke(true.B)
          dut.io.mode.poke(true.B)
          dut.io.load_bias.poke(false.B)
          for (i <- 0 until rows)
            dut.io.row(i).poke(
              (if (driving && i < tileRows) toFP16(A(rBase + i)(k)) else 0L).U)
          for (j <- 0 until cols)
            dut.io.col(j).poke(
              (if (driving && j < tileCols) toFP16(B(k)(cBase + j)) else 0L).U)
          dut.clock.step(1)
        }

        // ── Drain & capture ────────────────────────────────
        dut.io.en.poke(false.B)
        dut.io.load_bias.poke(false.B)
        for (i <- 0 until rows) dut.io.row(i).poke(0.U)
        for (j <- 0 until cols) dut.io.col(j).poke(0.U)

        val results = Array.ofDim[Float](rows, cols)

        // (i+j) even → valid at drain d=0
        dut.clock.step(1)
        for (i <- 0 until tileRows; j <- 0 until tileCols)
          if ((i + j) % 2 == 0)
            results(i)(j) = toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)

        // (i+j) odd → valid at drain d=2
        dut.clock.step(2)
        for (i <- 0 until tileRows; j <- 0 until tileCols)
          if ((i + j) % 2 == 1)
            results(i)(j) = toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)

        // ── Write to output + add bias ──────────────────────
        for (i <- 0 until tileRows; j <- 0 until tileCols)
  C_out(rBase + i)(cBase + j) = results(i)(j) + biasMatrix(rBase + i)(cBase + j)
      }
    }

    // ── Compare ────────────────────────────────────────────
    var maxAbsErr   = 0f
    var maxRelErr   = 0f
    var nearZeroBad = 0
    var trulyWrong  = 0

    for (i <- 0 until M; j <- 0 until N) {
      val abs = math.abs(C_out(i)(j) - X(i)(j))
      val rel = if (math.abs(X(i)(j)) > 1e-3f) abs / math.abs(X(i)(j)) else 0f
      if (abs > maxAbsErr) maxAbsErr = abs
      if (rel > maxRelErr) maxRelErr = rel
      if (rel > 0.01f) {
        if (math.abs(X(i)(j)) < 0.5f) nearZeroBad += 1
        else                           trulyWrong  += 1
        if (trulyWrong <= 5)
          println(f"  BAD ($i,$j): hw=${C_out(i)(j)}%.4f  ref=${X(i)(j)}%.4f  abs=$abs%.4f")
      }
    }

    println(f"\nMax absolute error : $maxAbsErr%.4f")
    println(f"Max relative error : ${maxRelErr*100}%.3f%%")
    println(f"Near-zero ref (FP16 noise): $nearZeroBad")
    println(f"Truly wrong (large ref):    $trulyWrong")
    println(f"Total Cycle Count: $totalCycles")
    println("\nHW output (top-left 4x4):")
    for (i <- 0 until 4)
      println((0 until 4).map(j => f"${C_out(i)(j)}%8.3f").mkString(" "))
    println("\nRef (top-left 4x4):")
    for (i <- 0 until 4)
      println((0 until 4).map(j => f"${X(i)(j)}%8.3f").mkString(" "))
  })
}
}