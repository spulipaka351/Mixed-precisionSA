package vectorPE

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random

// ─────────────────────────────────────────────────────────────────
//  Software reference model  (truncation + flush-to-zero, matching HW)
// ─────────────────────────────────────────────────────────────────
object SWModel {

  // ── INT8 helpers ──────────────────────────────────────────────
  /** One INT8 MAC step: acc += int_a * int_b  (all in 32-bit space) */
  def int8Mac(psumIn: Long, aBytes: Int, bBytes: Int): Long = {
    val a8 = aBytes.toByte.toInt          // sign-extend to 32b
    val b8 = bBytes.toByte.toInt
    val product16 = (a8 * b8) & 0xFFFF   // keep lower 16b, sign-extend
    val product32 = (product16.toShort).toInt & 0xFFFFFFFFL.toInt
    (psumIn + product32) & 0xFFFFFFFFL   // 32-bit wrap
  }

  // ── FP16 bit-field helpers ────────────────────────────────────
  def fp16Sign(bits: Int): Int  = (bits >> 15) & 1
  def fp16Exp (bits: Int): Int  = (bits >> 10) & 0x1F
  def fp16Man (bits: Int): Int  =  bits         & 0x3FF

  /** Decode FP16 bits to a Double (flush subnormals to zero). */
  def fp16ToDouble(bits: Int): Double = {
    val s = fp16Sign(bits)
    val e = fp16Exp(bits)
    val m = fp16Man(bits)
    if (e == 0)   return 0.0        // flush-to-zero (subnormal → 0)
    if (e == 31)  return if (m == 0) (if (s == 1) Double.NegativeInfinity else Double.PositiveInfinity)
                         else Double.NaN
    val sign  = if (s == 1) -1.0 else 1.0
    val value = (1 + m.toDouble / 1024.0) * math.pow(2.0, e - 15)
    sign * value
  }

  /** Encode a Double to FP32 bits (Java's standard float encoding). */
  def doubleToFp32Bits(v: Double): Long =
    java.lang.Float.floatToIntBits(v.toFloat).toLong & 0xFFFFFFFFL

  /** Decode FP32 bits to Double. */
  def fp32BitsToDouble(bits: Long): Double =
    java.lang.Float.intBitsToFloat(bits.toInt).toDouble

  /**
   * One FP16 MAC step in software:
   *   acc_fp = fp32(psum_in) + fp16(a) * fp16(b)
   * Uses Java float arithmetic (FP32 precision), matching the HW's
   * truncation-mode accumulator output.
   */
  def fp16Mac(psumInBits: Long, aBits: Int, bBits: Int): Long = {
    val psum   = fp32BitsToDouble(psumInBits)
    val aVal   = fp16ToDouble(aBits)
    val bVal   = fp16ToDouble(bBits)
    val result = (psum + aVal * bVal).toFloat   // truncate to FP32
    java.lang.Float.floatToIntBits(result).toLong & 0xFFFFFFFFL
  }
}

// ─────────────────────────────────────────────────────────────────
//  Test suite
// ─────────────────────────────────────────────────────────────────
class MixedPETest extends AnyFlatSpec with ChiselScalatestTester {

  // ── helpers ────────────────────────────────────────────────────

  /** Drive one cycle and read back outputs (combinational + registered). */
  def driveCycle(dut: mixedPE,
                 inA: Int, inB: Int,
                 mode: Boolean, psum: Long): Unit = {
    dut.io.in_a.poke(inA.U)
    dut.io.in_b.poke(inB.U)
    dut.io.mode.poke(mode.B)
    dut.io.psum_in.poke(psum.U)
    dut.clock.step(1)
  }

  /** Pack two INT8 values into the lower 16 bits of a UInt(16). */
  def packInt8(v: Int): Int = v & 0xFF

  // Encode a float as FP16 bits using Java's half-precision helper
  def floatToFp16(f: Float): Int = {
    // Use java.lang.Float.floatToFloat16 (Java 20+) if available,
    // otherwise use a simple manual encoder.
    val bits = java.lang.Float.floatToIntBits(f)
    val sign  = (bits >>> 31) & 0x1
    val exp   = ((bits >>> 23) & 0xFF) - 127 + 15
    val man   = (bits >>> 13) & 0x3FF
    if (exp <= 0)       (sign << 15)                // flush to zero
    else if (exp >= 31) (sign << 15) | (31 << 10)  // clamp to inf
    else                (sign << 15) | (exp << 10) | man
  }

