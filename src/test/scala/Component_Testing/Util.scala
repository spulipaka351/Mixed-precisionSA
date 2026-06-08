// package Mixed

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// // ─────────────────────────────────────────────────────────────────
// //  MAC Utilization Testbench
// //
// //  Measures: cycles, useful MACs, MACs/cycle, utilization %
// //  for both INT8 and FP16 modes across increasing K (inner dim).
// //
// //  Utilization = usefulMACs / (numPEs * totalCycles)
// //  Peak        = numPEs MACs/cycle  (every PE busy every cycle)
// //
// //  NOTE: test(new ...) { dut => } returns TestResult, not the
// //  value computed inside the block. All extracted values use
// //  a var declared outside the test block.
// // ─────────────────────────────────────────────────────────────────
// class Util extends AnyFlatSpec with ChiselScalatestTester {

//   val ROWS    = 4
//   val COLS    = 4
//   val NUM_PES = ROWS * COLS   // 16

//   // ── helpers (same as your existing TB) ───────────────────────

//   def floatToFp16(f: Float): Int = {
//     val bits = java.lang.Float.floatToIntBits(f)
//     val sign = (bits >>> 31) & 0x1
//     val exp  = ((bits >>> 23) & 0xFF) - 127 + 15
//     val man  = (bits >>> 13) & 0x3FF
//     if (exp <= 0)       sign << 15
//     else if (exp >= 31) (sign << 15) | (31 << 10)
//     else                (sign << 15) | (exp << 10) | man
//   }

//   def packInt8(v: Int): Int = v & 0xFF

//   // ── result container ─────────────────────────────────────────

//   case class UtilResult(
//     mode:         String,
//     K:            Int,
//     totalCycles:  Int,
//     usefulMACs:   Int,
//     macsPerCycle: Double,
//     utilPct:      Double
//   ) {
//     override def toString: String =
//       f"$mode%-6s  K=$K%4d  cycles=$totalCycles%5d  " +
//       f"MACs=$usefulMACs%6d  MACs/cycle=$macsPerCycle%6.2f  util=$utilPct%6.2f%%"
//   }

//   // ── core driver — returns (outputs, cycleCount) ───────────────
//   //
//   // Identical structure to your runMatmul but also returns how many
//   // cycles elapsed so the caller can compute utilization.
//   def runAndCount(
//     dut:      TopMixedSA,
//     rowsData: Seq[Seq[Int]],
//     colsData: Seq[Seq[Int]],
//     mode:     Boolean,
//     depth:    Int
//   ): (Seq[Seq[Long]], Int) = {

//     val rows      = rowsData.length
//     val cols      = colsData.length
//     val maxSkew   = (rows - 1) + (cols - 1)   // skew buffer latency
//     val totalCyc  = depth + maxSkew            // feed cycles

//     // reset
//     dut.io.res.poke(true.B)
//     dut.io.en.poke(false.B)
//     dut.io.mode.poke(mode.B)
//     dut.io.row.foreach(_.poke(0.U))
//     dut.io.col.foreach(_.poke(0.U))
//     dut.clock.step(2)
//     dut.io.res.poke(false.B)

//     // feed K cycles of real data followed by maxSkew zero-pad cycles
//     dut.io.en.poke(true.B)
//     for (cycle <- 0 until totalCyc) {
//       for (i <- 0 until rows)
//         dut.io.row(i).poke((if (cycle < depth) rowsData(i)(cycle) else 0).U)
//       for (j <- 0 until cols)
//         dut.io.col(j).poke((if (cycle < depth) colsData(j)(cycle) else 0).U)
//       dut.clock.step(1)
//     }

//     // read outputs
//     val outputs = Seq.tabulate(rows, cols) { (i, j) =>
//       dut.io.out_sum(i)(j).peek().litValue.toLong & 0xFFFFFFFFL
//     }

//     (outputs, totalCyc)
//   }

//   // ── compute utilization from raw cycle count ──────────────────

//   def computeUtil(mode: String, K: Int, totalCycles: Int): UtilResult = {
//     val usefulMACs   = NUM_PES * K
//     val macsPerCycle = usefulMACs.toDouble / totalCycles
//     val utilPct      = (macsPerCycle / NUM_PES) * 100.0
//     UtilResult(mode, K, totalCycles, usefulMACs, macsPerCycle, utilPct)
//   }

