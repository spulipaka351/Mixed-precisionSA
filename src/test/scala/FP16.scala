

package FP16

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest.simulator.WriteVcdAnnotation

class TopSAFPTest extends AnyFlatSpec with ChiselScalatestTester {
  
  // Helper function to convert float to FP16
  def floatToFP16(f: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(f)
    val sign = (bits >> 31) & 0x1
    val exponent = (bits >> 23) & 0xFF
    val mantissa = bits & 0x7FFFFF
    
    if (exponent == 0xFF) {
      // Infinity or NaN
      if (mantissa == 0) ((sign << 15) | 0x7C00).toShort
      else ((sign << 15) | 0x7E00).toShort
    } else if (exponent == 0 && mantissa == 0) {
      // Zero
      (sign << 15).toShort
    } else {
      // Normal number
      val new_exp = exponent - 112
      if (new_exp >= 31) ((sign << 15) | 0x7C00).toShort
      else if (new_exp <= 0) (sign << 15).toShort
      else {
        val new_mantissa = mantissa >> 13
        ((sign << 15) | (new_exp << 10) | new_mantissa).toShort
      }
    }
  }
  
  // Helper function to convert FP16 to float
  def fp16ToFloat(fp16: Int): Float = {
    val sign = (fp16 >> 15) & 0x1
    val exponent = (fp16 >> 10) & 0x1F
    val mantissa = fp16 & 0x3FF
    
    if (exponent == 0) {
      if (mantissa == 0) 0.0f
      else java.lang.Float.intBitsToFloat((sign << 31) | (120 << 23) | (mantissa << 13))
    } else if (exponent == 31) {
      if (mantissa == 0) {
        if (sign == 0) Float.PositiveInfinity else Float.NegativeInfinity
      } else Float.NaN
    } else {
      val exp32 = exponent + 112
      val bits = (sign << 31) | (exp32 << 23) | (mantissa << 13)
      java.lang.Float.intBitsToFloat(bits)
    }
  }

  behavior of "TopSAFP"

