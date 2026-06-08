package Mixed

import chisel3._
import chisel3.util._

// Optimized combinational MAC unit.
// Key changes vs original:
//   1. hidden_a/b: Mux(exp===0,0,1) -> exp.orR  (OR-reduction, no mux)
//   2. Multiplier inputs: when/otherwise -> flat Mux per wire
//   3. product_is_zero: uses !x.orR && !y.orR  (NOR+AND, no equality comparators)
//   4. FP multiply normalization: 3-way when -> 2 nested Muxes
//   5. Exponent alignment: 3-branch x 3-signal -> 1 comparator + 3 Muxes
//   6. Sign/subtract: nested when -> pre-compute both directions, 2 flat Muxes
//   7. Post-add normalization: 4-way when-chain -> flat priority Muxes.
//      PriorityEncoder confined to 23-bit sub-field; shift formula corrected
//      so leading 1 lands at bit 23 of padded result and is dropped by (22,0).
class mixedPE extends Module {
  val io = IO(new Bundle {
    val in_a    = Input(UInt(16.W))
    val in_b    = Input(UInt(16.W))
    val mode    = Input(Bool())       // false = INT8, true = FP16
    val psum_in = Input(UInt(32.W))

    val out   = Output(UInt(32.W))
    val out_a = Output(UInt(16.W))   // registered pass-through (1-cycle per hop)
    val out_b = Output(UInt(16.W))   // registered pass-through (1-cycle per hop)
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

  // OPT: exp.orR replaces Mux(exp===0, 0.U(1.W), 1.U(1.W)) -- no mux cell.
  val hidden_a     = exp_a.orR
  val hidden_b     = exp_b.orR
  val fp_man_a_11b = Cat(hidden_a, man_a)   // 11-bit significand
  val fp_man_b_11b = Cat(hidden_b, man_b)

  val int_a = io.in_a(7, 0).asSInt
  val int_b = io.in_b(7, 0).asSInt

  // ---------------------------------------------------------------
  // 2. SHARED 12x12 SIGNED MULTIPLIER
  //    OPT: flat Mux per input wire instead of when/otherwise block.
  // ---------------------------------------------------------------
  val shared_in_a = Mux(io.mode,
                        Cat(0.U(1.W), fp_man_a_11b).asSInt,
                        int_a.pad(12))
  val shared_in_b = Mux(io.mode,
                        Cat(0.U(1.W), fp_man_b_11b).asSInt,
                        int_b.pad(12))

  val shared_mul_result = shared_in_a * shared_in_b   // SInt(24.W)

  // ---------------------------------------------------------------
  // 3. INT8 MAC (combinational)
  // ---------------------------------------------------------------
  val out_int = (io.psum_in.asSInt + shared_mul_result(15, 0).asSInt.pad(32)).asUInt

  // ---------------------------------------------------------------
  // 4. FP16 MULTIPLY -> normalized FP32 product
  // ---------------------------------------------------------------
  val out_fp_sign    = sign_a ^ sign_b
  val out_fp_exp_raw = (exp_a +& exp_b).pad(8)   // UInt(8.W), biased sum

  // OPT: zero detection via !x.orR (NOR reduction) instead of === 0.U.
  // A FP16 value is zero iff BOTH exp and mantissa fields are zero.
  val a_is_zero       = !exp_a.orR && !man_a.orR
  val b_is_zero       = !exp_b.orR && !man_b.orR
  val product_is_zero = a_is_zero || b_is_zero

  // 22-bit raw mantissa product from the shared multiplier
  val raw_man = shared_mul_result(21, 0).asUInt

  // OPT: 3-way when collapsed to 2 nested Muxes.
  //   raw_man(21)=1 -> 1.xx case, exp_bias=+98
  //   raw_man(21)=0 -> 0.1x case, exp_bias=+97
  //   product_is_zero -> force 0
  val norm_sel     = raw_man(21)
  val mul_norm_man = Mux(product_is_zero, 0.U(23.W),
                      Mux(norm_sel,
                          Cat(raw_man(20, 0), 0.U(2.W)),
                          Cat(raw_man(19, 0), 0.U(3.W))))
  val mul_norm_exp = Mux(product_is_zero, 0.U(8.W),
                      Mux(norm_sel,
                          out_fp_exp_raw + 98.U,
                          out_fp_exp_raw + 97.U))

  // ---------------------------------------------------------------
  // 5. FP32 ADD: psum_in + normalized product
  // ---------------------------------------------------------------
  val psum_fp_sign = io.psum_in(31)
  val psum_fp_exp  = io.psum_in(30, 23)
  val psum_fp_man  = io.psum_in(22, 0)

  // Prepend hidden bit via orR
  val psum_man_full = Cat(psum_fp_exp.orR, psum_fp_man)   // UInt(24.W)
  val mul_man_full  = Mux(product_is_zero, 0.U(24.W),
                          Cat(1.U(1.W), mul_norm_man))     // UInt(24.W)

  // OPT: exponent alignment -- 3-branch x 3-signal when -> 1 comparator + 3 Muxes.
  val mul_larger   = mul_norm_exp >= psum_fp_exp
  val exp_diff     = Mux(mul_larger,
                         mul_norm_exp - psum_fp_exp,
                         psum_fp_exp  - mul_norm_exp)
  val common_exp   = Mux(mul_larger, mul_norm_exp, psum_fp_exp)

  val aligned_mul_man  = Mux(mul_larger, mul_man_full,  mul_man_full  >> exp_diff)
  val aligned_psum_man = Mux(mul_larger, psum_man_full >> exp_diff, psum_man_full)

  // OPT: sign/subtract -- pre-compute all arithmetic results, select with 2 flat Muxes.
  val signs_match = out_fp_sign === psum_fp_sign
  val mul_gte     = aligned_mul_man >= aligned_psum_man

  val add_result  = aligned_mul_man +& aligned_psum_man   // UInt(25.W)
  val sub_ab      = aligned_mul_man  - aligned_psum_man   // mul >= psum case
  val sub_ba      = aligned_psum_man - aligned_mul_man    // psum > mul case

  val sum_man    = Mux(signs_match, add_result,
                    Mux(mul_gte, sub_ab.pad(25), sub_ba.pad(25)))
  val final_sign = Mux(signs_match, out_fp_sign,
                    Mux(mul_gte,    out_fp_sign, psum_fp_sign))

  // ---------------------------------------------------------------
  // 6. POST-ADD NORMALIZATION
  //    OPT: 4-way priority when-chain -> flat priority Muxes.
  //
  //    Cases based on where the leading 1 sits in sum_man[24:0]:
  //      Case A (overflow):   bit24=1  -> right-shift 1, exp+1
  //      Case B (normalised): bit23=1  -> no shift,     exp+0
  //      Case C (subnormal):  leading 1 in bits[22:0] -> left-shift N, exp-N
  //      Case Z (zero):       all zero -> output zero
  //
  //    Case C shift formula: left_shift = hi_idx + 1
  //      This moves the leading 1 (at bit hi_idx of sub_field) to bit 23
  //      of the padded result. Extracting bits (22,0) then drops the
  //      implicit leading 1, leaving the 23-bit mantissa field.
  //      max left_shift = 23 (hi_idx=0); pad to 47 bits covers this safely.
  // ---------------------------------------------------------------
  val is_zero    = sum_man === 0.U
  val overflow   = sum_man(24)   // Case A
  val normalised = sum_man(23)   // Case B (only when !overflow)

  val sub_field  = sum_man(22, 0)
  val hi_idx     = PriorityEncoder(Reverse(sub_field))   // = (22 - actual_hi_idx); range 0..22
  val left_shift = hi_idx + 1.U  // shift leading 1 to bit 23; see note below

  // Mantissa per case (all static bit extractions):
  val man_A = sum_man(23, 1)                             // overflow: >> 1
  val man_B = sum_man(22, 0)                             // normalised: as-is
  val man_C = (sub_field.pad(47) << left_shift)(22, 0)  // subnormal: shift up, drop leading 1

  // Exponent per case:
  val exp_A = common_exp + 1.U
  val exp_B = common_exp
  val exp_C = Mux(left_shift <= common_exp, common_exp - left_shift, 0.U(8.W))

  // Flat priority Mux select:
  val norm_man = Mux(is_zero,    0.U(23.W),
                 Mux(overflow,   man_A,
                 Mux(normalised, man_B,
                                 man_C)))

  val norm_exp = Mux(is_zero,    0.U(8.W),
                 Mux(overflow,   exp_A,
                 Mux(normalised, exp_B,
                                 exp_C)))

  val out_fp = Cat(final_sign, norm_exp, norm_man)

  // ---------------------------------------------------------------
  // 7. OUTPUTS
  // ---------------------------------------------------------------
  io.out   := Mux(io.mode, out_fp, out_int)
  io.out_a := RegNext(io.in_a, 0.U)
  io.out_b := RegNext(io.in_b, 0.U)
}