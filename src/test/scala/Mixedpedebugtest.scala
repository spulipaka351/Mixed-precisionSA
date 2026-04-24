package Mixed

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// ─────────────────────────────────────────────────────────────────
// Debug PE — exposes internal signals so we can see what the HW
// is actually computing. Drop this in src/main/scala/vectorPE/
// Run: sbt "testOnly vectorPE.MixedPEDebugTest"
// Delete after debugging is done.
// ─────────────────────────────────────────────────────────────────
class mixedPE_debug extends Module {
  val io = IO(new Bundle {
    val in_a    = Input(UInt(16.W))
    val in_b    = Input(UInt(16.W))
    val mode    = Input(Bool())
    val psum_in = Input(UInt(32.W))

    val out   = Output(UInt(32.W))
    val out_a = Output(UInt(16.W))
    val out_b = Output(UInt(16.W))

    // ── exposed internals ──
    val dbg_raw_man      = Output(UInt(22.W))
    val dbg_mul_norm_man = Output(UInt(23.W))
    val dbg_mul_norm_exp = Output(UInt(8.W))
    val dbg_mul_man_full = Output(UInt(24.W))
    val dbg_sum_man      = Output(UInt(25.W))
    val dbg_norm_man     = Output(UInt(23.W))
    val dbg_norm_exp     = Output(UInt(8.W))
    val dbg_final_sign   = Output(UInt(1.W))
    val dbg_common_exp   = Output(UInt(8.W))
    val dbg_psum_exp     = Output(UInt(8.W))
    val dbg_shared_mul   = Output(UInt(24.W))
  })

  val ACC_int = RegInit(0.U(32.W))
  val ACC_fp  = RegInit(0.U(32.W))

  val sign_a = io.in_a(15)
  val exp_a  = io.in_a(14, 10)
  val man_a  = io.in_a(9, 0)
  val sign_b = io.in_b(15)
  val exp_b  = io.in_b(14, 10)
  val man_b  = io.in_b(9, 0)

  val hidden_a     = Mux(exp_a === 0.U, 0.U(1.W), 1.U(1.W))
  val hidden_b     = Mux(exp_b === 0.U, 0.U(1.W), 1.U(1.W))
  val fp_man_a_11b = Cat(hidden_a, man_a)
  val fp_man_b_11b = Cat(hidden_b, man_b)

  val int_a = io.in_a(7, 0).asSInt
  val int_b = io.in_b(7, 0).asSInt

  val shared_in_a = Wire(SInt(12.W))
  val shared_in_b = Wire(SInt(12.W))
  when(io.mode) {
    shared_in_a := Cat(0.U(1.W), fp_man_a_11b).asSInt
    shared_in_b := Cat(0.U(1.W), fp_man_b_11b).asSInt
  } .otherwise {
    shared_in_a := int_a.pad(12)
    shared_in_b := int_b.pad(12)
  }
  val shared_mul_result = shared_in_a * shared_in_b

  val out_int = shared_mul_result(15, 0).asSInt.pad(32).asUInt
  when(!io.mode) { ACC_int := io.psum_in + out_int }

  val out_fp_sign    = sign_a ^ sign_b
  val out_fp_exp_raw = (exp_a +& exp_b).pad(8)   // UInt(8.W)

  val a_is_zero       = (exp_a === 0.U) && (man_a === 0.U)
  val b_is_zero       = (exp_b === 0.U) && (man_b === 0.U)
  val product_is_zero = a_is_zero || b_is_zero

  val raw_man      = shared_mul_result(21, 0).asUInt
  val mul_norm_man = Wire(UInt(23.W))
  val mul_norm_exp = Wire(UInt(8.W))

  when(product_is_zero) {
    mul_norm_man := 0.U
    mul_norm_exp := 0.U
  } .elsewhen(raw_man(21) === 1.U) {
    mul_norm_man := Cat(raw_man(20, 0), 0.U(2.W))
    mul_norm_exp := out_fp_exp_raw + 98.U
  } .otherwise {
    mul_norm_man := Cat(raw_man(19, 0), 0.U(3.W))
    mul_norm_exp := out_fp_exp_raw + 97.U
  }

  val psum_fp_sign  = io.psum_in(31)
  val psum_fp_exp   = io.psum_in(30, 23)
  val psum_fp_man   = io.psum_in(22, 0)

  val psum_man_full = Mux(psum_fp_exp === 0.U,
                          Cat(0.U(1.W), psum_fp_man),
                          Cat(1.U(1.W), psum_fp_man))
  val mul_man_full  = Mux(product_is_zero,
                          0.U(24.W),
                          Cat(1.U(1.W), mul_norm_man))

