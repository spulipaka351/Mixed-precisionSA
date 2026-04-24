package vectorPE

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TopSATest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TopSA"

  it should "compute 4x4 Matrix Multiplication correctly" in {
    // Dimensions
    val rows = 4
    val cols = 4
    val width = 32
    val matrixDepth = 4 // computing A x B where dimensions are 4x4

    test(new TopSA(rows, cols, width)) { dut =>
      // --- 1. Initialization ---
      dut.io.res.poke(true.B)  // Reset accumulators and buffers
      dut.io.en.poke(false.B)
      dut.io.row.foreach(_.poke(0.U))
      dut.io.col.foreach(_.poke(0.U))
      dut.clock.step(1)

      dut.io.res.poke(false.B) // Release reset
      dut.io.en.poke(true.B)   // Enable shifting
      
      println("--- Starting Matrix Feed ---")

      // --- 2. Feed Data Loop ---
      // We are multiplying A (4x4) * B (4x4).
      // A is filled with 2.
      // B is filled with 3.
      
      for (k <- 0 until matrixDepth) {
        // Feed Column 'k' of Matrix A into the Left Rows
        for (r <- 0 until rows) {
           dut.io.row(r).poke(2.U) 
        }

        // Feed Row 'k' of Matrix B into the Top Cols
        for (c <- 0 until cols) {
           dut.io.col(c).poke(3.U)
        }

        dut.clock.step(1)
      }

      // --- 3. Drain Loop ---
      // Feed 0s to allow the data currently in SkewBuffers to propagate
      // and for the computation wave to finish.
      println("--- Draining Pipeline ---")
      dut.io.row.foreach(_.poke(0.U))
      dut.io.col.foreach(_.poke(0.U))
      
      // We need to wait enough cycles:
      // Max Skew delay = 3 cycles
      // SA Traversal = 4+4 cycles
      // Safety margin = 15 cycles
      dut.clock.step(15) 

      // --- 4. Verify Results ---
      // Expected: 2 * 3 * 4(depth) = 24
      println("--- Verifying Results ---")
      
      for (r <- 0 until rows) {
        for (c <- 0 until cols) {
          val result = dut.io.out_sum(r)(c).peek().litValue
          
          print(f"$result%3d ") // Print formatted matrix to console
          
          assert(result == 24, s"Error at ($r,$c): Expected 24, got $result")
        }
        println() // Newline for matrix print
      }
    }
  }
}