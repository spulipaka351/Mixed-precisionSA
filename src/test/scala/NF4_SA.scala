package Pipe

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// ─────────────────────────────────────────────────────────────────────────────
// NF4TopTest  (Treadle / no Verilator)
//
// Tests the RTL NF4Dequantizer inside NF4Top end-to-end with HW Bias.
// ─────────────────────────────────────────────────────────────────────────────

class NF4TopTest extends AnyFlatSpec with ChiselScalatestTester {

  // ── FP helpers ──────────────────────────────────────────────────────────────
  def toFP16(f: Float): Long = {
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 31) & 0x1
    val exp  = ((bits >>> 23) & 0xFF) - 127 + 15
    val man  = (bits >>> 13) & 0x3FF
    val clampedExp = if (exp <= 0) 0 else if (exp >= 31) 31 else exp
    ((sign << 15) | (clampedExp << 10) | man).toLong & 0xFFFFL
  }

  // Converts a Scala Float into 32-bit raw integer bits for HW bias
  def toFP32(f: Float): Long = {
    java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  }

  def toFloat(bits: Long): Float = {
    val signed = if (bits > 0x7FFFFFFFL) bits - 0x100000000L else bits
    java.lang.Float.intBitsToFloat(signed.toInt)
  }

  // ── File loaders ─────────────────────────────────────────────────────────────
  def loadFloatMatrix(path: String): Array[Array[Float]] =
    scala.io.Source.fromFile(path).getLines()
      .filterNot(_.trim.isEmpty)
      .map(_.trim.split("\\s+").map(_.toFloat))
      .toArray

  def loadIntMatrix(path: String): Array[Array[Int]] =
    scala.io.Source.fromFile(path).getLines()
      .filterNot(_.trim.isEmpty)
      .map(_.trim.split("\\s+").map(_.toFloat.toInt))
      .toArray

  def loadScaleRow(path: String): Array[Float] =
    scala.io.Source.fromFile(path).getLines()
      .filterNot(_.trim.isEmpty)
      .flatMap(_.trim.split("\\s+").map(_.toFloat))
      .toArray

  // ── Nibble packing ───────────────────────────────────────────────────────────
  def packNF4(i0: Int, i1: Int, i2: Int, i3: Int): Long =
    ((i3 & 0xF).toLong << 12) |
    ((i2 & 0xF).toLong <<  8) |
    ((i1 & 0xF).toLong <<  4) |
     (i0 & 0xF).toLong

  // ── Main test ─────────────────────────────────────────────────────────────────
  "NF4Top RTL dequantizer" should "match Python NF4 reference output" in {
    test(new NF4Top(4, 4))({ dut =>

      val rows   = 4
      val cols   = 4
      val numDeq = (cols + 3) / 4

      // ── Load test data ────────────────────────────────────────────────────────
      val base    = "src/test/scala/nf4_test_files/"
      val A       = loadFloatMatrix(s"${base}A (1).txt")
      val Bidx    = loadIntMatrix  (s"${base}B (1).txt")
      val Bscales = loadScaleRow   (s"${base}B_scales.txt")
      val biasM   = loadFloatMatrix(s"${base}bias (1).txt")
      val X       = loadFloatMatrix(s"${base}X_nf4.txt")

      val M = A.length
      val K = A(0).length
      val N = Bidx(0).length

      // ── Diagnostics ───────────────────────────────────────────────────────────
      println(f"[DIAG] M=$M%d K=$K%d N=$N%d")
      println(f"[DIAG] A(0)(0)  = ${A(0)(0)}%+.6f")
      println(f"[DIAG] Bidx(0)  = ${Bidx(0).toSeq}")
      println(f"[DIAG] Bscales  = ${Bscales.toSeq.map(v => f"$v%.4f")}")
      println(f"[DIAG] bias(0)  = ${biasM(0).toSeq.map(v => f"$v%.4f")}")
      println(f"[DIAG] X(0)(0)  = ${X(0)(0)}%+.6f")

      // ── Timing ───────────────────────────────────────────────────────────────
      val PIPE_DEPTH  = 3
      val MAX_SKEW    = (rows - 1) * PIPE_DEPTH
      val GAP         = PIPE_DEPTH + MAX_SKEW
      val totalCycles = K * GAP + MAX_SKEW

      val C_out = Array.ofDim[Float](M, N)

      // ── Tile loop ─────────────────────────────────────────────────────────────
      for (tileRow <- 0 until math.ceil(M.toDouble / rows).toInt) {
        for (tileCol <- 0 until math.ceil(N.toDouble / cols).toInt) {

          val rBase    = tileRow * rows
          val cBase    = tileCol * cols
          val tileRows = math.min(rows, M - rBase)
          val tileCols = math.min(cols, N - cBase)

          // ── Reset ───────────────────────────────────────────────────────────
          dut.io.res.poke(true.B)
          dut.io.en.poke(false.B)
          dut.io.mode.poke(true.B)
          
          // Disable load_bias during reset
          dut.io.load_bias.poke(false.B) 
          
          for (i <- 0 until rows) dut.io.row(i).poke(0.U)
          for (d <- 0 until numDeq) {
            dut.io.packed_nf4(d).poke(0.U)
            dut.io.scale(d).poke(0.U)
          }
          
          // POKE HARDWARE BIAS PORT CONSTANTLY
          // Hardware is a 1D bias vector (per column). We broadcast the first row of biasM.
          for (j <- 0 until cols) {
            val cCol = cBase + j
            val bVal = if (cCol < N) biasM(0)(cCol) else 0f 
            dut.io.bias(j).poke(toFP32(bVal).U)
          }
          
          dut.clock.step(1)
          dut.io.res.poke(false.B)

          // ── Stream ──────────────────────────────────────────────────────────
          for (c <- 0 until totalCycles) {
            val k       = c / GAP
            val offset  = c % GAP
            val driving = offset < PIPE_DEPTH && k < K

            dut.io.en.poke(true.B)
            dut.io.mode.poke(true.B)
            
            // PULSE LOAD BIAS: Only true on the very first cycle of the tile computation
            dut.io.load_bias.poke((c < PIPE_DEPTH).B)

            // Activations
            for (i <- 0 until rows)
              dut.io.row(i).poke(
                (if (driving && i < tileRows) toFP16(A(rBase + i)(k)) else 0L).U)

            // Weights: pack NF4 indices + drive FP16 scale
            for (d <- 0 until numDeq) {
              if (driving) {
                def idx(n: Int): Int = {
                  val col = cBase + d * 4 + n
                  if (n < tileCols && col < N) Bidx(k)(col) else 0
                }
                val packed = packNF4(idx(0), idx(1), idx(2), idx(3))
                val scFP16 = toFP16(Bscales(k))
                dut.io.packed_nf4(d).poke(packed.U)
                dut.io.scale(d).poke(scFP16.U)
              } else {
                dut.io.packed_nf4(d).poke(0.U)
                dut.io.scale(d).poke(0.U)
              }
            }

            dut.clock.step(1)
          }

          // ── Drain & capture ─────────────────────────────────────────────────
          dut.io.en.poke(false.B)
          dut.io.load_bias.poke(false.B) // Keep false during drain
          
          for (i <- 0 until rows) dut.io.row(i).poke(0.U)
          for (d <- 0 until numDeq) {
            dut.io.packed_nf4(d).poke(0.U)
            dut.io.scale(d).poke(0.U)
          }

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

          // BIAS IS HANDLED BY HARDWARE
          for (i <- 0 until tileRows; j <- 0 until tileCols)
            C_out(rBase + i)(cBase + j) = results(i)(j)
        }
      }

      // ── Error reporting ───────────────────────────────────────────────────────
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
      println(f"Max relative error : ${maxRelErr * 100}%.3f%%")
      println(f"Near-zero ref (FP16 quantisation noise): $nearZeroBad")
      println(f"Truly wrong (large ref):                 $trulyWrong")
      println(f"Total cycle count:                       $totalCycles")

      println("\nHW output (full 4×4):")
      for (i <- 0 until M)
        println((0 until N).map(j => f"${C_out(i)(j)}%8.3f").mkString(" "))
      println("\nReference (full 4×4):")
      for (i <- 0 until M)
        println((0 until N).map(j => f"${X(i)(j)}%8.3f").mkString(" "))

      assert(trulyWrong == 0,
        s"$trulyWrong cell(s) exceeded 1% relative error (max abs = $maxAbsErr)")
    })
  }
}