  val exp_diff         = Wire(UInt(8.W))
  val aligned_mul_man  = Wire(UInt(24.W))
  val aligned_psum_man = Wire(UInt(24.W))
  val common_exp       = Wire(UInt(8.W))

  when(mul_norm_exp > psum_fp_exp) {
    exp_diff         := mul_norm_exp - psum_fp_exp
    aligned_psum_man := psum_man_full >> exp_diff
    aligned_mul_man  := mul_man_full
    common_exp       := mul_norm_exp
  } .elsewhen(psum_fp_exp > mul_norm_exp) {
    exp_diff         := psum_fp_exp - mul_norm_exp
    aligned_mul_man  := mul_man_full >> exp_diff
    aligned_psum_man := psum_man_full
    common_exp       := psum_fp_exp
  } .otherwise {
    exp_diff         := 0.U
    aligned_mul_man  := mul_man_full
    aligned_psum_man := psum_man_full
    common_exp       := mul_norm_exp
  }

  val signs_match = out_fp_sign === psum_fp_sign
  val sum_man     = Wire(UInt(25.W))
  val final_sign  = Wire(UInt(1.W))

  when(signs_match) {
    sum_man    := aligned_mul_man +& aligned_psum_man
    final_sign := out_fp_sign
  } .otherwise {
    when(aligned_mul_man >= aligned_psum_man) {
      sum_man    := aligned_mul_man - aligned_psum_man
      final_sign := out_fp_sign
    } .otherwise {
      sum_man    := aligned_psum_man - aligned_mul_man
      final_sign := psum_fp_sign
    }
  }

  val norm_man = Wire(UInt(23.W))
  val norm_exp = Wire(UInt(8.W))

  when(sum_man === 0.U) {
    norm_man := 0.U; norm_exp := 0.U
  } .elsewhen(sum_man(24) === 1.U) {
    norm_man := sum_man(23, 1); norm_exp := common_exp + 1.U
  } .elsewhen(sum_man(23) === 1.U) {
    norm_man := sum_man(22, 0); norm_exp := common_exp
  } .otherwise {
    val leading_one = PriorityEncoder(Reverse(sum_man(22, 0)))
    val shift_amt   = 23.U - leading_one
    norm_man := (sum_man(22, 0) << shift_amt)(22, 0)
    when(shift_amt < common_exp) { norm_exp := common_exp - shift_amt }
    .otherwise                   { norm_exp := 0.U }
  }

  when(io.mode) { ACC_fp := Cat(final_sign, norm_exp, norm_man) }

  io.out   := Mux(io.mode, ACC_fp, ACC_int)
  io.out_a := RegNext(io.in_a)
  io.out_b := RegNext(io.in_b)

  // wire out all debug signals
  io.dbg_raw_man      := raw_man
  io.dbg_mul_norm_man := mul_norm_man
  io.dbg_mul_norm_exp := mul_norm_exp
  io.dbg_mul_man_full := mul_man_full
  io.dbg_sum_man      := sum_man
  io.dbg_norm_man     := norm_man
  io.dbg_norm_exp     := norm_exp
  io.dbg_final_sign   := final_sign
  io.dbg_common_exp   := common_exp
  io.dbg_psum_exp     := psum_fp_exp
  io.dbg_shared_mul   := shared_mul_result.asUInt
}

class MixedPEDebugTest extends AnyFlatSpec with ChiselScalatestTester {

  def floatToFp16(f: Float): Int = {
    val bits  = java.lang.Float.floatToIntBits(f)
    val sign  = (bits >>> 31) & 0x1
    val exp   = ((bits >>> 23) & 0xFF) - 127 + 15
    val man   = (bits >>> 13) & 0x3FF
    if (exp <= 0) sign << 15
    else if (exp >= 31) (sign << 15) | (31 << 10)
    else (sign << 15) | (exp << 10) | man
  }

  def fp32BitsToFloat(bits: Long): Float =
    java.lang.Float.intBitsToFloat(bits.toInt)

  behavior of "mixedPE_debug FP16 internals"

