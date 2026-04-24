// package vectorPE

// import chisel3._
// import chiseltest._
// import chisel3.util.log2Ceil
// import org.scalatest.flatspec.AnyFlatSpec
// class VecTest extends AnyFlatSpec with ChiselScalatestTester {
//  "VectorPE" should "compute pipelined multi-cycle dot product correctly with valid signal" in {

//     val len = 4
//     val latency = 1 + log2Ceil(len) // 1 cycle for PE + log2(len) cycles for reduction tree

//     test(new VectorPE(len)) { dut =>

//       // Example input vectors
//       val A = Seq(1, 2, 3, 4)
//       val B = Seq(1, 1, 1, 1)
//       val expectedDot = A.zip(B).map{ case (a,b) => a*b }.sum

//       // -----------------------------
//       // Reset
//       // -----------------------------
//       dut.io.res.poke(true.B)
//       dut.clock.step(1)
//       dut.io.res.poke(false.B)

//       // -----------------------------
//       // Apply inputs
//       // -----------------------------
//       for(i <- 0 until len){
//         dut.io.a(i).poke(A(i).U)
//         dut.io.b(i).poke(B(i).U)
//       }

//       // -----------------------------
//       // Wait for pipeline fill
//       // -----------------------------
//       for(_ <- 0 until latency){
//         dut.io.outValid.expect(false.B) // output not ready yet
//         dut.clock.step(1)
//       }

//       // -----------------------------
//       // First valid output
//       // -----------------------------
//       dut.io.outValid.expect(true.B)
//       dut.io.out.expect(expectedDot.U)

//       // -----------------------------
//       // Second cycle: accumulation continues
//       // -----------------------------
//       dut.clock.step(1)
//       dut.io.outValid.expect(true.B)
//       dut.io.out.expect((2*expectedDot).U)

//       // -----------------------------
//       // Third cycle: further accumulation
//       // -----------------------------
//       dut.clock.step(1)
//       dut.io.outValid.expect(true.B)
//       dut.io.out.expect((3*expectedDot).U)

//       // -----------------------------
//       // Reset mid-stream and check
//       // -----------------------------
//       dut.io.res.poke(true.B)
//       dut.clock.step(1)
//       dut.io.res.poke(false.B)

//       dut.clock.step(latency)
//       dut.io.outValid.expect(true.B)
//       dut.io.out.expect(0.U) // accumulator reset
//     }
//   }
// }