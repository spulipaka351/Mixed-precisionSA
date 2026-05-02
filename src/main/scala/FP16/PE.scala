package FP16
import chisel3._
import chisel3.util._

/**
 * FP16-Only Processing Element (PE) for Systolic Array Benchmarking
 * 
 * This PE implements a single processing element for an output-stationary
 * systolic array that operates exclusively in FP16 precision for both
 * inputs and accumulation.
 * 
 * Architecture:
 * - Shared 16x16 FP16 multiplier
 * - FP32 accumulator (for high-precision partial sum storage)
 * - Input: operand A (FP16), operand B (FP16)
 * - Output: accumulated result (FP32)
 * - Skew buffers for timing alignment in systolic array
 */

class PE_FP16(val width: Int) extends Module {
  val io = IO(new Bundle {
    val in_a = Input(UInt(16.W))      // FP16 weight
    val in_b = Input(UInt(16.W))      // FP16 activation
    val out_a = Output(UInt(16.W))    // Pass-through weight
    val out_b = Output(UInt(16.W))    // Pass-through activation
    val out_sum = Output(UInt(32.W))  // FP32 result
    val res = Input(Bool())           // Reset signal
  })
 
  // FP16 to FP32 converter
  def fp16_to_fp32(fp16_val: UInt): UInt = {
    val sign = fp16_val(15)
    val exp = fp16_val(14, 10)
    val frac = fp16_val(9, 0)
    
    val exp_extended = exp +& 112.U(8.W)
    val frac_extended = frac << 13
    
    Cat(sign, exp_extended(7, 0), frac_extended(22, 0))
  }
 
  // FP16 multiplication (convert to FP32, multiply as float operations)
  def fp16_mult(a: UInt, b: UInt): UInt = {
    val a_fp32 = fp16_to_fp32(a)
    val b_fp32 = fp16_to_fp32(b)
    
    // Extract components (FP32 format)
    val a_sign = a_fp32(31)
    val a_exp = a_fp32(30, 23)
    val a_mant = Cat(1.U(1.W), a_fp32(22, 0))  // 24 bits with implicit 1
    
    val b_sign = b_fp32(31)
    val b_exp = b_fp32(30, 23)
    val b_mant = Cat(1.U(1.W), b_fp32(22, 0))  // 24 bits with implicit 1
    
    // Multiply mantissas (24 x 24 = 48 bits)
    val mant_prod = a_mant * b_mant  // 48 bits
    
    // Normalize: if mant_prod[47] is set, shift right by 1
    val normalized_mant = Mux(mant_prod(47), mant_prod(47, 24), mant_prod(46, 23))
    val exp_adjust = Mux(mant_prod(47), 1.U, 0.U)
    
    // Add exponents and subtract bias (127 for FP32)
    val exp_sum = a_exp +& b_exp +& exp_adjust -& 127.U(9.W)
    
    // Result sign (XOR of input signs)
    val result_sign = a_sign ^ b_sign
    
    // Handle special cases
    val result = WireDefault(0.U(32.W))
    when(a_fp32 === 0.U || b_fp32 === 0.U) {
      result := 0.U
    }.elsewhen(exp_sum >= 255.U) {
      // Overflow to infinity
      result := Cat(result_sign, 0xFF.U(8.W), 0.U(23.W))
    }.elsewhen(exp_sum <= 0.U) {
      // Underflow to zero
      result := 0.U
    }.otherwise {
      result := Cat(result_sign, exp_sum(7, 0), normalized_mant(22, 0))
    }
    
    result
  }
 
  // FP32 addition (simplified - assumes same sign for accumulation)
  def fp32_add(a: UInt, b: UInt): UInt = {
    // Extract components
    val a_sign = a(31)
    val a_exp = a(30, 23)
    val a_mant = Cat(1.U(1.W), a(22, 0))
    
    val b_sign = b(31)
    val b_exp = b(30, 23)
    val b_mant = Cat(1.U(1.W), b(22, 0))
    
    // Simple case: if one is zero, return the other
    val result = WireDefault(0.U(32.W))
    when(a === 0.U) {
      result := b
    }.elsewhen(b === 0.U) {
      result := a
    }.elsewhen(a_sign === b_sign) {
      // Same sign: align and add
      val exp_diff = a_exp -& b_exp
      
      when(exp_diff > 0.U) {
        // a has larger exponent
        val aligned_b_mant = b_mant >> exp_diff
        val mant_sum = a_mant +& aligned_b_mant
        
        val normalized = Mux(mant_sum(24), mant_sum >> 1.U, mant_sum)
        val final_exp = Mux(mant_sum(24), a_exp +& 1.U, a_exp)
        
        result := Cat(a_sign, final_exp(7, 0), normalized(22, 0))
      }.elsewhen(exp_diff < 0.U) {
        // b has larger exponent
        val aligned_a_mant = a_mant >> (-exp_diff)
        val mant_sum = aligned_a_mant +& b_mant
        
        val normalized = Mux(mant_sum(24), mant_sum >> 1.U, mant_sum)
        val final_exp = Mux(mant_sum(24), b_exp +& 1.U, b_exp)
        
        result := Cat(b_sign, final_exp(7, 0), normalized(22, 0))
      }.otherwise {
        // Same exponent
        val mant_sum = a_mant +& b_mant
        val normalized = Mux(mant_sum(24), mant_sum >> 1.U, mant_sum)
        val final_exp = Mux(mant_sum(24), a_exp +& 1.U, a_exp)
        
        result := Cat(a_sign, final_exp(7, 0), normalized(22, 0))
      }
    }.otherwise {
      // Different signs: this is subtraction (simplified - just add for now)
      result := a
    }
    
    result
  }
 
  val partial_sum = RegInit(0.U(32.W))
 
  // Compute multiply-accumulate
  val mult_result = WireDefault(0.U(32.W))
  mult_result := fp16_mult(io.in_a, io.in_b)
 
  // Update accumulator
  when(io.res) {
    partial_sum := 0.U
  }.otherwise {
    partial_sum := fp32_add(partial_sum, mult_result)
  }
 
  // Outputs
  io.out_sum := partial_sum
  io.out_a := io.in_a
  io.out_b := io.in_b
}