//   // ── INT8 utilization sweep ────────────────────────────────────

//   behavior of "TopMixedSA MAC utilization — INT8"

//   it should "report utilization across K = 4, 8, 16, 32, 64" in {
//     val rng     = new Random(1)
//     val kValues = Seq(4, 8, 16, 32, 64)

//     println("\n=== INT8 utilization sweep ===")
//     println(f"${"mode"}%-6s  ${"K"}%4s  ${"cycles"}%5s  ${"MACs"}%6s  ${"MACs/cyc"}%8s  ${"util"}%6s")
//     println("-" * 60)

//     // var declared outside test block — the only way to extract
//     // values from inside test(){ } since it returns TestResult
//     var results = Seq.empty[UtilResult]

//     kValues.foreach { K =>
//       var r: UtilResult = null

//       test(new TopMixedSA(ROWS, COLS)) { dut =>
//         val A        = Seq.fill(ROWS, K)(rng.nextInt(256))
//         val B        = Seq.fill(K, COLS)(rng.nextInt(256))
//         val rowsData = A.map(_.map(packInt8))
//         val colsData = Seq.tabulate(COLS, K)((j, k) => packInt8(B(k)(j)))

//         val (_, totalCyc) = runAndCount(dut, rowsData, colsData, mode = false, depth = K)
//         r = computeUtil("INT8", K, totalCyc)
//       }

//       println(r)
//       assert(r.utilPct > 0.0 && r.utilPct <= 100.0,
//         s"INT8 util out of range at K=$K: ${r.utilPct}%%")

//       results = results :+ r
//     }

//     // utilization must improve (or hold) as K grows —
//     // fill/drain overhead is fixed, so larger K amortizes it
//     for (i <- 1 until results.length)
//       assert(results(i).utilPct >= results(i - 1).utilPct - 1.0,
//         s"Util should increase with K: " +
//         s"K=${kValues(i)} gave ${results(i).utilPct}%% < " +
//         s"K=${kValues(i-1)} gave ${results(i-1).utilPct}%%")

//     println(f"\nPeak at K=64: ${results.last.utilPct}%.1f%%")
//   }

//   // ── FP16 utilization sweep ────────────────────────────────────

//   behavior of "TopMixedSA MAC utilization — FP16"

//   it should "report utilization across K = 4, 8, 16, 32, 64" in {
//     val rng     = new Random(2)
//     val kValues = Seq(4, 8, 16, 32, 64)

//     println("\n=== FP16 utilization sweep ===")
//     println(f"${"mode"}%-6s  ${"K"}%4s  ${"cycles"}%5s  ${"MACs"}%6s  ${"MACs/cyc"}%8s  ${"util"}%6s")
//     println("-" * 60)

//     kValues.foreach { K =>
//       var r: UtilResult = null

//       test(new TopMixedSA(ROWS, COLS)) { dut =>
//         def randFp16(): Int = {
//           val exp  = rng.nextInt(10) + 10
//           val sign = rng.nextInt(2)
//           val man  = rng.nextInt(1024)
//           (sign << 15) | (exp << 10) | man
//         }

//         val rowsData = Seq.fill(ROWS, K)(randFp16())
//         val colsData = Seq.fill(COLS, K)(randFp16())

//         val (_, totalCyc) = runAndCount(dut, rowsData, colsData, mode = true, depth = K)
//         r = computeUtil("FP16", K, totalCyc)
//       }

//       println(r)
//       assert(r.utilPct > 0.0 && r.utilPct <= 100.0,
//         s"FP16 util out of range at K=$K: ${r.utilPct}%%")
//     }
//   }

//   // ── Mode cycle parity ─────────────────────────────────────────
//   //
//   // INT8 and FP16 must take identical cycles for the same K.
//   // mode only changes arithmetic, not the systolic timing structure.
//   // Any difference here = structural timing bug in your design.

//   behavior of "TopMixedSA mode cycle parity"

//   it should "INT8 and FP16 take same cycles for same K" in {
//     val rng = new Random(3)
//     val K   = 16