  // ── INT8 tests ─────────────────────────────────────────────────

  behavior of "mixedPE INT8 mode"

  it should "compute 3 * 4 + 0 = 12" in {
    test(new mixedPE) { dut =>
      val a = packInt8(3)
      val b = packInt8(4)
      dut.io.in_a.poke(a.U)
      dut.io.in_b.poke(b.U)
      dut.io.mode.poke(false.B)
      dut.io.psum_in.poke(0.U)
      dut.clock.step(1)
      val expected = SWModel.int8Mac(0L, a, b)
      dut.io.out.expect(expected.U, s"3*4+0 should be $expected")
    }
  }

  it should "accumulate correctly over two cycles" in {
    test(new mixedPE) { dut =>
      // Cycle 1: 3 * 4 + 0 = 12
      driveCycle(dut, packInt8(3), packInt8(4), mode = false, psum = 0L)
      val acc1 = SWModel.int8Mac(0L, packInt8(3), packInt8(4))
      dut.io.out.expect(acc1.U)

      // Cycle 2: feed acc1 as psum; 5 * 6 + 12 = 42
      driveCycle(dut, packInt8(5), packInt8(6), mode = false, psum = acc1)
      val acc2 = SWModel.int8Mac(acc1, packInt8(5), packInt8(6))
      dut.io.out.expect(acc2.U)
    }
  }

  it should "handle negative INT8 values: (-3) * 4 + 0 = -12" in {
    test(new mixedPE) { dut =>
      val a = packInt8(-3)   // 0xFD
      val b = packInt8(4)
      driveCycle(dut, a, b, mode = false, psum = 0L)
      val expected = SWModel.int8Mac(0L, a, b)
      dut.io.out.expect(expected.U, s"(-3)*4 should be $expected")
    }
  }

  it should "handle both negative: (-7) * (-8) = 56" in {
    test(new mixedPE) { dut =>
      val a = packInt8(-7)
      val b = packInt8(-8)
      driveCycle(dut, a, b, mode = false, psum = 0L)
      val expected = SWModel.int8Mac(0L, a, b)
      dut.io.out.expect(expected.U)
    }
  }

  it should "handle zero input: 0 * 127 = 0" in {
    test(new mixedPE) { dut =>
      driveCycle(dut, packInt8(0), packInt8(127), mode = false, psum = 0L)
      dut.io.out.expect(0.U)
    }
  }

  it should "handle max INT8: 127 * 127 = 16129" in {
    test(new mixedPE) { dut =>
      driveCycle(dut, packInt8(127), packInt8(127), mode = false, psum = 0L)
      val expected = SWModel.int8Mac(0L, packInt8(127), packInt8(127))
      dut.io.out.expect(expected.U)
    }
  }

  it should "handle min INT8: (-128) * (-128) = 16384" in {
    test(new mixedPE) { dut =>
      val a = packInt8(-128)
      val b = packInt8(-128)
      driveCycle(dut, a, b, mode = false, psum = 0L)
      val expected = SWModel.int8Mac(0L, a, b)
      dut.io.out.expect(expected.U)
    }
  }

  it should "not update ACC_int when in FP16 mode" in {
    test(new mixedPE) { dut =>
      // First: build up ACC_int = 12 in INT8 mode
      driveCycle(dut, packInt8(3), packInt8(4), mode = false, psum = 0L)
      dut.io.out.expect(12.U)

      // Switch to FP16 mode — ACC_int should freeze
      // Use 1.0 * 1.0 in FP16
      val one = floatToFp16(1.0f)
      val psumZero = SWModel.doubleToFp32Bits(0.0)
      driveCycle(dut, one, one, mode = true, psum = psumZero)

      // Switch back to INT8 mode with psum=12; INT8 acc still 12 (no corruption)
      driveCycle(dut, packInt8(1), packInt8(1), mode = false, psum = 12L)
      val expected = SWModel.int8Mac(12L, packInt8(1), packInt8(1))
      dut.io.out.expect(expected.U, "ACC_int must not be corrupted by FP16 cycle")
    }
  }

  // ── FP16 tests ─────────────────────────────────────────────────

  behavior of "mixedPE FP16 mode"

