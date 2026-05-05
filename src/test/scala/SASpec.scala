package Mixed_opt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import GoldenModel._

/**
 * SASpec — corrected timing.
 *
 * Key insight: out_a and out_b are RegNext pass-throughs (1 cycle delay per hop).
 * For an NxN SA, the last MAC for PE(N-1,N-1) completes at cycle 3N-3 (0-indexed),
 * so totalCycles = 3N-2 with en=1 throughout.
 *
 * Correct input skew (SA module, no internal SkewBuffers):
 *   in_a(i) at cycle t = A[i][t-i]  if 0 <= t-i < K  else 0
 *   in_b(j) at cycle t = B[t-j][j]  if 0 <= t-j < K  else 0
 */
class SASpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  // Golden model is bit-exact with RTL; Float/Double rounding only
  val FP16_TOL = 1e-5

  def resetSA(dut: SA): Unit = {
    dut.io.res.poke(true.B); dut.io.en.poke(false.B)
    dut.clock.step()
    dut.io.res.poke(false.B)
  }

  def readInt(dut: SA, i: Int, j: Int): Int =
    dut.io.out_sum(i)(j).peek().litValue.toInt

  def readFP(dut: SA, i: Int, j: Int): Float =
    fp32BitsToFloat(dut.io.out_sum(i)(j).peek().litValue.toLong)

  /** Run a full NxN matmul through the SA module with correct skew and timing. */
  def runSAMatmul(dut: SA, N: Int, A: Seq[Seq[Int]], B: Seq[Seq[Int]]): Unit = {
    val K     = N
    val total = 3 * N - 2  // last MAC at t=3N-3; en=1 for all these cycles

    for (t <- 0 until total) {
      dut.io.en.poke(true.B); dut.io.res.poke(false.B)
      for (i <- 0 until N) {
        val ki = t - i
        dut.io.in_a(i).poke((if (ki >= 0 && ki < K) A(i)(ki) & 0xFFFF else 0).U)
      }
      for (j <- 0 until N) {
        val kj = t - j
        dut.io.in_b(j).poke((if (kj >= 0 && kj < K) B(kj)(j) & 0xFFFF else 0).U)
      }
      dut.clock.step()
    }
    dut.io.en.poke(false.B)
    dut.clock.step()
  }

  // ── Accumulator reset ─────────────────────────────────────────────────────

  "SA" should "reset all accumulators to 0 on res=1" in {
    test(new SA(2, 2)) { dut =>
      dut.io.mode.poke(false.B)
      val A = Seq(Seq(5, 3), Seq(5, 3))
      val B = Seq(Seq(7, 7), Seq(2, 2))
      resetSA(dut)
      runSAMatmul(dut, 2, A, B)
      assert(readInt(dut, 0, 0) != 0, "expected non-zero before reset")
      resetSA(dut)
      for (i <- 0 until 2; j <- 0 until 2)
        readInt(dut, i, j) shouldBe 0
    }
  }

  // ── Accumulator freeze ────────────────────────────────────────────────────

  it should "freeze accumulators when en=0" in {
    // Strategy: run a complete 2×2 matmul so every PE accumulates a known
    // non-zero value, snapshot all four cells, freeze for 4 cycles with
    // large garbage inputs, then verify every cell is unchanged.
    val N    = 2
    val A    = Seq(Seq(3, 2), Seq(4, 1))
    val B    = Seq(Seq(5, 6), Seq(7, 8))
    val gold = matmulInt8(A, B)   // [[29, 34], [27, 32]]

    test(new SA(N, N)) { dut =>
      dut.io.mode.poke(false.B)
      resetSA(dut)
      runSAMatmul(dut, N, A, B)   // completes with en=0 at the end

      // All four accumulators now hold the matmul result
      val snap = Seq.tabulate(N, N)((i, j) => readInt(dut, i, j))
      for (i <- 0 until N; j <- 0 until N)
        assert(snap(i)(j) == gold(i)(j),
          s"pre-freeze check C($i,$j): got=${snap(i)(j)} gold=${gold(i)(j)}")

      // Freeze for 4 more cycles with large inputs — accumulators must not move
      dut.io.en.poke(false.B); dut.io.res.poke(false.B)
      dut.io.in_a(0).poke(127.U); dut.io.in_a(1).poke(127.U)
      dut.io.in_b(0).poke(127.U); dut.io.in_b(1).poke(127.U)
      dut.clock.step(4)

      for (i <- 0 until N; j <- 0 until N)
        readInt(dut, i, j) shouldBe snap(i)(j)
    }
  }

  // ── Data wavefront propagation ────────────────────────────────────────────

  it should "propagate in_a horizontally via out_a with 1-cycle delay per hop" in {
    test(new SA(1, 3)) { dut =>
      dut.io.mode.poke(false.B)
      resetSA(dut)

      dut.io.en.poke(true.B); dut.io.res.poke(false.B)
      dut.io.in_a(0).poke(4.U)
      dut.io.in_b(0).poke(1.U); dut.io.in_b(1).poke(1.U); dut.io.in_b(2).poke(1.U)
      dut.clock.step()
      readInt(dut, 0, 0) shouldBe int8Mac(4, 1, 0)  // 4 accumulated at hop 0

      dut.io.in_a(0).poke(0.U)  // stop sending new data
      dut.clock.step()
      readInt(dut, 0, 1) shouldBe int8Mac(4, 1, 0)  // forwarded 4 arrives at hop 1

      dut.clock.step()
      readInt(dut, 0, 2) shouldBe int8Mac(4, 1, 0)  // forwarded 4 arrives at hop 2
    }
  }

  // ── 2×2 INT8 matrix multiply ─────────────────────────────────────────────

  it should "compute correct 2×2 INT8 matmul" in {
    val N    = 2
    val A    = Seq(Seq(2,3), Seq(4,5))
    val B    = Seq(Seq(1,2), Seq(3,4))
    val gold = matmulInt8(A, B)  // [[11,16],[19,28]]

    test(new SA(N, N)) { dut =>
      dut.io.mode.poke(false.B)
      resetSA(dut)
      runSAMatmul(dut, N, A, B)
      for (i <- 0 until N; j <- 0 until N)
        assert(readInt(dut, i, j) == gold(i)(j),
          s"2×2 INT8 C($i,$j): got=${readInt(dut,i,j)}  gold=${gold(i)(j)}")
    }
  }

  // ── 2×2 FP16 matrix multiply ─────────────────────────────────────────────

  it should "compute correct 2×2 FP16 matmul" in {
    val N  = 2
    val af = Seq(Seq(1.5f, 0.5f), Seq(-1.0f, 1.25f))
    val bf = Seq(Seq(1.5f, 1.0f), Seq(-0.5f, 1.25f))
    val A  = af.map(_.map(floatToHalf(_).toInt))
    val B  = bf.map(_.map(floatToHalf(_).toInt))
    val gold = matmulFP16(A, B)

    test(new SA(N, N)) { dut =>
      dut.io.mode.poke(true.B)
      resetSA(dut)
      runSAMatmul(dut, N, A, B)
      for (i <- 0 until N; j <- 0 until N) {
        val got = readFP(dut, i, j)
        val re  = relErr(got, gold(i)(j))
        assert(re < FP16_TOL,
          f"2×2 FP16 C($i,$j): got=$got%.5f  gold=${gold(i)(j)}%.5f  relErr=${re*100}%%")
      }
    }
  }

  // ── 3×3 INT8 stress ───────────────────────────────────────────────────────

  it should "compute correct 3×3 INT8 matmul (5 random cases)" in {
    val N   = 3
    val rng = new scala.util.Random(42)
    for (run <- 0 until 5) {
      val A    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
      val B    = Seq.fill(N)(Seq.fill(N)(rng.nextInt(16) - 8))
      val gold = matmulInt8(A, B)

      test(new SA(N, N)) { dut =>
        dut.io.mode.poke(false.B)
        resetSA(dut)
        runSAMatmul(dut, N, A, B)
        for (i <- 0 until N; j <- 0 until N)
          assert(readInt(dut, i, j) == gold(i)(j),
            s"3×3 INT8 run=$run C($i,$j): got=${readInt(dut,i,j)}  gold=${gold(i)(j)}")
      }
    }
  }
}