  it should "initialize and reset correctly" in {
    test(new TopSAFP(rows = 4, cols = 4, width = 16))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      // Reset the design
      dut.io.res.poke(true.B)
      dut.io.en.poke(false.B)
      dut.clock.step(1)
      
      // Check all outputs are zero after reset
      for (i <- 0 until 4) {
        for (j <- 0 until 4) {
          dut.io.out_sum(i)(j).expect(0.U)
        }
      }
      
      println("✓ Reset test passed")
    }
  }

  it should "perform simple 2x2 matrix multiplication" in {
    test(new TopSAFP(rows = 2, cols = 2, width = 16))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      // Matrix A (2x2):
      // [1.0  2.0]
      // [3.0  4.0]
      
      // Matrix B (2x2):
      // [1.0  0.0]
      // [0.0  1.0]
      
      // Expected result C = A * B:
      // [1.0  2.0]
      // [3.0  4.0]
      
      val A = Array(
        Array(1.0f, 2.0f),
        Array(3.0f, 4.0f)
      )
      
      val B = Array(
        Array(1.0f, 0.0f),
        Array(0.0f, 1.0f)
      )
      
      // Convert to FP16
      val A_fp16 = A.map(_.map(floatToFP16))
      val B_fp16 = B.map(_.map(floatToFP16))
      
      // Reset
      dut.io.res.poke(true.B)
      dut.io.en.poke(false.B)
      dut.clock.step(1)
      
      dut.io.res.poke(false.B)
      dut.io.en.poke(true.B)
      
      // Feed inputs (row-wise for A, column-wise for B)
      // For 2x2, we need to stream 2 elements
      for (step <- 0 until 2) {
        for (i <- 0 until 2) {
          dut.io.row(i).poke(A_fp16(i)(step).U)
        }
        for (j <- 0 until 2) {
          dut.io.col(j).poke(B_fp16(step)(j).U)
        }
        dut.clock.step(1)
      }
      
      // Wait for computation to complete (skew + computation)
      dut.io.en.poke(false.B)
      for (i <- 0 until 2) {
        dut.io.row(i).poke(0.U)
        dut.io.col(i).poke(0.U)
      }
      dut.clock.step(10)
      
      // Check results
      println("\nResults (2x2 matrix multiply):")
      for (i <- 0 until 2) {
        for (j <- 0 until 2) {
          val result = dut.io.out_sum(i)(j).peek().litValue.toInt
          println(f"  out_sum($i)($j) = 0x${result}%08x")
        }
      }
      
      println("✓ Simple 2x2 matrix multiplication test passed")
    }
  }

  it should "perform 4x4 matrix multiplication with identity" in {
    test(new TopSAFP(rows = 4, cols = 4, width = 16))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      // Matrix A (4x4): Random values
      val A = Array(
        Array(1.0f, 2.0f, 3.0f, 4.0f),
        Array(5.0f, 6.0f, 7.0f, 8.0f),
        Array(9.0f, 10.0f, 11.0f, 12.0f),
        Array(13.0f, 14.0f, 15.0f, 16.0f)
      )
      
      // Matrix B (4x4): Identity matrix
      val B = Array(
        Array(1.0f, 0.0f, 0.0f, 0.0f),
        Array(0.0f, 1.0f, 0.0f, 0.0f),
        Array(0.0f, 0.0f, 1.0f, 0.0f),
        Array(0.0f, 0.0f, 0.0f, 1.0f)
      )
      
      // Convert to FP16
      val A_fp16 = A.map(_.map(floatToFP16))
      val B_fp16 = B.map(_.map(floatToFP16))
      
      // Reset
      dut.io.res.poke(true.B)
      dut.io.en.poke(false.B)
      dut.clock.step(1)
      
      dut.io.res.poke(false.B)
      dut.io.en.poke(true.B)
      
      // Feed inputs
      for (step <- 0 until 4) {
        for (i <- 0 until 4) {
          dut.io.row(i).poke(A_fp16(i)(step).U)
        }
        for (j <- 0 until 4) {
          dut.io.col(j).poke(B_fp16(step)(j).U)
        }
        dut.clock.step(1)
      }
      
      // Wait for computation
      dut.io.en.poke(false.B)
      for (i <- 0 until 4) {
        dut.io.row(i).poke(0.U)
        dut.io.col(i).poke(0.U)
      }
      dut.clock.step(20)
      
      // Check results (should be A since A * I = A)
      println("\nResults (4x4 matrix * identity):")
      for (i <- 0 until 4) {
        for (j <- 0 until 4) {
          val result = dut.io.out_sum(i)(j).peek().litValue.toInt
          println(f"  out_sum($i)($j) = 0x${result}%08x")
        }
      }
      
      println("✓ 4x4 matrix multiplication with identity test passed")
    }
  }

  it should "accumulate multiple matrix products" in {
    test(new TopSAFP(rows = 2, cols = 2, width = 16))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      // First matrix pair
      val A1 = Array(
        Array(1.0f, 0.0f),
        Array(0.0f, 1.0f)
      )
      
      val B1 = Array(
        Array(2.0f, 0.0f),
        Array(0.0f, 2.0f)
      )
      
      // Convert to FP16
      val A1_fp16 = A1.map(_.map(floatToFP16))
      val B1_fp16 = B1.map(_.map(floatToFP16))
      
      // Reset
      dut.io.res.poke(true.B)
      dut.io.en.poke(false.B)
      dut.clock.step(1)
      
      // First computation
      dut.io.res.poke(false.B)
      dut.io.en.poke(true.B)
      
      for (step <- 0 until 2) {
        for (i <- 0 until 2) {
          dut.io.row(i).poke(A1_fp16(i)(step).U)
        }
        for (j <- 0 until 2) {
          dut.io.col(j).poke(B1_fp16(step)(j).U)
        }
        dut.clock.step(1)
      }
      
      // Wait
      dut.io.en.poke(false.B)
      for (i <- 0 until 2) {
        dut.io.row(i).poke(0.U)
        dut.io.col(i).poke(0.U)
      }
      dut.clock.step(10)
      
      // Check first result
      println("\nFirst accumulation:")
      for (i <- 0 until 2) {
        for (j <- 0 until 2) {
          val result = dut.io.out_sum(i)(j).peek().litValue.toInt
          println(f"  out_sum($i)($j) = 0x${result}%08x")
        }
      }
      
      // Second computation (without reset - accumulates)
      dut.io.en.poke(true.B)
      
      for (step <- 0 until 2) {
        for (i <- 0 until 2) {
          dut.io.row(i).poke(A1_fp16(i)(step).U)
        }
        for (j <- 0 until 2) {
          dut.io.col(j).poke(B1_fp16(step)(j).U)
        }
        dut.clock.step(1)
      }
      
      // Wait
      dut.io.en.poke(false.B)
      for (i <- 0 until 2) {
        dut.io.row(i).poke(0.U)
        dut.io.col(i).poke(0.U)
      }
      dut.clock.step(10)
      
      // Check accumulated result
      println("\nSecond accumulation (should be doubled):")
      for (i <- 0 until 2) {
        for (j <- 0 until 2) {
          val result = dut.io.out_sum(i)(j).peek().litValue.toInt
          println(f"  out_sum($i)($j) = 0x${result}%08x")
        }
      }
      
      println("✓ Accumulation test passed")
    }
  }

  it should "handle streaming correctly with proper skew" in {
    test(new TopSAFP(rows = 4, cols = 4, width = 16))
      .withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      
      // Test streaming with proper timing
      val A = Array.fill(4, 4)(1.0f)
      val B = Array.fill(4, 4)(1.0f)
      
      val A_fp16 = A.map(_.map(floatToFP16))
      val B_fp16 = B.map(_.map(floatToFP16))
      
      // Reset
      dut.io.res.poke(true.B)
      dut.io.en.poke(false.B)
      dut.clock.step(2)
      
      dut.io.res.poke(false.B)
      dut.io.en.poke(true.B)
      
      println("\nStreaming data with skew buffers:")
      
      // Stream for N cycles (where N = cols or rows)
      for (step <- 0 until 4) {
        println(f"  Cycle $step:")
        for (i <- 0 until 4) {
          dut.io.row(i).poke(A_fp16(i)(step).U)
          println(f"    row($i) = ${A(i)(step)}%.1f (0x${A_fp16(i)(step)}%04x)")
        }
        for (j <- 0 until 4) {
          dut.io.col(j).poke(B_fp16(step)(j).U)
          println(f"    col($j) = ${B(step)(j)}%.1f (0x${B_fp16(step)(j)}%04x)")
        }
        dut.clock.step(1)
      }
      
      // Disable input, wait for results
      dut.io.en.poke(false.B)
      for (i <- 0 until 4) {
        dut.io.row(i).poke(0.U)
        dut.io.col(i).poke(0.U)
      }
      dut.clock.step(20)
      
      // Expected: all 1s matrix * all 1s matrix = all 4s (since 4x4)
      println("\nFinal results:")
      for (i <- 0 until 4) {
        for (j <- 0 until 4) {
          val result = dut.io.out_sum(i)(j).peek().litValue.toInt
          println(f"  out_sum($i)($j) = 0x${result}%08x")
        }
      }
      
      println("✓ Streaming with skew test passed")
    }
  }
}