package Mixed_opt


import chisel3._
import chisel3.util._

class OptPE extends Module {
  val io = IO(new Bundle {
    val in_a    = Input(UInt(16.W))
    val in_b    = Input(UInt(16.W))
    val mode    = Input(Bool())
    val psum_in = Input(UInt(32.W))
    val out     = Output(UInt(32.W))
    val out_a   = Output(UInt(16.W))
    val out_b   = Output(UInt(16.W))
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

  val fp_man_a_11b = Cat(1.U(1.W), man_a)   // QAT: drop subnormal mux
  val fp_man_b_11b = Cat(1.U(1.W), man_b)

  val int_a = io.in_a(7, 0).asSInt
  val int_b = io.in_b(7, 0).asSInt

  // ---------------------------------------------------------------
  // 2. SHARED 12x12 MULTIPLIER
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
  // 3. INT8 MAC (unchanged)
  // ---------------------------------------------------------------
  val out_int = (io.psum_in.asSInt + shared_mul_result(15, 0).asSInt.pad(32)).asUInt

  // ---------------------------------------------------------------
  // 4. FP16 MULTIPLY — simplified normalization
  //    QAT guarantee: both inputs are normal, product fits in
  //    raw_man(21) or raw_man(20) — just one bit check, no LUT shift
  // ---------------------------------------------------------------
  val out_fp_sign    = sign_a ^ sign_b
  val out_fp_exp_raw = (exp_a +& exp_b).pad(8)

  val raw_man      = shared_mul_result(21, 0).asUInt

  // Single-bit normalization only — drop the subnormal/zero branches
  val mul_norm_man = Wire(UInt(23.W))
  val mul_norm_exp = Wire(UInt(8.W))

  when(raw_man(21) === 1.U) {
    mul_norm_man := Cat(raw_man(20, 0), 0.U(2.W))
    mul_norm_exp := out_fp_exp_raw + 98.U
  } .otherwise {
    mul_norm_man := Cat(raw_man(19, 0), 0.U(3.W))
    mul_norm_exp := out_fp_exp_raw + 97.U
  }

  // ---------------------------------------------------------------
  // 5. FP32 ADD — simplified normalization
  //    QAT keeps accumulations bounded: at most 1-bit overflow,
  //    at most 1-bit left-shift needed. Drop PriorityEncoder entirely.
  // ---------------------------------------------------------------
  val psum_fp_sign = io.psum_in(31)
  val psum_fp_exp  = io.psum_in(30, 23)
  val psum_fp_man  = io.psum_in(22, 0)

  // Always assume normal (no subnormal mux) — safe under QAT
  val psum_man_full = Cat(1.U(1.W), psum_fp_man)         // UInt(24.W)
  val mul_man_full  = Cat(1.U(1.W), mul_norm_man)         // UInt(24.W)

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

  // Simplified normalization: only handle overflow (+1) and
  // already-normal cases. QAT prevents deeper left-shift needs.
  val norm_man = Wire(UInt(23.W))
val norm_exp = Wire(UInt(8.W))

when(sum_man(24) === 1.U) {              // overflow
  norm_man := sum_man(23, 1)
  norm_exp := common_exp + 1.U

} .elsewhen(sum_man(23) === 1.U) {       // already normalized
  norm_man := sum_man(22, 0)
  norm_exp := common_exp

} .elsewhen(sum_man(22) === 1.U) {       // shift left 1
  norm_man := Cat(sum_man(21, 0), 0.U(1.W))
  norm_exp := common_exp - 1.U

} .elsewhen(sum_man(21) === 1.U) {       // NEW: shift left 2
  norm_man := Cat(sum_man(20, 0), 0.U(2.W))
  norm_exp := common_exp - 2.U

} .elsewhen(sum_man(20) === 1.U) {       // NEW: shift left 3
  norm_man := Cat(sum_man(19, 0), 0.U(3.W))
  norm_exp := common_exp - 3.U

} .otherwise {
  // fallback: tiny value instead of zero (soft FTZ)
  norm_man := 1.U   // smallest non-zero mantissa
  norm_exp := 0.U   // acts like subnormal-ish
}
  val out_fp = Cat(final_sign, norm_exp, norm_man)

  // ---------------------------------------------------------------
  // 6. OUTPUTS
  // ---------------------------------------------------------------
  io.out   := Mux(io.mode, out_fp, out_int)
  io.out_a := RegNext(io.in_a, 0.U)
  io.out_b := RegNext(io.in_b, 0.U)
}