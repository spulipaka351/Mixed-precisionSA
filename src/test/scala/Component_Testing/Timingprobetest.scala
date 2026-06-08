// package Mixed

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class TimingProbeTest extends AnyFlatSpec with ChiselScalatestTester {
//   behavior of "TopMixedSA offdiagonal timing"

//   it should "trace PE(0,1) with padded all-ones K=4" in {
//     test(new TopMixedSA(4, 4)) { dut =>
//       val K = 4
//       val pad = (4 - 1) + (4 - 1)   // 6
//       val total = K + pad           // 10

//       dut.io.res.poke(true.B)
//       dut.io.en.poke(false.B)
//       dut.io.mode.poke(false.B)
//       dut.io.row.foreach(_.poke(0.U))
//       dut.io.col.foreach(_.poke(0.U))
//       dut.clock.step(2)
//       dut.io.res.poke(false.B)

//       dut.io.en.poke(true.B)
//       println(s"=== Feeding K=$K cycles of 1s then $pad cycles of 0s ===")
//       for (cycle <- 0 until total) {
//         val v = if (cycle < K) 1 else 0
//         dut.io.row.foreach(_.poke(v.U))
//         dut.io.col.foreach(_.poke(v.U))
//         dut.clock.step(1)
//         println(f"  cyc[$cycle%2d] feed=$v: " +
//                 f"(0,0)=${dut.io.out_sum(0)(0).peek().litValue} " +
//                 f"(0,1)=${dut.io.out_sum(0)(1).peek().litValue} " +
//                 f"(0,2)=${dut.io.out_sum(0)(2).peek().litValue} " +
//                 f"(0,3)=${dut.io.out_sum(0)(3).peek().litValue} " +
//                 f"(1,1)=${dut.io.out_sum(1)(1).peek().litValue} " +
//                 f"(3,3)=${dut.io.out_sum(3)(3).peek().litValue}")
//       }
//     }
//   }
// }