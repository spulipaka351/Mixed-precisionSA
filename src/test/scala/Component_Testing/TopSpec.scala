// package Mixed_opt

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec
// import org.scalatest.matchers.should.Matchers
// import GoldenModel._

// /**
//  * TopSpec — corrected timing for Top (SkewBuffers + SA).
//  *
//  * Timing analysis for an NxN array:
//  *   SkewBuffers delay row[i] by i cycles, col[j] by j cycles.
//  *   Caller feeds io.row[i] = A[i][t] and io.col[j] = B[t][j] each cycle.
//  *   The last non-zero skewed value exits SkewBuffers at t = 2N-2,
//  *   but then must traverse N-1 out_a/out_b hops inside the SA.
//  *   Last MAC at PE(N-1,N-1) completes at t = 3N-3.
//  *   Therefore en=1 for cycles 0..3N-3 (= 3N-2 active cycles), then 1 drain cycle.
//  *
//  * The original driver used computeEnd = 2N-2, which is N-1 cycles short.
//  */
// class TopSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

//   val FP16_TOL = 1e-5  // golden model bit-exact with RTL

//   // ── Driver ────────────────────────────────────────────────────────────────

//   /**
//    * Run a full NxN matmul through Top.
//    * A and B are NxN matrices of raw 16-bit values.
//    * Returns final out_sum as 2D Seq of raw UInt bits (Long).
//    */
//   def runMatmul(dut: Top, N: Int, A: Seq[Seq[Int]], B: Seq[Seq[Int]],
//                 mode: Boolean): Seq[Seq[Long]] = {

//     val activeCycles = 3 * N - 2   // en=1 for cycles 0..3N-3
//     val totalCycles  = activeCycles + 1  // +1 drain cycle (en=0)

//     // Reset
//     dut.io.mode.poke(mode.B)
//     dut.io.res.poke(true.B); dut.io.en.poke(false.B)
//     dut.clock.step()
//     dut.io.res.poke(false.B)

//     for (t <- 0 until totalCycles) {
//       val active = t < activeCycles
//       dut.io.en.poke(active.B)

//       // Feed un-skewed: SkewBuffers handle the delay internally
//       for (i <- 0 until N) {
//         val v = if (t < N) A(i)(t) & 0xFFFF else 0
//         dut.io.row(i).poke(v.U)
//       }
//       for (j <- 0 until N) {
//         val v = if (t < N) B(t)(j) & 0xFFFF else 0
//         dut.io.col(j).poke(v.U)
//       }
//       dut.clock.step()
//     }

//     Seq.tabulate(N, N)((i, j) =>
//       dut.io.out_sum(i)(j).peek().litValue.toLong)
//   }

//   def readInt2D(raw: Seq[Seq[Long]]): Seq[Seq[Int]]   = raw.map(_.map(_.toInt))
//   def readFP2D (raw: Seq[Seq[Long]]): Seq[Seq[Float]] = raw.map(_.map(b => fp32BitsToFloat(b)))

//   // ── 2×2 INT8 identity ────────────────────────────────────────────────────

//   "Top" should "produce A when B is identity for 2×2 INT8" in {
//     val N = 2
//     val A = Seq(Seq(3, 7), Seq(-2, 5))
//     val I = Seq(Seq(1, 0), Seq(0, 1))
//     val gold = matmulInt8(A, I)

//     test(new Top(N, N)) { dut =>
//       val got = readInt2D(runMatmul(dut, N, A, I, mode = false))
//       for (i <- 0 until N; j <- 0 until N)
//         got(i)(j) shouldBe gold(i)(j)
//     }
//   }

//   // ── 2×2 INT8 arbitrary ───────────────────────────────────────────────────

//   it should "compute correct 2×2 INT8 matmul" in {
//     val N = 2
//     val A = Seq(Seq(3, -1), Seq(5,  2))
//     val B = Seq(Seq(2,  4), Seq(-3, 1))
//     val gold = matmulInt8(A, B)

//     test(new Top(N, N)) { dut =>
//       val got = readInt2D(runMatmul(dut, N, A, B, mode = false))
//       for (i <- 0 until N; j <- 0 until N)
//         assert(got(i)(j) == gold(i)(j),
//           s"2×2 INT8 C($i,$j): got=${got(i)(j)}  gold=${gold(i)(j)}")
//     }
//   }

