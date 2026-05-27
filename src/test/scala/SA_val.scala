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



//   "TOP timing" should "trace per-cycle inputs" in {
//   test(new Top(2,2))({ dut =>

//     val A = loadMatrices("/home/maruthi/dev/ChiselVectorPE/src/test/scala/test_files/A.txt")
//     val B = loadMatrices("/home/maruthi/dev/ChiselVectorPE/src/test/scala/test_files/B.txt")
    
//     val rows = 2

// val PIPE_DEPTH = 3
// val MAX_SKEW   = (rows - 1) * PIPE_DEPTH  // = 3 for 2x2
// val GAP        = PIPE_DEPTH + MAX_SKEW    // = 6, feedback round-trip for slowest PE
// val K          = 16

// // total cycles = K*GAP + MAX_SKEW = 2*6 + 3 = 15
// println("sanity check")
// val totalCycles = K * GAP + MAX_SKEW
// Seq(0.0154f, -0.5395f, -1.4374f, -0.7193f, 1.7864f).foreach { v =>
//   val back = fp16BitsToFloat(toFP16(v))
//   println(f"$v%.4f → $back%.4f")
// }
// for (c <- 0 until totalCycles) {
//   val k       = c / GAP          // which k-step this cycle belongs to
//   val offset  = c % GAP          // position within this k-step's window
//   val driving = offset < PIPE_DEPTH && k < K  // real data or zero

//   dut.io.res.poke(false.B)
//   dut.io.en.poke(true.B)
//   dut.io.mode.poke(true.B)
//   if (driving) {
//     dut.io.row(0).poke(toFP16(A(0)(k)).U)
//     dut.io.row(1).poke(toFP16(A(1)(k)).U)
//     dut.io.col(0).poke(toFP16(B(k)(0)).U)
//     dut.io.col(1).poke(toFP16(B(k)(1)).U)
//   } else {
//     dut.io.row(0).poke(0.U); dut.io.row(1).poke(0.U)
//     dut.io.col(0).poke(0.U); dut.io.col(1).poke(0.U)
//   }
//   dut.clock.step(1)
// }
// val results = Array.ofDim[Float](2, 2)
// val captured = Array.ofDim[Boolean](2, 2)
// // drain with en=false
// for (d <- 0 until 4) {
//   dut.io.en.poke(false.B)
//   dut.io.row(0).poke(0.U); dut.io.row(1).poke(0.U)
//   dut.io.col(0).poke(0.U); dut.io.col(1).poke(0.U)
//   dut.clock.step(1)
//   for(i <- 0 until 2; j <- 0 until 2) {
//     val out = toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)
//     if (!captured(i)(j) && math.abs(out) > 0.1f) {
//       results(i)(j) = out
//       captured(i)(j) = true
//     }
//   }
  
// }
// println("\nFinal results:")
// for (i <- 0 until 2) {
//   for (j <- 0 until 2) {
//     print(f"${results(i)(j)} ")
//   }
//   println()
// }
    
//   })
// }


