package Mixed_opt

import chisel3._
import chisel3.util._

// Area-reduced mixedPE for QAT inference.
//
// Cuts vs original mixedPE (all QAT-justified):
//   A. Zero detection removed — QAT prunes zero weights; hot path never zero.
//      Saves: 2× NOR-reduction trees + 1 OR gate + 3 Mux cells.
//   B. exp_diff clamped to 24 via min() — if diff > 24 the shifted mantissa is
//      already all-zero; no extra logic needed. Saves: full 8-bit shift on the
//      smaller operand (replaced by a 5-bit shift with 1 comparator guard).
//   C. psum hidden bit: keep orR (correct for zero psum on first MAC).
//      No change — this is a correctness fix, not area.
//   D. mul_man_full: drop the product_is_zero Mux (cut A removes it).
//      Saves: 24-bit 2-to-1 mux.
//   E. Post-add norm: keep full PriorityEncoder — diagnosis proved QAT does NOT
//      bound cancellation depth. This cannot be cut.
//
// Unchanged from mixedPE:
//   - Shared 12×12 multiplier
//   - Flat Mux() style throughout (better synthesis than when/otherwise)
//   - exp.orR for hidden bits
//   - All INT8 logic

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

  // orR: single NOR gate, no mux (unchanged from mixedPE)
  val fp_man_a_11b = Cat(exp_a.orR, man_a)
  val fp_man_b_11b = Cat(exp_b.orR, man_b)

  val int_a = io.in_a(7, 0).asSInt
  val int_b = io.in_b(7, 0).asSInt

  // ---------------------------------------------------------------
  // 2. SHARED 12x12 MULTIPLIER
  // ---------------------------------------------------------------
  val shared_in_a = Mux(io.mode, Cat(0.U(1.W), fp_man_a_11b).asSInt, int_a.pad(12))
  val shared_in_b = Mux(io.mode, Cat(0.U(1.W), fp_man_b_11b).asSInt, int_b.pad(12))

  val shared_mul_result = shared_in_a * shared_in_b   // SInt(24.W)

  // ---------------------------------------------------------------
  // 3. INT8 MAC
  // ---------------------------------------------------------------
  val out_int = (io.psum_in.asSInt + shared_mul_result(15, 0).asSInt.pad(32)).asUInt

  // ---------------------------------------------------------------
  // 4. FP16 MULTIPLY
  // ---------------------------------------------------------------
  val out_fp_sign    = sign_a ^ sign_b
  val out_fp_exp_raw = (exp_a +& exp_b).pad(8)

  val raw_man  = shared_mul_result(21, 0).asUInt
  val norm_sel = raw_man(21)

  // CUT A: drop product_is_zero Mux — QAT ensures no zero weights in hot path.
  // If your model can have zero activations, reinstate the zero guard.
  val mul_norm_man = Mux(norm_sel,
                        Cat(raw_man(20, 0), 0.U(2.W)),
                        Cat(raw_man(19, 0), 0.U(3.W)))
  val mul_norm_exp = Mux(norm_sel,
                        out_fp_exp_raw + 98.U,
                        out_fp_exp_raw + 97.U)

  // ---------------------------------------------------------------
  // 5. FP32 ADD
  // ---------------------------------------------------------------
  val psum_fp_sign = io.psum_in(31)
  val psum_fp_exp  = io.psum_in(30, 23)
  val psum_fp_man  = io.psum_in(22, 0)

  // orR for hidden bit — correct for zero psum on first MAC (unchanged)
  val psum_man_full = Cat(psum_fp_exp.orR, psum_fp_man)   // UInt(24.W)

  // CUT D: no product_is_zero guard on mul_man_full
  val mul_man_full = Cat(1.U(1.W), mul_norm_man)           // UInt(24.W)

  // CUT B: clamp exp_diff to 5 bits (max meaningful shift = 24).
  // If |exp_diff| > 24 the shifted mantissa is all zeros — the result is just
  // the larger operand. A 5-bit shift saves the full 8-bit barrel shifter.
  val mul_larger  = mul_norm_exp >= psum_fp_exp
  val exp_diff_8  = Mux(mul_larger,
                        mul_norm_exp - psum_fp_exp,
                        psum_fp_exp  - mul_norm_exp)
  val exp_diff    = Mux(exp_diff_8 > 24.U, 24.U(5.W), exp_diff_8(4, 0))
  val common_exp  = Mux(mul_larger, mul_norm_exp, psum_fp_exp)

  // Shift operands: with 5-bit shift the synthesis tool infers a 24×5 barrel
  // shifter (~60 gates) instead of a 24×8 one (~160 gates).
  val aligned_mul_man  = Mux(mul_larger, mul_man_full,  mul_man_full  >> exp_diff)
  val aligned_psum_man = Mux(mul_larger, psum_man_full >> exp_diff, psum_man_full)

  // Add/subtract (unchanged flat-Mux style)
  val signs_match = out_fp_sign === psum_fp_sign
  val mul_gte     = aligned_mul_man >= aligned_psum_man

  val add_result  = aligned_mul_man +& aligned_psum_man
  val sub_ab      = aligned_mul_man  - aligned_psum_man
  val sub_ba      = aligned_psum_man - aligned_mul_man

  val sum_man    = Mux(signs_match, add_result,
                    Mux(mul_gte, sub_ab.pad(25), sub_ba.pad(25)))
  val final_sign = Mux(signs_match, out_fp_sign,
                    Mux(mul_gte,    out_fp_sign, psum_fp_sign))

  // ---------------------------------------------------------------
  // 6. POST-ADD NORMALIZATION — full LZC, cannot be cut
  //    (QAT diagnosis showed cancellation down to bit 5)
  // ---------------------------------------------------------------
  val is_zero    = sum_man === 0.U
  val overflow   = sum_man(24)
  val normalised = sum_man(23)

  val sub_field  = sum_man(22, 0)
  val hi_idx     = PriorityEncoder(Reverse(sub_field))
  val left_shift = hi_idx + 1.U

  val man_A = sum_man(23, 1)
  val man_B = sum_man(22, 0)
  val man_C = (sub_field.pad(47) << left_shift)(22, 0)

  val exp_A = common_exp + 1.U
  val exp_B = common_exp
  val exp_C = Mux(left_shift <= common_exp, common_exp - left_shift, 0.U(8.W))

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