//   // ── 2×2 FP16 ─────────────────────────────────────────────────────────────

//   it should "compute correct 2×2 FP16 matmul" in {
//     val N  = 2
//     val af = Seq(Seq(1.5f,  0.5f), Seq(-1.0f, 1.25f))
//     val bf = Seq(Seq(1.5f,  1.0f), Seq(-0.5f, 1.25f))
//     val A  = af.map(_.map(v => floatToHalf(v)))
//     val B  = bf.map(_.map(v => floatToHalf(v)))
//     val gold = matmulFP16(A, B)

//     test(new Top(N, N)) { dut =>
//       val got = readFP2D(runMatmul(dut, N, A, B, mode = true))
//       for (i <- 0 until N; j <- 0 until N) {
//         val re = relErr(got(i)(j), gold(i)(j))
//         assert(re < FP16_TOL,
//           f"2×2 FP16 C($i,$j): got=${got(i)(j)}%.5f  gold=${gold(i)(j)}%.5f  relErr=${re*100}%%")
//       }
//     }
//   }

//   // ── 3×3 INT8 stress ───────────────────────────────────────────────────────

//   it should "compute correct 3×3 INT8 matmul (5 random cases)" in {
//     val N   = 3
//     val rng = new scala.util.Random(0xCAFE)
//     for (run <- 0 until 5) {
//       val A    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
//       val B    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
//       val gold = matmulInt8(A, B)

//       test(new Top(N, N)) { dut =>
//         val got = readInt2D(runMatmul(dut, N, A, B, mode = false))
//         for (i <- 0 until N; j <- 0 until N)
//           assert(got(i)(j) == gold(i)(j),
//             s"3×3 INT8 run=$run C($i,$j): got=${got(i)(j)}  gold=${gold(i)(j)}")
//       }
//     }
//   }

//   // ── 3×3 FP16 stress ───────────────────────────────────────────────────────

//   it should "compute correct 3×3 FP16 matmul (5 random cases)" in {
//     val N   = 3
//     val rng = new scala.util.Random(0xF00D)
//     for (run <- 0 until 5) {
//       // [0.5, 1.5] magnitude avoids deep-cancellation flush-to-zero
//       val af = Seq.fill(N)(Seq.fill(N)(rng.nextFloat() + 0.5f))
//       val bf = Seq.fill(N)(Seq.fill(N)(rng.nextFloat() + 0.5f))
//       val A    = af.map(_.map(v => floatToHalf(if (rng.nextBoolean()) v else -v)))
//       val B    = bf.map(_.map(v => floatToHalf(if (rng.nextBoolean()) v else -v)))
//       val gold = matmulFP16(A, B)

//       test(new Top(N, N)) { dut =>
//         val got = readFP2D(runMatmul(dut, N, A, B, mode = true))
//         for (i <- 0 until N; j <- 0 until N) {
//           val re = relErr(got(i)(j), gold(i)(j))
//           assert(re < FP16_TOL,
//             f"3×3 FP16 run=$run C($i,$j): got=${got(i)(j)}%.5f  gold=${gold(i)(j)}%.5f  relErr=${re*100}%%")
//         }
//       }
//     }
//   }

//   // ── Back-to-back matmuls ──────────────────────────────────────────────────

//   it should "produce correct results for two back-to-back 2×2 INT8 matmuls" in {
//     val N  = 2
//     val A1 = Seq(Seq(1, 2), Seq(3, 4))
//     val B1 = Seq(Seq(5, 6), Seq(7, 8))
//     val A2 = Seq(Seq(-1, 3), Seq(2, -4))
//     val B2 = Seq(Seq(4, -2), Seq(-3, 1))

//     val gold1 = matmulInt8(A1, B1)
//     val gold2 = matmulInt8(A2, B2)

//     test(new Top(N, N)) { dut =>
//       dut.io.mode.poke(false.B)

//       val got1 = readInt2D(runMatmul(dut, N, A1, B1, mode = false))
//       for (i <- 0 until N; j <- 0 until N)
//         assert(got1(i)(j) == gold1(i)(j),
//           s"B2B first C($i,$j): got=${got1(i)(j)}  gold=${gold1(i)(j)}")

