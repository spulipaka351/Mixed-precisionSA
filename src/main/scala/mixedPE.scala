// FILE: src/main/scala/vectorPE/mixedPE.scala
package vectorPE

import chisel3._
import chisel3.util._

// Pure combinational MAC unit — no internal accumulator registers.
// MixedSA owns the ACC register externally and feeds psum_in each cycle.
// This gives single-cycle MAC latency with clean feedback.
class mixedPE extends Module {
  val io = IO(new Bundle {
    val in_a    = Input(UInt(16.W))
    val in_b    = Input(UInt(16.W))
    val mode    = Input(Bool())        // false = INT8, true = FP16
    val psum_in = Input(UInt(32.W))

    val out   = Output(UInt(32.W))
    val out_a = Output(UInt(16.W))    // registered pass-through (1-cycle per hop)
    val out_b = Output(UInt(16.W))    // registered pass-through (1-cycle per hop)
  })

  // ---------------------------------------------------------------
  // 1. FIELD EXTRACTION
  // ---------------------------------------------------------------
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

  // ---------------------------------------------------------------
  // 2. SHARED 12x12 SIGNED MULTIPLIER
  // ---------------------------------------------------------------
  val shared_in_a = Wire(SInt(12.W))
  val shared_in_b = Wire(SInt(12.W))

  when(io.mode) {
    shared_in_a := Cat(0.U(1.W), fp_man_a_11b).asSInt
    shared_in_b := Cat(0.U(1.W), fp_man_b_11b).asSInt
  } .otherwise {
    shared_in_a := int_a.pad(12)
    shared_in_b := int_b.pad(12)
  }

  val shared_mul_result = shared_in_a * shared_in_b  // SInt(24.W)

  // ---------------------------------------------------------------
  // 3. INT8 MAC output (combinational)
  // ---------------------------------------------------------------
  val out_int = (io.psum_in.asSInt + shared_mul_result(15, 0).asSInt.pad(32)).asUInt

  // ---------------------------------------------------------------
  // 4. FP16 MULTIPLY → normalized FP32 product
  // ---------------------------------------------------------------
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

  // ---------------------------------------------------------------
  // 5. FP32 ADD: psum_in + normalized product (combinational)
  // ---------------------------------------------------------------
  val psum_fp_sign = io.psum_in(31)
  val psum_fp_exp  = io.psum_in(30, 23)
  val psum_fp_man  = io.psum_in(22, 0)

  val psum_man_full = Mux(psum_fp_exp === 0.U,
                          Cat(0.U(1.W), psum_fp_man),
                          Cat(1.U(1.W), psum_fp_man))

  val mul_man_full = Mux(product_is_zero,
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
    norm_man := 0.U
    norm_exp := 0.U
  } .elsewhen(sum_man(24) === 1.U) {
    norm_man := sum_man(23, 1)
    norm_exp := common_exp + 1.U
  } .elsewhen(sum_man(23) === 1.U) {
    norm_man := sum_man(22, 0)
    norm_exp := common_exp
  } .otherwise {
    val leading_one = PriorityEncoder(Reverse(sum_man(22, 0)))
    val shift_amt   = 23.U - leading_one
    norm_man := (sum_man(22, 0) << shift_amt)(22, 0)
    when(shift_amt < common_exp) {
      norm_exp := common_exp - shift_amt
    } .otherwise {
      norm_exp := 0.U
    }
  }

  val out_fp = Cat(final_sign, norm_exp, norm_man)

  // ---------------------------------------------------------------
  // 6. OUTPUTS — purely combinational, no registers in PE
  // ---------------------------------------------------------------
  io.out   := Mux(io.mode, out_fp, out_int)
  io.out_a := RegNext(io.in_a, 0.U)
  io.out_b := RegNext(io.in_b, 0.U)
}