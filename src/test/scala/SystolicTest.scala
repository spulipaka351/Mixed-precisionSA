package vectorPE

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class SystolicTest extends AnyFlatSpec with ChiselScalatestTester {
  "Systolic Array" should "compute 2x2 MatMul" in {
    // Small 2x2 array for easier debugging
    test(new SA(rows = 2, cols = 2, width = 16)) { dut =>
      
      // We want to compute:
      // A = [1 2]   B = [1 0]
      //     [3 4]       [0 1]
      // Result should be same as A (Identity matrix mult)
      
      // WAVEFRONT INPUT SEQUENCE
      
      dut.io.res.poke(true.B)
      dut.clock.step()
      dut.io.res.poke(false.B)

      // Cycle 0: Feed A00, B00
      dut.io.in_a(0).poke(1.U); dut.io.in_a(1).poke(0.U)
      dut.io.in_b(0).poke(1.U); dut.io.in_b(1).poke(0.U)
      dut.clock.step()

      // Cycle 1: Feed A01, A10, B01, B10
      dut.io.in_a(0).poke(2.U); dut.io.in_a(1).poke(3.U)
      dut.io.in_b(0).poke(0.U); dut.io.in_b(1).poke(0.U)
      dut.clock.step()
      
      // Cycle 2: Feed A11, B11
      dut.io.in_a(0).poke(0.U); dut.io.in_a(1).poke(4.U)
      dut.io.in_b(0).poke(0.U); dut.io.in_b(1).poke(1.U)
      dut.clock.step()
      
      // Cycle 3: Drain pipeline
      dut.io.in_a(1).poke(0.U); dut.io.in_b(1).poke(0.U)
      dut.clock.step(5) // Wait for data to flow through

      // Check Result
      println(s"C(0,0): ${dut.io.out_sum(0)(0).peek().litValue} (Exp: 1)")
      println(s"C(0,1): ${dut.io.out_sum(0)(1).peek().litValue} (Exp: 2)")
      println(s"C(1,0): ${dut.io.out_sum(1)(0).peek().litValue} (Exp: 3)")
      println(s"C(1,1): ${dut.io.out_sum(1)(1).peek().litValue} (Exp: 4)")
      
      dut.io.out_sum(0)(0).expect(1.U)
      dut.io.out_sum(1)(1).expect(4.U)
    }
  }
}