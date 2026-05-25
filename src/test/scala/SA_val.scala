package Pipe

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

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
  // ── PipePE: push 2.0 * 3.0 + 0.0, wait 3 cycles, read ─

  // ── PipeSA: push one k-step, drain, print all PEs ──
  

    "PipePE" should "handle negatives" in {
  test(new PipePE()) { dut =>

    val cases = Seq(
      (2f,  -3f,  0f,   -6f),    // pos * neg + 0
      (-2f, -3f,  0f,    6f),    // neg * neg + 0
      (2f,   3f, -10f,  -4f),   // pos * pos + neg psum
      (2f,  -3f,  10f,   4f),   // pos * neg + pos psum
      (-2f,  3f, -10f, -16f)    // neg * pos + neg psum
    )

    for ((a, b, psum, expected) <- cases) {
      dut.io.in_a.poke(toFP16(a).U)
      dut.io.in_b.poke(toFP16(b).U)
      dut.io.psum_in.poke(toFP32Bits(psum).U)
      dut.io.mode.poke(true.B)
      dut.clock.step(3)
      val out = toFloat(dut.io.out.peek().litValue.toLong)
      val ok  = math.abs(out - expected) < 0.1f
      println(f"$a * $b + $psum = $out%.4f  (expect $expected)  ${if(ok) "PASS" else "FAIL"}")
    }
  }
}


  "TOP timing" should "trace per-cycle inputs" in {
  test(new Top(2,2))({ dut =>

    val A = Array(Array( -0.9014f,0.4814f,1.1982f,0.9062f,0.7998f,-0.2549f,-1.8877f,-0.1833f,-0.0255f,-0.6367f,1.3623f,-0.9907f,-0.6387f,0.9312f,-0.4102f,-1.4893f),Array( 0.2206f,-1.3213f,0.2798f,-0.0704f,-1.3936f,0.7725f,-0.2142f,0.3984f,-0.8027f,0.7852f,-0.5044f,0.9155f,0.8540f,0.1736f,0.2023f,-0.1366f)
)

    val B = Array(Array( -0.2927f,1.0859f),Array( 0.9521f,-1.6553f),Array( -2.5254f,-1.6973f),Array( -0.6855f,-0.6328f),Array( 0.1732f,0.3403f),Array( -0.1177f,-1.0010f),Array( 2.7363f,1.4199f),Array( 1.4170f,-0.3057f),Array( -0.1031f,-0.3955f),Array( 1.0488f,2.3613f),Array( -0.6748f,0.0058f),Array( -0.1392f,-0.7124f),Array( 1.0791f,0.5532f),Array( 0.9736f,1.1797f),Array( 1.2373f,0.9121f),Array( -0.8535f,0.3325f)

)
    
    val rows = 2

val PIPE_DEPTH = 3
val MAX_SKEW   = (rows - 1) * PIPE_DEPTH  // = 3 for 2x2
val GAP        = PIPE_DEPTH + MAX_SKEW    // = 6, feedback round-trip for slowest PE
val K          = 16

// total cycles = K*GAP + MAX_SKEW = 2*6 + 3 = 15
println("sanity check")
val totalCycles = K * GAP + MAX_SKEW
Seq(0.0154f, -0.5395f, -1.4374f, -0.7193f, 1.7864f).foreach { v =>
  val back = fp16BitsToFloat(toFP16(v))
  println(f"$v%.4f → $back%.4f")
}
for (c <- 0 until totalCycles) {
  val k       = c / GAP          // which k-step this cycle belongs to
  val offset  = c % GAP          // position within this k-step's window
  val driving = offset < PIPE_DEPTH && k < K  // real data or zero

  dut.io.res.poke(false.B)
  dut.io.en.poke(true.B)
  dut.io.mode.poke(true.B)
  if (driving) {
    dut.io.row(0).poke(toFP16(A(0)(k)).U)
    dut.io.row(1).poke(toFP16(A(1)(k)).U)
    dut.io.col(0).poke(toFP16(B(k)(0)).U)
    dut.io.col(1).poke(toFP16(B(k)(1)).U)
  } else {
    dut.io.row(0).poke(0.U); dut.io.row(1).poke(0.U)
    dut.io.col(0).poke(0.U); dut.io.col(1).poke(0.U)
  }
  dut.clock.step(1)
}
val results = Array.ofDim[Float](2, 2)
val captured = Array.ofDim[Boolean](2, 2)
// drain with en=false
for (d <- 0 until 4) {
  dut.io.en.poke(false.B)
  dut.io.row(0).poke(0.U); dut.io.row(1).poke(0.U)
  dut.io.col(0).poke(0.U); dut.io.col(1).poke(0.U)
  dut.clock.step(1)
  for(i <- 0 until 2; j <- 0 until 2) {
    val out = toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)
    if (!captured(i)(j) && math.abs(out) > 0.1f) {
      results(i)(j) = out
      captured(i)(j) = true
    }
  }
  
}
println("\nFinal results:")
for (i <- 0 until 2) {
  for (j <- 0 until 2) {
    print(f"${results(i)(j)} ")
  }
  println()
}
    
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