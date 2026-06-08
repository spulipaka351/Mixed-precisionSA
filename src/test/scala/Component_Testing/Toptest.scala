// package Pipe.Component_Testing

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec
// import Pipe.Component_Testing.FPHelpers

// // TopTest — timing-accurate testbench for Top (skew buffers + pipelined SA)
// //
// // StridedSkewBuffers(stride=PIPE) delays row(i) by i*PIPE cycles.
// // So row(1) enters SA at cycle 3, row(2) at cycle 6, etc.
// //
// // PE(i,j) receives its (a,b) token at push cycle: max(i,j)*PIPE
// // Its result exits the PE pipeline at push cycle: max(i,j)*PIPE + PIPE - 1
// //
// // PUSH_CYCLES = (max(ROWS,COLS)-1)*PIPE + 1   (ensures all skewed inputs delivered)
// //
// // If pushValid(i,j) < PUSH_CYCLES  → collect during push phase
// // If pushValid(i,j) >= PUSH_CYCLES → collect at drain cycle = pushValid - PUSH_CYCLES

// class TopTest extends AnyFlatSpec with ChiselScalatestTester with FPHelpers {

//   val ROWS        = 2
//   val COLS        = 2
//   val PIPE        = 3
//   val PUSH_CYCLES = (math.max(ROWS, COLS) - 1) * PIPE + 1  // = 4 for 2x2
//   val DRAIN_MAX   = 10

//   // cycle (0-indexed from first push) when PE(i,j) result is valid
// def pushValid(i: Int, j: Int): Int = (i + j) * PIPE + PIPE - 1  // PE(0,0)=2, PE(0,1)=PE(1,0)=PE(1,1)=5 for PIPE=3

//   def setIn(dut: Top,
//             aCol: Seq[Float], bRow: Seq[Float],
//             en: Boolean, res: Boolean = false): Unit = {
//     dut.io.res.poke(res.B)
//     dut.io.en.poke(en.B)
//     dut.io.mode.poke(true.B)
//     for (i <- 0 until ROWS) dut.io.row(i).poke(toFP16(aCol(i)).U)
//     for (j <- 0 until COLS) dut.io.col(j).poke(toFP16(bRow(j)).U)
//   }

//   def zeros(dut: Top, en: Boolean, res: Boolean = false): Unit =
//     setIn(dut, Seq.fill(ROWS)(0f), Seq.fill(COLS)(0f), en, res)

//   def peekGrid(dut: Top): Array[Array[Float]] =
//     Array.tabulate(ROWS, COLS)((i, j) =>
//       toFloat(dut.io.out_sum(i)(j).peek().litValue.toLong))

//   // Push one K-step for PUSH_CYCLES cycles, collect each PE's result
//   // at its exact valid cycle (during push or during drain).
//   def pushKStep(dut: Top,
//                 aCol: Seq[Float], bRow: Seq[Float],
//                 softAcc: Array[Array[Float]],
//                 kIdx: Int): Unit = {
//     println(s"  --- K$kIdx: aCol=$aCol bRow=$bRow ---")

//     // push phase
//     for (p <- 0 until PUSH_CYCLES) {
//       setIn(dut, aCol, bRow, en = true)
//       dut.clock.step(1)
//       val grid = peekGrid(dut)
//       for (i <- 0 until ROWS)
//         for (j <- 0 until COLS)
//           if (p == pushValid(i, j)) {
//             softAcc(i)(j) += grid(i)(j)
//             println(f"    PE($i,$j) push[$p]: ${grid(i)(j)}%7.3f  acc=${softAcc(i)(j)}%7.3f")
//           }
//     }

//     // drain phase — collect PEs whose pushValid >= PUSH_CYCLES
//     val maxPV    = (for (i <- 0 until ROWS; j <- 0 until COLS) yield pushValid(i, j)).max
//     val nDrain   = math.max(0, maxPV - PUSH_CYCLES + 1) + 2
//     for (d <- 0 until nDrain) {
//       zeros(dut, en = false)
//       dut.clock.step(1)
//       val grid = peekGrid(dut)
//       for (i <- 0 until ROWS)
//         for (j <- 0 until COLS) {
//           val pv = pushValid(i, j)
//           if (pv >= PUSH_CYCLES && d == pv - PUSH_CYCLES) {
//             softAcc(i)(j) += grid(i)(j)
//             println(f"    PE($i,$j) drain[$d]: ${grid(i)(j)}%7.3f  acc=${softAcc(i)(j)}%7.3f")
//           }
//         }
//     }
//   }