//     println("\n=== Mode cycle parity at K=16 ===")

//     var int8Cycles = 0
//     var fp16Cycles = 0

//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val rowsData = Seq.fill(ROWS, K)(rng.nextInt(256)).map(_.map(packInt8))
//       val colsData = Seq.fill(COLS, K)(rng.nextInt(256)).map(_.map(packInt8))
//       val (_, cyc) = runAndCount(dut, rowsData, colsData, mode = false, depth = K)
//       int8Cycles = cyc
//     }

//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       def randFp16() =
//         (rng.nextInt(2) << 15) | ((rng.nextInt(10) + 10) << 10) | rng.nextInt(1024)
//       val rowsData = Seq.fill(ROWS, K)(randFp16())
//       val colsData = Seq.fill(COLS, K)(randFp16())
//       val (_, cyc) = runAndCount(dut, rowsData, colsData, mode = true, depth = K)
//       fp16Cycles = cyc
//     }

//     println(f"INT8 cycles = $int8Cycles   FP16 cycles = $fp16Cycles")
//     assert(int8Cycles == fp16Cycles,
//       s"Mode should not affect cycle count: INT8=$int8Cycles FP16=$fp16Cycles")

//     val r = computeUtil("both", K, int8Cycles)
//     println(f"Shared utilization at K=$K: ${r.utilPct}%.2f%%  (${r.macsPerCycle}%.2f MACs/cycle)")
//   }

//   // ── Summary table ─────────────────────────────────────────────
//   //
//   // Paper-ready table: both modes, all K values, one place.

//   behavior of "TopMixedSA utilization summary"

//   it should "print paper-ready comparison table for all K values" in {
//     val rng     = new Random(99)
//     val kValues = Seq(4, 8, 16, 32, 64)

//     println("\n╔══════════════════════════════════════════════════════════╗")
//     println("║           MAC Utilization Summary — TopMixedSA           ║")
//     println("╠════════╦══════╦═════════╦═══════════════╦═══════════════╣")
//     println("║   K    ║ mode ║  cycles ║  MACs/cycle   ║  utilization  ║")
//     println("╠════════╬══════╬═════════╬═══════════════╬═══════════════╣")

//     for (K <- kValues) {
//       for ((modeStr, modeBool) <- Seq(("INT8", false), ("FP16", true))) {
//         var r: UtilResult = null

//         test(new TopMixedSA(ROWS, COLS)) { dut =>
//           val rowsData: Seq[Seq[Int]] = if (!modeBool) {
//             Seq.fill(ROWS, K)(rng.nextInt(256)).map(_.map(packInt8))
//           } else {
//             def randFp16() =
//               (rng.nextInt(2) << 15) | ((rng.nextInt(10) + 10) << 10) | rng.nextInt(1024)
//             Seq.fill(ROWS, K)(randFp16())
//           }

//           val colsData: Seq[Seq[Int]] = if (!modeBool) {
//             Seq.fill(COLS, K)(rng.nextInt(256)).map(_.map(packInt8))
//           } else {
//             def randFp16() =
//               (rng.nextInt(2) << 15) | ((rng.nextInt(10) + 10) << 10) | rng.nextInt(1024)
//             Seq.fill(COLS, K)(randFp16())
//           }

//           val (_, totalCyc) = runAndCount(dut, rowsData, colsData, modeBool, depth = K)
//           r = computeUtil(modeStr, K, totalCyc)
//         }

//         println(f"║  $K%4d  ║ $modeStr%-4s ║  ${r.totalCycles}%5d  ║   ${r.macsPerCycle}%8.2f    ║   ${r.utilPct}%8.2f%%   ║")
//       }

//       if (K != kValues.last)
//         println("╠════════╬══════╬═════════╬═══════════════╬═══════════════╣")
//     }

//     println("╚════════╩══════╩═════════╩═══════════════╩═══════════════╝")
//     println(s"\nPeak theoretical: $NUM_PES MACs/cycle")
//     println(s"Array: ${ROWS}x${COLS} = $NUM_PES PEs")
//     println(s"Fixed overhead: ${(ROWS - 1) + (COLS - 1)} cycles (fill + drain)")
//   }
// }