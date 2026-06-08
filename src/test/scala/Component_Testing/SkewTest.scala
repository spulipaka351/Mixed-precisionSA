// package common
// import common.SkewBuffers
// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class SkewTest extends AnyFlatSpec with ChiselScalatestTester {
//   behavior of "SkewBuffers"

//   it should "delay row i by i cycles" in {
//     val n = 4
//     val width = 32
    
//     test(new SkewBuffers(n, width)) { dut =>
//       // 1. Setup
//       dut.io.res.poke(false.B)
//       dut.io.en.poke(true.B)
//       // Clear inputs initially
//       dut.io.inp.foreach(_.poke(0.U))
//       dut.clock.step(1)

//       // 2. Inject Pulse
//       println("--- Injecting 0xFF ---")
//       dut.io.inp.foreach(_.poke(0xFF.U))

//       // === CRITICAL FIX: Check Row 0 IMMEDIATELY ===
//       // Since Row 0 has delay 0, it behaves like a wire.
//       // We must check it before stepping the clock.
//       dut.io.out(0).expect(0xFF.U, "Row 0 (Combinational) failed!")
//       println(s"  [OK] Row 0 passed (Combinational)")

//       // 3. Step Clock
//       // This latches the values into Row 1, Row 2, etc.
//       dut.clock.step(1)
      
//       // Remove input (pulse is over)
//       dut.io.inp.foreach(_.poke(0.U))

//       // 4. Check remaining rows (1 to n-1)
//       // Row i should output 0xFF at cycle i (relative to injection)
//       // We are currently at cycle 1.
      
//       for (cycle <- 1 until n) {
//         println(s"--- Cycle $cycle Checking ---")
        
//         // At this specific cycle, only Row 'cycle' should have the data
//         dut.io.out(cycle).expect(0xFF.U, s"Row $cycle failed at correct time")
        
//         // Optional: Ensure other rows are 0 (pulse passed or hasn't arrived)
//         for(r <- 0 until n) {
//           if (r != cycle) {
//             dut.io.out(r).expect(0.U, s"Row $r should be 0 at cycle $cycle")
//           }
//         }
        
//         println(s"  [OK] Row $cycle passed")
//         dut.clock.step(1)
//       }
//     }
//   }
// }