"TOP tiled 200x100x50" should "match PyTorch output" in {
  test(new Top(4, 4)).withAnnotations(Seq(VerilatorBackendAnnotation))({ dut =>

    def loadMatrix(path: String): Array[Array[Float]] =
      scala.io.Source.fromFile(path).getLines()
        .filterNot(_.trim.isEmpty)
        .map(_.trim.split("\\s+").map(_.toFloat))
        .toArray

    val A     = loadMatrix("/home/maruthi/dev/ChiselVectorPE/src/test/scala/test_files/A.txt")   // 200 × 100
    val B     = loadMatrix("/home/maruthi/dev/ChiselVectorPE/src/test/scala/test_files/B.txt")   // 100 × 50
    val C_ref = loadMatrix("/home/maruthi/dev/ChiselVectorPE/src/test/scala/test_files/C.txt")   // 200 × 50

    val M    = A.length        // 200
    val K    = A(0).length     // 100
    val N    = B(0).length     //  50
    val rows = 4
    val cols = 4

    val PIPE_DEPTH  = 3
    val MAX_SKEW    = (rows - 1) * PIPE_DEPTH   // 9  for 4×4
    val GAP         = PIPE_DEPTH + MAX_SKEW      // 12 for 4×4
    val totalCycles = K * GAP + MAX_SKEW
    val DRAIN       = MAX_SKEW + PIPE_DEPTH + 2  // 14, safe margin

    val C_out = Array.ofDim[Float](M, N)

    for (tileRow <- 0 until M / rows) {   // 0..49
      for (tileCol <- 0 until N / cols) { // 0..12

        val rBase = tileRow * rows
        val cBase = tileCol * cols

        // ── Reset ──────────────────────────────────────────
        dut.io.res.poke(true.B)
        dut.io.en.poke(false.B)
        for (i <- 0 until rows) dut.io.row(i).poke(0.U)
        for (j <- 0 until cols) dut.io.col(j).poke(0.U)
        dut.clock.step(1)
        dut.io.res.poke(false.B)

        // ── Stream ─────────────────────────────────────────
        for (c <- 0 until totalCycles) {
          val k       = c / GAP
          val offset  = c % GAP
          val driving = offset < PIPE_DEPTH && k < K

          dut.io.en.poke(true.B)
          dut.io.mode.poke(true.B)

          // ── FIX 1: drive ALL rows and cols, not just 0 and 1 ──
          for (i <- 0 until rows) {
            val a_val = if (driving) toFP16(A(rBase + i)(k)) else 0L
            dut.io.row(i).poke(a_val.U)
          }
          for (j <- 0 until cols) {
            val b_val = if (driving) toFP16(B(k)(cBase + j)) else 0L
            dut.io.col(j).poke(b_val.U)
          }

          dut.clock.step(1)
        }

        // ── Drain ──────────────────────────────────────────
        // FIX 2: drain window must cover MAX_SKEW, not hardcoded 6
        val tileResult = Array.ofDim[Float](rows, cols)
        val captured   = Array.ofDim[Boolean](rows, cols)

        for (_ <- 0 until DRAIN) {
          dut.io.en.poke(false.B)
          for (i <- 0 until rows) dut.io.row(i).poke(0.U)
          for (j <- 0 until cols) dut.io.col(j).poke(0.U)
          dut.clock.step(1)

          for (i <- 0 until rows; j <- 0 until cols) {
            if (!captured(i)(j)) {
              val v = toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)
              if (math.abs(v) > 1e-4f) {
                tileResult(i)(j) = v
                captured(i)(j)   = true
              }
            }
          }
        }

        for (i <- 0 until rows; j <- 0 until cols)
          C_out(rBase + i)(cBase + j) = tileResult(i)(j)
      }
    }

    // ── Compare ────────────────────────────────────────────
   var maxAbsErr = 0f
var maxRelErr = 0f
var badCount  = 0

for (i <- 0 until M; j <- 0 until N) {
  val abs = math.abs(C_out(i)(j) - C_ref(i)(j))
  val rel = if (math.abs(C_ref(i)(j)) > 1e-3f)
              abs / math.abs(C_ref(i)(j)) else 0f
  if (abs > maxAbsErr) maxAbsErr = abs
  if (rel > maxRelErr) maxRelErr = rel
  if (rel > 0.01f) badCount += 1   // >1% relative error = truly wrong
}

println(f"Max absolute error : $maxAbsErr%.4f")
println(f"Max relative error : ${maxRelErr*100}%.3f%%")
println(f"Elements with >1%% rel err: $badCount / ${M*N}")

    println("\nHW output (top-left 4×4):")
    for (i <- 0 until 16)
      println((0 until 16).map(j => f"${C_out(i)(j)}%8.3f").mkString(" "))
    println("\nRef (top-left 4×4):")
    for (i <- 0 until 16)
      println((0 until 16).map(j => f"${C_ref(i)(j)}%8.3f").mkString(" "))





      var nearZeroBad = 0
var trulyWrong = 0
for (i <- 0 until M; j <- 0 until N) {
  val abs = math.abs(C_out(i)(j) - C_ref(i)(j))
  val rel = if (math.abs(C_ref(i)(j)) > 1e-3f)
              abs / math.abs(C_ref(i)(j)) else 0f
  if (rel > 0.01f) {
    if (math.abs(C_ref(i)(j)) < 0.5f)
      nearZeroBad += 1    // ref is near zero — FP16 rounding dominates
    else
      trulyWrong  += 1    // ref is large but HW is wrong — real bug
    if (trulyWrong <= 5)  // print first 5 truly wrong ones
      println(f"  BAD ($i,$j): hw=${C_out(i)(j)}%.4f  ref=${C_ref(i)(j)}%.4f  abs=$abs%.4f")
  }
}
println(f"Near-zero ref (FP16 noise): $nearZeroBad")
println(f"Truly wrong (large ref):    $trulyWrong")
  })

  
}
//   "TOP" should "show outputs" in {
//     test(new Top(2,2))({ dut=>