//       val got2 = readInt2D(runMatmul(dut, N, A2, B2, mode = false))
//       for (i <- 0 until N; j <- 0 until N)
//         assert(got2(i)(j) == gold2(i)(j),
//           s"B2B second C($i,$j): got=${got2(i)(j)}  gold=${gold2(i)(j)}")
//     }
//   }

//   // ── Mid-computation freeze ────────────────────────────────────────────────

//   it should "hold accumulators when en is de-asserted mid-computation" in {
//     val N = 2
//     test(new Top(N, N)) { dut =>
//       dut.io.mode.poke(false.B)

//       // Reset
//       dut.io.res.poke(true.B); dut.io.en.poke(false.B)
//       dut.clock.step()
//       dut.io.res.poke(false.B)

//       val A = Seq(Seq(4, 2), Seq(-3, 5))
//       val B = Seq(Seq(3, -1), Seq(2, 4))

//       // Cycle 0: first active cycle
//       dut.io.en.poke(true.B)
//       dut.io.row(0).poke((A(0)(0) & 0xFFFF).U); dut.io.row(1).poke((A(1)(0) & 0xFFFF).U)
//       dut.io.col(0).poke((B(0)(0) & 0xFFFF).U); dut.io.col(1).poke((B(0)(1) & 0xFFFF).U)
//       dut.clock.step()

//       val snap = Seq.tabulate(N, N)((i, j) =>
//         dut.io.out_sum(i)(j).peek().litValue.toLong)

//       // Freeze for 3 cycles with garbage inputs
//       dut.io.en.poke(false.B)
//       dut.io.row(0).poke(127.U); dut.io.row(1).poke(127.U)
//       dut.io.col(0).poke(127.U); dut.io.col(1).poke(127.U)
//       dut.clock.step(3)

//       for (i <- 0 until N; j <- 0 until N)
//         dut.io.out_sum(i)(j).peek().litValue.toLong shouldBe snap(i)(j)
//     }
//   }

//   // ── Mode isolation ────────────────────────────────────────────────────────

//   it should "not cross-contaminate FP16 and INT8 results on mode change" in {
//     val N  = 2
//     val Ai = Seq(Seq(2, 3), Seq(4, 5))
//     val Bi = Seq(Seq(1, 2), Seq(3, 4))
//     val af = Seq(Seq(1.0f, 0.5f), Seq(-1.0f, 1.25f))
//     val bf = Seq(Seq(1.5f, 1.0f), Seq(-0.5f, 0.5f))
//     val Af = af.map(_.map(floatToHalf(_)))
//     val Bf = bf.map(_.map(floatToHalf(_)))

//     val goldInt = matmulInt8(Ai, Bi)
//     val goldFP  = matmulFP16(Af, Bf)

//     test(new Top(N, N)) { dut =>
//       val gotInt = readInt2D(runMatmul(dut, N, Ai, Bi, mode = false))
//       for (i <- 0 until N; j <- 0 until N)
//         gotInt(i)(j) shouldBe goldInt(i)(j)

//       val gotFP = readFP2D(runMatmul(dut, N, Af, Bf, mode = true))
//       for (i <- 0 until N; j <- 0 until N) {
//         val re = relErr(gotFP(i)(j), goldFP(i)(j))
//         assert(re < FP16_TOL,
//           f"Mode-switch FP16 C($i,$j): got=${gotFP(i)(j)}%.5f  gold=${goldFP(i)(j)}%.5f  relErr=${re*100}%%")
//       }
//     }
//   }

//   // ── 4×4 INT8 large random batch ───────────────────────────────────────────

//   it should "compute correct 4×4 INT8 matmul (10 random cases)" in {
//     val N   = 4
//     val rng = new scala.util.Random(0x1234)
//     for (run <- 0 until 10) {
//       val A    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
//       val B    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
//       val gold = matmulInt8(A, B)

//       test(new Top(N, N)) { dut =>
//         val got = readInt2D(runMatmul(dut, N, A, B, mode = false))
//         for (i <- 0 until N; j <- 0 until N)
//           assert(got(i)(j) == gold(i)(j),
//             s"4×4 INT8 run=$run C($i,$j): got=${got(i)(j)}  gold=${gold(i)(j)}")
//       }
//     }
//   }
// }