  it should "print internals for 2.0 * 3.0 + 0" in {
    test(new mixedPE_debug) { dut =>
      val two   = floatToFp16(2.0f)
      val three = floatToFp16(3.0f)
      println(f"  2.0 FP16 bits = 0x${two}%04X")
      println(f"  3.0 FP16 bits = 0x${three}%04X")

      dut.io.in_a.poke(two.U)
      dut.io.in_b.poke(three.U)
      dut.io.mode.poke(true.B)
      dut.io.psum_in.poke(0.U)
      dut.clock.step(1)

      val rawMul    = dut.io.dbg_shared_mul.peek().litValue.toLong
      val rawMan    = dut.io.dbg_raw_man.peek().litValue.toLong
      val normMan   = dut.io.dbg_mul_norm_man.peek().litValue.toLong
      val normExp   = dut.io.dbg_mul_norm_exp.peek().litValue.toLong
      val manFull   = dut.io.dbg_mul_man_full.peek().litValue.toLong
      val psumExp   = dut.io.dbg_psum_exp.peek().litValue.toLong
      val commonExp = dut.io.dbg_common_exp.peek().litValue.toLong
      val sumMan    = dut.io.dbg_sum_man.peek().litValue.toLong
      val normManO  = dut.io.dbg_norm_man.peek().litValue.toLong
      val normExpO  = dut.io.dbg_norm_exp.peek().litValue.toLong
      val sign      = dut.io.dbg_final_sign.peek().litValue.toLong
      val hwOut     = dut.io.out.peek().litValue.toLong

      println(f"  shared_mul_result  = 0x${rawMul}%06X  ($rawMul)")
      println(f"  raw_man [21:0]     = 0x${rawMan}%06X  ($rawMan)")
      println(f"    bit21=${(rawMan>>21)&1} bit20=${(rawMan>>20)&1} bit19=${(rawMan>>19)&1}")
      println(f"  mul_norm_man       = 0x${normMan}%06X")
      println(f"  mul_norm_exp       = $normExp  (FP32 real exp = ${normExp.toInt - 127})")
      println(f"  mul_man_full       = 0x${manFull}%06X  ($manFull)")
      println(f"  psum_fp_exp        = $psumExp")
      println(f"  common_exp         = $commonExp")
      println(f"  sum_man            = 0x${sumMan}%08X  ($sumMan)")
      println(f"    bit24=${(sumMan>>24)&1} bit23=${(sumMan>>23)&1}")
      println(f"  norm_man (out)     = 0x${normManO}%06X")
      println(f"  norm_exp (out)     = $normExpO  (FP32 real exp = ${normExpO.toInt - 127})")
      println(f"  final_sign         = $sign")
      println(f"  ACC_fp / hw out    = 0x${hwOut}%08X  = ${fp32BitsToFloat(hwOut)}")
      println(f"  Expected           = 6.0  (0x40C00000)")
    }
  }

  it should "print internals for 1.5 * 2.0 + 1.0" in {
    test(new mixedPE_debug) { dut =>
      val onePfive = floatToFp16(1.5f)
      val two      = floatToFp16(2.0f)
      // psum = 1.0 in FP32
      val psumBits = java.lang.Float.floatToIntBits(1.0f).toLong & 0xFFFFFFFFL
      println(f"\n  1.5 FP16 = 0x${onePfive}%04X   2.0 FP16 = 0x${two}%04X")
      println(f"  psum_in (1.0 FP32) = 0x${psumBits}%08X")

      dut.io.in_a.poke(onePfive.U)
      dut.io.in_b.poke(two.U)
      dut.io.mode.poke(true.B)
      dut.io.psum_in.poke(psumBits.U)
      dut.clock.step(1)

      val rawMan    = dut.io.dbg_raw_man.peek().litValue.toLong
      val normExp   = dut.io.dbg_mul_norm_exp.peek().litValue.toLong
      val manFull   = dut.io.dbg_mul_man_full.peek().litValue.toLong
      val psumExp   = dut.io.dbg_psum_exp.peek().litValue.toLong
      val commonExp = dut.io.dbg_common_exp.peek().litValue.toLong
      val sumMan    = dut.io.dbg_sum_man.peek().litValue.toLong
      val normExpO  = dut.io.dbg_norm_exp.peek().litValue.toLong
      val hwOut     = dut.io.out.peek().litValue.toLong

      println(f"  raw_man            = 0x${rawMan}%06X  bit21=${(rawMan>>21)&1} bit20=${(rawMan>>20)&1}")
      println(f"  mul_norm_exp       = $normExp  (real = ${normExp.toInt - 127})")
      println(f"  mul_man_full       = 0x${manFull}%06X")
      println(f"  psum_fp_exp        = $psumExp  (real = ${psumExp.toInt - 127})")
      println(f"  common_exp         = $commonExp")
      println(f"  sum_man            = 0x${sumMan}%08X  bit24=${(sumMan>>24)&1} bit23=${(sumMan>>23)&1}")
      println(f"  norm_exp (out)     = $normExpO  (real = ${normExpO.toInt - 127})")
      println(f"  hw out             = 0x${hwOut}%08X  = ${fp32BitsToFloat(hwOut)}")
      println(f"  Expected           = 4.0  (0x40800000)")
    }
  }
}