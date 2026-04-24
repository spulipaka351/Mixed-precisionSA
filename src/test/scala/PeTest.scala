// package vectorPE
// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class PeTest extends AnyFlatSpec with ChiselScalatestTester {
//   "Pe (MAC Unit)" should "multiply and accumulate correctly" in {
//     test(new PE(16)) { dut =>
      
//       // --- Cycle 1: Calculate 2 * 3 ---
//       // Changed 'in_a' -> 'a' and 'in_b' -> 'b'
//       dut.io.a.poke(2.U)
//       dut.io.b.poke(3.U)
//       // Changed 'clear' -> 'res' (assuming 'res' is your reset/clear signal)
//       dut.io.res.poke(false.B)
      
//       dut.clock.step() 
      
//       dut.io.out.expect(6.U)
      
//       // --- Cycle 2: Calculate 4 * 5 ---
//       dut.io.a.poke(4.U)
//       dut.io.b.poke(5.U)
//       println(s"output after cylce 2 ${dut.io.out.peek()}")
//       dut.clock.step() 
      
//       dut.io.out.expect(26.U)

//       // --- Cycle 3: Test Clear Signal ---
//       dut.io.res.poke(true.B) 
      
//       dut.clock.step()

//       dut.io.out.expect(0.U)
//     }
//   }
// }