//   // ── Test 0: raw timing probe ────────────────────────────────────────────
//   // Drive a single K-step and print all outputs to verify timing.
//   "Top (2x2)" should "show correct per-PE timing" in {
//     test(new Top(ROWS, COLS)) { dut =>

//       zeros(dut, en = false, res = true)
//       dut.clock.step(1)
//       zeros(dut, en = false)

//       println(s"\nPUSH_CYCLES=$PUSH_CYCLES  pushValid(0,0)=${pushValid(0,0)}  pushValid(1,1)=${pushValid(1,1)}")

//       // push [1,3] x [5,6] for PUSH_CYCLES
//       // expect: PE(0,0)=5, PE(0,1)=6, PE(1,0)=15, PE(1,1)=18
//       for (p <- 0 until PUSH_CYCLES) {
//         setIn(dut, Seq(1f, 3f), Seq(5f, 6f), en = true)
//         dut.clock.step(1)
//         val g = peekGrid(dut)
//         println(f"  push $p: PE(0,0)=${g(0)(0)}%5.1f  PE(0,1)=${g(0)(1)}%5.1f  PE(1,0)=${g(1)(0)}%5.1f  PE(1,1)=${g(1)(1)}%5.1f")
//       }
//       for (d <- 0 until 8) {
//         zeros(dut, en = false)
//         dut.clock.step(1)
//         val g = peekGrid(dut)
//         println(f"  drain $d: PE(0,0)=${g(0)(0)}%5.1f  PE(0,1)=${g(0)(1)}%5.1f  PE(1,0)=${g(1)(0)}%5.1f  PE(1,1)=${g(1)(1)}%5.1f")
//       }
//     }
//   }

//   // ── Test 1: 2×2 GEMM ───────────────────────────────────────────────────
//   //  A = [[1, 2],    B = [[5, 6],     C = A×B = [[19, 22],
//   //       [3, 4]]         [7, 8]]                [43, 50]]
//   "Top (2x2)" should "compute correct 2x2 GEMM" in {
//     test(new Top(ROWS, COLS)) { dut =>

//       zeros(dut, en = false, res = true)
//       dut.clock.step(1)
//       zeros(dut, en = false)

//       val softAcc = Array.ofDim[Float](ROWS, COLS)

//       println("\n=== GEMM ===")
//       val kSteps = Seq(
//         (Seq(1f, 3f), Seq(5f, 6f)),
//         (Seq(2f, 4f), Seq(7f, 8f))
//       )
//       kSteps.zipWithIndex.foreach { case ((aCol, bRow), k) =>
//         pushKStep(dut, aCol, bRow, softAcc, k)
//       }

//       val expected = Array(Array(19f, 22f), Array(43f, 50f))
//       println("\n=== Verification ===")
//       for (i <- 0 until ROWS)
//         for (j <- 0 until COLS) {
//           val got = softAcc(i)(j)
//           val exp = expected(i)(j)
//           val ok  = math.abs(got - exp) < 0.5f
//           println(s"  C($i,$j): got=$got  expected=$exp  ${if(ok) "PASS" else "FAIL"}")
//           assert(ok, s"C($i,$j): got $got expected $exp")
//         }
//       println("2x2 GEMM passed.")
//     }
//   }

//   // ── Test 2: single K-step products ─────────────────────────────────────
//   "Top (2x2)" should "produce correct products for a single K-step" in {
//     test(new Top(ROWS, COLS)) { dut =>

//       zeros(dut, en = false, res = true)
//       dut.clock.step(1)
//       zeros(dut, en = false)

//       val softAcc  = Array.ofDim[Float](ROWS, COLS)
//       val expected = Array(Array(5f, 6f), Array(15f, 18f))

//       pushKStep(dut, Seq(1f, 3f), Seq(5f, 6f), softAcc, 0)

//       println("\n=== Single K-step verification ===")
//       for (i <- 0 until ROWS)
//         for (j <- 0 until COLS) {
//           val ok = math.abs(softAcc(i)(j) - expected(i)(j)) < 0.1f
//           println(s"  PE($i,$j): got=${softAcc(i)(j)}  expected=${expected(i)(j)}  ${if(ok) "PASS" else "FAIL"}")
//           assert(ok, s"PE($i,$j): got ${softAcc(i)(j)} expected ${expected(i)(j)}")
//         }
//       println("Single K-step passed.")
//     }
//   }
// }