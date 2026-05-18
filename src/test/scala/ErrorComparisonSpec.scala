package Mixed_opt

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/**
 * OptPEErrorAnalysis — quantifies the numeric error introduced by the
 * simplified (QAT-optimised) FP32 normalization in OptPE vs an exact
 * software reference.  Not a pass/fail suite — prints a full report.
 */
class OptPEErrorAnalysis extends AnyFlatSpec with ChiselScalatestTester {

  // ── bit-level helpers (no GoldenModel dependency) ─────────────────────

  def f32Bits(f: Float): Long  = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  def bitsF32(b: Long):  Float = java.lang.Float.intBitsToFloat(b.toInt)

  def toFP16(f: Float): Int = {
    val b    = java.lang.Float.floatToRawIntBits(f)
    val sign = (b >>> 31) & 0x1
    val exp  = ((b >>> 23) & 0xFF) - 127 + 15
    val man  = (b & 0x7FFFFF) >>> 13
    (sign << 15) | (exp << 10) | man
  }
  def fromFP16(raw: Int): Float = {
    val sign = (raw >>> 15) & 0x1
    val exp  = ((raw >>> 10) & 0x1F) - 15 + 127
    val man  = (raw & 0x3FF) << 13
    bitsF32(((sign << 31) | (exp << 23) | man).toLong & 0xFFFFFFFFL)
  }

  def driveFP16(dut: OptPE, ia: Int, ib: Int, psumF: Float): Float = {
    dut.io.mode.poke(true.B)
    dut.io.in_a.poke((ia & 0xFFFF).U)
    dut.io.in_b.poke((ib & 0xFFFF).U)
    dut.io.psum_in.poke(f32Bits(psumF).U)
    dut.clock.step()
    bitsF32(dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL)
  }

  // exact reference: multiply in Float32 arithmetic
  def ref(ia: Int, ib: Int, psum: Float): Float =
    fromFP16(ia) * fromFP16(ib) + psum

  def relErr(got: Float, ref: Float): Double = {
    val abs = math.abs(got - ref).toDouble
    if (math.abs(ref) < 1e-9) abs else abs / math.abs(ref)
  }

  // ── bucket thresholds ─────────────────────────────────────────────────
  val THRESHOLDS = Seq(0.001, 0.01, 0.05, 0.10, 0.20, 1.0)  // relative error upper bounds

  // ── scenarios ─────────────────────────────────────────────────────────
  // Each scenario exercises a different normalization path in the hardware.
  case class Scenario(name: String, aRange: (Float,Float), bRange: (Float,Float),
                      psumRange: (Float,Float), n: Int = 500)

  val scenarios = Seq(
    Scenario("Normal × Normal, psum≈0",      (0.5f, 2.0f), (0.5f, 2.0f), (-0.01f, 0.01f)),
    Scenario("Near-max × near-max (overflow branch)", (1.5f, 2.0f), (1.5f, 2.0f), (0f, 0f), 200),
    Scenario("Cancellation (psum ≈ -product)", (0.5f, 2.0f), (0.5f, 2.0f), (-4.0f, -0.1f)),
    Scenario("Large psum accumulation",       (0.5f, 1.5f), (0.5f, 1.5f), (1.0f,  8.0f)),
    Scenario("Mixed signs",                   (0.5f, 2.0f), (0.5f, 2.0f), (-4.0f, 4.0f)),
  )

  "OptPE FP16 Error Analysis" should "report error distribution per normalization scenario" in {
    test(new OptPE()) { dut =>

      println("\n" + "=" * 72)
      println("  OptPE FP16 Error Analysis  (simplified QAT normalization vs exact)")
      println("=" * 72)

      var grandTotal = 0
      var grandExact = 0
      var grandMax   = 0.0
      val grandBuckets = Array.fill(THRESHOLDS.length)(0)

      for (sc <- scenarios) {
        val rng     = new scala.util.Random(0xCAFE)
        val buckets = Array.fill(THRESHOLDS.length)(0)
        var maxRE   = 0.0
        var exact   = 0
        var total   = 0

        for (_ <- 0 until sc.n) {
          // generate inputs with random signs
          val aAbs = sc.aRange._1 + rng.nextFloat() * (sc.aRange._2 - sc.aRange._1)
          val bAbs = sc.bRange._1 + rng.nextFloat() * (sc.bRange._2 - sc.bRange._1)
          val aF   = if (rng.nextBoolean()) aAbs else -aAbs
          val bF   = if (rng.nextBoolean()) bAbs else -bAbs
          val pF   = sc.psumRange._1 + rng.nextFloat() * (sc.psumRange._2 - sc.psumRange._1)

          val ia  = toFP16(aF)
          val ib  = toFP16(bF)
          val got = driveFP16(dut, ia, ib, pF)
          val r   = ref(ia, ib, pF)
          val re  = relErr(got, r)

          total += 1
          if (re == 0.0) exact += 1
          if (re > maxRE) maxRE = re

          // place into the first bucket whose threshold it fits
          val idx = THRESHOLDS.indexWhere(re <= _)
          if (idx >= 0) buckets(idx) += 1
          // else: above all thresholds → counted as overflow

          grandTotal += 1
          if (re == 0.0) grandExact += 1
          if (re > grandMax) grandMax = re
          val gi = THRESHOLDS.indexWhere(re <= _)
          if (gi >= 0) grandBuckets(gi) += 1
        }

        // ── per-scenario table ──────────────────────────────────────────
        println(s"\nScenario: ${sc.name}  (n=${sc.n})")
        println(f"  Exact matches : $exact%4d / $total  (${100.0*exact/total}%.1f%%)")
        println(f"  Max rel error : ${maxRE*100}%.4f%%")
        println("  Distribution:")
        var cumul = 0
        for ((t, cnt) <- THRESHOLDS.zip(buckets)) {
          cumul += cnt
          println(f"    relErr ≤ ${t*100}%5.1f%%  →  $cnt%4d samples  " +
                  f"(${100.0*cnt/total}%5.1f%% this bucket,  " +
                  f"${100.0*cumul/total}%5.1f%% cumulative)")
        }
        val overflow = total - buckets.sum
        if (overflow > 0)
          println(f"    relErr  > ${THRESHOLDS.last*100}%5.1f%%  →  $overflow%4d samples  " +
                  f"(${100.0*overflow/total}%5.1f%%)")
      }

      // ── grand summary ─────────────────────────────────────────────────
      println("\n" + "-" * 72)
      println(s"GRAND SUMMARY  (all scenarios, n=$grandTotal)")
      println(f"  Exact matches : $grandExact / $grandTotal  (${100.0*grandExact/grandTotal}%.1f%%)")
      println(f"  Max rel error : ${grandMax*100}%.4f%%")
      println("  Cumulative distribution:")
      var cum = 0
      for ((t, cnt) <- THRESHOLDS.zip(grandBuckets)) {
        cum += cnt
        println(f"    within ${t*100}%5.1f%%  →  ${100.0*cum/grandTotal}%6.2f%% of all samples")
      }
      println("=" * 72 + "\n")

      // soft assertion: at least 95% of samples within 5% relative error
      val within5pct = grandBuckets.take(THRESHOLDS.indexOf(0.05) + 1).sum
      assert(within5pct.toDouble / grandTotal >= 0.95,
        s"Fewer than 95% of samples within 5% relative error: " +
        s"${100.0*within5pct/grandTotal}%.1f%%")
    }
  }
}