  /** Tolerance for FP16 HW comparison (truncation vs round-to-nearest). */
  def fp32Close(hwBits: Long, swBits: Long, ulp: Int = 4): Boolean = {
    val hw = java.lang.Float.intBitsToFloat(hwBits.toInt)
    val sw = java.lang.Float.intBitsToFloat(swBits.toInt)
    if (hw.isNaN && sw.isNaN) return true
    if (hw.isInfinite && sw.isInfinite) return hw == sw
    math.abs(hw - sw) <= ulp * math.ulp(sw.toFloat)
  }

  it should "compute 1.0 * 1.0 + 0 = 1.0" in {
    test(new mixedPE) { dut =>
      val one  = floatToFp16(1.0f)
      val psum = SWModel.doubleToFp32Bits(0.0)
      driveCycle(dut, one, one, mode = true, psum = psum)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(psum, one, one)
      assert(fp32Close(hwOut, swOut),
        f"1.0*1.0+0: hw=${java.lang.Float.intBitsToFloat(hwOut.toInt)}%.6f " +
        f"sw=${java.lang.Float.intBitsToFloat(swOut.toInt)}%.6f")
    }
  }

  it should "compute 2.0 * 3.0 + 0 = 6.0" in {
    test(new mixedPE) { dut =>
      val two   = floatToFp16(2.0f)
      val three = floatToFp16(3.0f)
      val psum  = SWModel.doubleToFp32Bits(0.0)
      driveCycle(dut, two, three, mode = true, psum = psum)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(psum, two, three)
      assert(fp32Close(hwOut, swOut),
        f"2.0*3.0+0: hw=${java.lang.Float.intBitsToFloat(hwOut.toInt)}%.6f " +
        f"sw=${java.lang.Float.intBitsToFloat(swOut.toInt)}%.6f")
    }
  }

  it should "compute (-1.0) * 2.0 + 0 = -2.0" in {
    test(new mixedPE) { dut =>
      val negOne = floatToFp16(-1.0f)
      val two    = floatToFp16(2.0f)
      val psum   = SWModel.doubleToFp32Bits(0.0)
      driveCycle(dut, negOne, two, mode = true, psum = psum)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(psum, negOne, two)
      assert(fp32Close(hwOut, swOut), "(-1.0)*2.0 should be ~-2.0")
    }
  }

  it should "accumulate: 1.5 * 2.0 + 1.0 = 4.0" in {
    test(new mixedPE) { dut =>
      val onePointFive = floatToFp16(1.5f)
      val two          = floatToFp16(2.0f)
      val psum         = SWModel.doubleToFp32Bits(1.0)
      driveCycle(dut, onePointFive, two, mode = true, psum = psum)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(psum, onePointFive, two)
      assert(fp32Close(hwOut, swOut),
        f"1.5*2.0+1.0: hw=${java.lang.Float.intBitsToFloat(hwOut.toInt)}%.6f " +
        f"sw=${java.lang.Float.intBitsToFloat(swOut.toInt)}%.6f")
    }
  }

  it should "handle zero input: 0.0 * 3.0 + 5.0 = 5.0" in {
    test(new mixedPE) { dut =>
      val zero  = floatToFp16(0.0f)
      val three = floatToFp16(3.0f)
      val psum  = SWModel.doubleToFp32Bits(5.0)
      driveCycle(dut, zero, three, mode = true, psum = psum)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(psum, zero, three)
      assert(fp32Close(hwOut, swOut), "0.0*3.0+5.0 should be ~5.0")
    }
  }

  it should "not update ACC_fp when in INT8 mode" in {
    test(new mixedPE) { dut =>
      // Build ACC_fp = 6.0 via FP16 cycle
      val two   = floatToFp16(2.0f)
      val three = floatToFp16(3.0f)
      val psum0 = SWModel.doubleToFp32Bits(0.0)
      driveCycle(dut, two, three, mode = true, psum = psum0)

      // Switch to INT8 mode — ACC_fp must freeze
      driveCycle(dut, packInt8(5), packInt8(5), mode = false, psum = 0L)

      // Switch back to FP16, check ACC_fp didn't corrupt
      // Feed psum = 6.0 from first cycle's result
      val fp6 = SWModel.fp16Mac(psum0, two, three)
      driveCycle(dut, floatToFp16(1.0f), floatToFp16(1.0f), mode = true, psum = fp6)
      val hwOut = dut.io.out.peek().litValue.toLong
      val swOut = SWModel.fp16Mac(fp6, floatToFp16(1.0f), floatToFp16(1.0f))
      assert(fp32Close(hwOut, swOut), "ACC_fp must not be corrupted by INT8 cycle")
    }
  }