//         println("\n--- TOP TESTING ---")

//         // tensor([[0.1525, 0.3970],
// //          [0.8703, 0.7563]]),
// //  tensor([[0.1836, 0.0991],

// // push each k-step for PIPE_DEPTH cycles so feedback returns in time
// // A[row][col], B[row][col] — standard layout
// val A = Array(
//   Array(0.1525f, 0.3970f),  // row 0
//   Array(0.8703f, 0.7563f)   // row 1
// )
// val B = Array(
//   Array(0.1836f, 0.0991f),  // row 0
//   Array(0.1583f, 0.0066f)   // row 1
// )

// // at k-step k:
// // row input i = A[i][k]   (i-th row of A, k-th column)
// // col input j = B[k][j]   (k-th row of B, j-th column)
// val PIPE_DEPTH = 3
// val SKEW_DELAY = 3
// val K          = 2

// // Each k needs PIPE_DEPTH cycles. Last k needs extra SKEW_DELAY for PE(1,1).
// // But PE(0,0) must only see each k exactly PIPE_DEPTH times.
// // Solution: push k normally for PIPE_DEPTH, then push ZEROS for skew tail
// // so PE(0,0) gets zeros but skew buffers deliver last k to PE(1,1).

// for (k <- 0 until K) {
//   for (_ <- 0 until PIPE_DEPTH) {
//     dut.io.res.poke(false.B)
//     dut.io.en.poke(true.B)
//     dut.io.mode.poke(true.B)
//     dut.io.row(0).poke(toFP16(A(0)(k)).U)
//     dut.io.row(1).poke(toFP16(A(1)(k)).U)
//     dut.io.col(0).poke(toFP16(B(k)(0)).U)
//     dut.io.col(1).poke(toFP16(B(k)(1)).U)
//     dut.clock.step(1)
//   }
// }

// // Skew tail: push ZEROS so PE(0,0) accumulates nothing,
// // but the skew buffers finish delivering last k to PE(1,1)
// for (_ <- 0 until SKEW_DELAY) {
//   dut.io.res.poke(false.B)
//   dut.io.en.poke(true.B)
//   dut.io.mode.poke(true.B)
//   dut.io.row(0).poke(0.U)
//   dut.io.row(1).poke(0.U)
//   dut.io.col(0).poke(0.U)
//   dut.io.col(1).poke(0.U)
//   dut.clock.step(1)
// }

//         for (d <- 0 until 15) {
//           dut.io.en.poke(false.B)
//           dut.io.row(0).poke(0.U); dut.io.row(1).poke(0.U)
//           dut.io.col(0).poke(0.U); dut.io.col(1).poke(0.U)
//           dut.clock.step(1)
//           val r00 = toFloat(dut.io.out_sum(0)(0).peek().litValue.toLong)
//           val r01 = toFloat(dut.io.out_sum(0)(1).peek().litValue.toLong)
//           val r10 = toFloat(dut.io.out_sum(1)(0).peek().litValue.toLong)
//           val r11 = toFloat(dut.io.out_sum(1)(1).peek().litValue.toLong)
//           println(f"d$d: (0,0)=$r00  (0,1)=$r01  (1,0)=$r10  (1,1)=$r11")
//         }

//     })
// }

}