
package Pipe

import chisel3._
import chisel3.util._

class NF4Dequantizer extends Module {

def fp16Mul(a: UInt, b: UInt): UInt = {
    require(a.getWidth == 16 && b.getWidth == 16)

    val sign_a = a(15)
    val sign_b = b(15)
    val exp_a  = a(14, 10)
    val exp_b  = b(14, 10)
    val man_a  = a(9, 0)
    val man_b  = b(9, 0)

    // Result sign
    val res_sign = sign_a ^ sign_b

    // Zero detection (flush denormals to zero too)
    val a_zero = !exp_a.orR
    val b_zero = !exp_b.orR

    // Full 11-bit significands (hidden bit prepended)
    val sig_a = Cat(1.U(1.W), man_a)   // 11 bits
    val sig_b = Cat(1.U(1.W), man_b)   // 11 bits

    // 22-bit mantissa product
    val man_prod = sig_a * sig_b        // UInt(22.W)

    // Normalise: if bit 21 is set the product has the form 1x.xxx,
    // shift right 1 and add 1 to exponent; otherwise it is 01.xxx.
    val overflow_bit = man_prod(21)
    val norm_man = Mux(overflow_bit,
                       man_prod(20, 11),   // top 10 after leading 1 at 21
                       man_prod(19, 10))   // top 10 after leading 1 at 20

    // Biased exponent: exp_a + exp_b - 15 (remove one copy of bias)
    // Use 7-bit addition to catch potential over/underflow for safety
    val exp_sum = exp_a +& exp_b          // 6 bits max
    val res_exp_raw = Mux(overflow_bit,
                          exp_sum - 14.U, // -15 + 1 (normalisation shift)
                          exp_sum - 15.U) // -15

    val exp_underflow = res_exp_raw(res_exp_raw.getWidth - 1) || !res_exp_raw.orR
    val res_exp = Mux(exp_underflow, 0.U(5.W), res_exp_raw(4, 0))
    val res_man = Mux(exp_underflow, 0.U(10.W), norm_man)

    Mux(a_zero || b_zero,
        0.U(16.W),
        Cat(res_sign, res_exp, res_man))
  }


  val io = IO(new Bundle {
   
    val packed_nf4 = Input(UInt(16.W))   // 4 × 4-bit NF4 indices
    val scale      = Input(UInt(16.W))   // FP16 block scale
    val out        = Output(Vec(4, UInt(16.W)))  // 4 × FP16 dequantized
  })


  val nf4Lut = VecInit(Seq(
    "hBC00".U(16.W),  //  0: -1.0000000
    "hB991".U(16.W),  //  1: -0.6961928
    "hB833".U(16.W),  //  2: -0.5250731
    "hB651".U(16.W),  //  3: -0.3949175
    "hB48D".U(16.W),  //  4: -0.2844414
    "hB1E9".U(16.W),  //  5: -0.1847734
    "hADD3".U(16.W),  //  6: -0.0910500
    "h0000".U(16.W),  //  7:  0.0000000
    "h2D17".U(16.W),  //  8: +0.0795803
    "h3126".U(16.W),  //  9: +0.1609302
    "h33E0".U(16.W),  // 10: +0.2461123
    "h3568".U(16.W),  // 11: +0.3379152
    "h370D".U(16.W),  // 12: +0.4407098
    "h3880".U(16.W),  // 13: +0.5626170
    "h39C8".U(16.W),  // 14: +0.7229568
    "h3C00".U(16.W)   // 15: +1.0000000
  ))
  
  val idx = Wire(Vec(4, UInt(4.W)))
  idx(0) := io.packed_nf4(3,  0)
  idx(1) := io.packed_nf4(7,  4)
  idx(2) := io.packed_nf4(11, 8)
  idx(3) := io.packed_nf4(15, 12)

  for (k <- 0 until 4) {
    val lut_val = nf4Lut(idx(k))
    val result  = fp16Mul(lut_val, io.scale)
    io.out(k) := result 
      
  }
}