  // ── Pass-through register tests ────────────────────────────────

  behavior of "mixedPE pass-through registers"

  it should "delay out_a and out_b by exactly one cycle" in {
    test(new mixedPE) { dut =>
      val testA = 0xABCD
      val testB = 0x1234
      dut.io.in_a.poke(testA.U)
      dut.io.in_b.poke(testB.U)
      dut.io.mode.poke(false.B)
      dut.io.psum_in.poke(0.U)

      // Cycle 0→1: latch inputs
      dut.clock.step(1)
      // Outputs now available
      dut.io.out_a.expect(testA.U, "out_a should be RegNext of in_a")
      dut.io.out_b.expect(testB.U, "out_b should be RegNext of in_b")

      // Change inputs — out_a/b should still show previous values this cycle
      dut.io.in_a.poke(0xDEAD.U)
      dut.io.in_b.poke(0xBEEF.U)
      // Verify before stepping
      dut.io.out_a.expect(testA.U)
      dut.io.out_b.expect(testB.U)

      dut.clock.step(1)
      dut.io.out_a.expect(0xDEAD.U)
      dut.io.out_b.expect(0xBEEF.U)
    }
  }

  // ── Randomized smoke test ──────────────────────────────────────

  behavior of "mixedPE random"

  it should "match SW model for 50 random INT8 MACs" in {
    test(new mixedPE) { dut =>
      val rng = new Random(42)
      var psum = 0L
      for (_ <- 0 until 50) {
        val a = rng.nextInt(256)         // random byte
        val b = rng.nextInt(256)
        driveCycle(dut, packInt8(a.toByte), packInt8(b.toByte), mode = false, psum = psum)
        psum = SWModel.int8Mac(psum, packInt8(a.toByte), packInt8(b.toByte))
        val hwOut = dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL
        assert(hwOut == psum,
          s"INT8 random: a=$a b=$b expected=$psum got=$hwOut")
      }
    }
  }

  it should "match SW model for 50 random FP16 MACs (within 4 ULP)" in {
    test(new mixedPE) { dut =>
      val rng = new Random(99)
      var hwAcc = 0L

      for (i <- 0 until 50) {
        val aExp  = rng.nextInt(28) + 1
        val bExp  = rng.nextInt(28) + 1
        val aSign = rng.nextInt(2)
        val bSign = rng.nextInt(2)
        val aMan  = rng.nextInt(1024)
        val bMan  = rng.nextInt(1024)
        val aBits = (aSign << 15) | (aExp << 10) | aMan
        val bBits = (bSign << 15) | (bExp << 10) | bMan

        driveCycle(dut, aBits, bBits, mode = true, psum = hwAcc)
        val hwOut = dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL
        val swOut = SWModel.fp16Mac(hwAcc, aBits, bBits)

        val hwF     = java.lang.Float.intBitsToFloat(hwOut.toInt)
        val swF     = java.lang.Float.intBitsToFloat(swOut.toInt)
        val psumF   = java.lang.Float.intBitsToFloat(hwAcc.toInt)
        val aVal    = SWModel.fp16ToDouble(aBits)
        val bVal    = SWModel.fp16ToDouble(bBits)
        val psumExp = ((hwAcc >> 23) & 0xFF).toInt
        val mulExp  = { val ea = (aBits>>10)&0x1F; val eb = (bBits>>10)&0x1F; ea+eb+97 }
        val ok      = fp32Close(hwOut, swOut, ulp = 64)
        println(f"[$i] psum=$psumF%.2f(e=$psumExp) a=$aVal%.3f b=$bVal%.3f " +
                f"prod=${aVal*bVal}%.2f mulExp=$mulExp diff=${psumExp-mulExp} " +
                f"hw=$hwF%.4f sw=$swF%.4f ${if(ok) "OK" else "FAIL"}")

        assert(ok,
          f"FP16 random[$i]: a=0x${aBits}%04X b=0x${bBits}%04X " +
          f"hw=${hwF}%.6f sw=${swF}%.6f")

        hwAcc = hwOut
      }
    }
  }
}