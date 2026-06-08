// package Mixed_opt

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// /**
//  * OptPEFTZDiagnosis — finds the exact inputs that reach the otherwise/FTZ
//  * fallback in the FP32-add normalization, and prints what sum_man looks
//  * like at that point so you can decide how many more shift stages to add.
//  */
// class OptPEFTZDiagnosis extends AnyFlatSpec with ChiselScalatestTester {

//   // ── helpers ────────────────────────────────────────────────────────────
//   def f32Bits(f: Float): Long  = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
//   def bitsF32(b: Long):  Float = java.lang.Float.intBitsToFloat(b.toInt)

//   def toFP16(f: Float): Int = {
//     val b = java.lang.Float.floatToRawIntBits(f)
//     val sign = (b >>> 31) & 0x1
//     val exp  = ((b >>> 23) & 0xFF) - 127 + 15
//     val man  = (b & 0x7FFFFF) >>> 13
//     (sign << 15) | (exp << 10) | man
//   }
//   def fromFP16(r: Int): Float = {
//     val sign = (r >>> 15) & 0x1
//     val exp  = ((r >>> 10) & 0x1F) - 15 + 127
//     val man  = (r & 0x3FF) << 13
//     bitsF32(((sign << 31) | (exp << 23) | man).toLong & 0xFFFFFFFFL)
//   }

//   def driveFP16(dut: OptPE, ia: Int, ib: Int, psumF: Float): Float = {
//     dut.io.mode.poke(true.B)
//     dut.io.in_a.poke((ia & 0xFFFF).U)
//     dut.io.in_b.poke((ib & 0xFFFF).U)
//     dut.io.psum_in.poke(f32Bits(psumF).U)
//     dut.clock.step()
//     bitsF32(dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL)
//   }

//   def ref(ia: Int, ib: Int, psum: Float): Float =
//     fromFP16(ia) * fromFP16(ib) + psum

//   /** Simulate the FP32-add normalization in software and return the
//    *  leading-one position of sum_man (bit index, 0-based from MSB of 25-bit word).
//    *  Returns -1 if sum_man == 0. */
//   def sumManLeadingOne(ia: Int, ib: Int, psumF: Float): Int = {
//     // --- replicate hardware mantissa alignment ---
//     val aF = fromFP16(ia); val bF = fromFP16(ib)

//     // product sign & exponent (mirrors hardware exactly)
//     val signA = (ia >>> 15) & 1;  val expA = (ia >>> 10) & 0x1F
//     val signB = (ib >>> 15) & 1;  val expB = (ib >>> 10) & 0x1F
//     val manA  = ((ia & 0x3FF) | 0x400).toLong   // 11-bit with implicit 1
//     val manB  = ((ib & 0x3FF) | 0x400).toLong
//     val rawMan22 = (manA * manB) & 0x3FFFFFL     // 22-bit raw mantissa

//     val expRaw = expA + expB                      // unbiased FP16 sum
//     val (mulMan23, mulExp8) =
//       if (((rawMan22 >>> 21) & 1) == 1)
//         (((rawMan22 & 0x1FFFFFL) << 2) | 0L, (expRaw + 98) & 0xFF)
//       else
//         (((rawMan22 & 0xFFFFFL) << 3)  | 0L, (expRaw + 97) & 0xFF)

//     val mulManFull = (1L << 23) | mulMan23   // 24-bit with implicit 1
//     val mulExp     = mulExp8

//     val psumBits = f32Bits(psumF)
//     val psumExp  = ((psumBits >>> 23) & 0xFF).toInt
//     val psumMan  = (psumBits & 0x7FFFFFL) | 0x800000L  // 24-bit

//     val (alignedMul, alignedPsum, commonExp) =
//       if (mulExp > psumExp)
//         (mulManFull, psumMan >>> (mulExp - psumExp), mulExp)
//       else if (psumExp > mulExp)
//         (mulManFull >>> (psumExp - mulExp), psumMan, psumExp)
//       else
//         (mulManFull, psumMan, mulExp)

//     val mulSign  = signA ^ signB
//     val psumSign = ((psumBits >>> 31) & 1).toInt

//     val sum25: Long =
//       if (mulSign == psumSign) alignedMul + alignedPsum
//       else if (alignedMul >= alignedPsum) alignedMul - alignedPsum
//       else alignedPsum - alignedMul

//     // find leading-one bit in 25-bit sum_man
//     if (sum25 == 0L) return -1
//     var pos = 24
//     while (pos >= 0 && ((sum25 >>> pos) & 1) == 0L) pos -= 1
//     pos   // 24 = sum_man(24), 23 = sum_man(23), ...
//   }

//   // ═══════════════════════════════════════════════════════════════════════
//   // DIAGNOSIS TEST
//   // ═══════════════════════════════════════════════════════════════════════

//   "OptPE FTZ Diagnosis" should "find and print all inputs hitting the fallback" in {
//     test(new OptPE()) { dut =>

//       val rng = new scala.util.Random(0xCAFE)

//       // leading-one histogram: index = bit position in sum_man[24:0]
//       // -1 bucket = true zero result
//       val histogram = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)

//       case class FailSample(aF: Float, bF: Float, psumF: Float,
//                             got: Float, r: Float, leadingOne: Int)
//       val failures = scala.collection.mutable.ArrayBuffer[FailSample]()

//       val N = 2000
//       for (_ <- 0 until N) {
//         // wide cancellation scenario — most likely to hit deep shift
//         val aAbs = 0.5f + rng.nextFloat() * 1.5f
//         val bAbs = 0.5f + rng.nextFloat() * 1.5f
//         val aF   = if (rng.nextBoolean()) aAbs else -aAbs
//         val bF   = if (rng.nextBoolean()) bAbs else -bAbs
//         val pF   = -(aF * bF) + (rng.nextFloat() - 0.5f) * 0.05f  // nearly cancels

//         val ia  = toFP16(aF); val ib = toFP16(bF)
//         val got = driveFP16(dut, ia, ib, pF)
//         val r   = ref(ia, ib, pF)
//         val lo  = sumManLeadingOne(ia, ib, pF)
//         histogram(lo) += 1

//         val isWrong = (r == 0.0f && got != 0.0f) ||
//                       (r != 0.0f && math.abs(got - r) / math.abs(r) > 0.01)
//         if (isWrong) failures += FailSample(aF, bF, pF, got, r, lo)
//       }

//       // ── print leading-one histogram ───────────────────────────────────
//       println("\n" + "=" * 64)
//       println("  sum_man leading-one histogram  (25-bit word, bit 24 = MSB)")
//       println("=" * 64)
//       println("  Bit  | Count | Hardware action")
//       println("  -----|-------|-------------------------------------------")
//       val actions = Map(
//         24 -> "overflow  → right-shift 1, exp+1",
//         23 -> "normal    → use as-is",
//         22 -> "shift-L 1 → exp-1",
//         21 -> "shift-L 2 → exp-2",
//         20 -> "shift-L 3 → exp-3",
//         -1 -> "true zero → exp=0, man=0"
//       )
//       for (bit <- (24 to -1 by -1)) {
//         val cnt = histogram(bit)
//         if (cnt > 0) {
//           val action = actions.getOrElse(bit, s"*** FALLBACK (shift-L ${24-bit}) — NOT HANDLED ***")
//           println(f"  $bit%3d  | $cnt%5d | $action")
//         }
//       }

//       // ── print failure samples ─────────────────────────────────────────
//       println(s"\n  Total failures (>1% rel error): ${failures.size} / $N")
//       if (failures.nonEmpty) {
//         println("\n  First 15 failing inputs:")
//         println("  a         b         psum      got       ref       leadingOne")
//         println("  --------- --------- --------- --------- --------- ----------")
//         failures.take(15).foreach { s =>
//           println(f"  ${s.aF}%9.4f ${s.bF}%9.4f ${s.psumF}%9.4f " +
//                   f"${s.got}%9.6f ${s.r}%9.6f  bit=${s.leadingOne}")
//         }
//       }

//       // ── what shift depth do you need? ─────────────────────────────────
//       val filteredBits = histogram.keys.filter(_ >= 0)
//       val minBitSeen = if (filteredBits.isEmpty) 24 else filteredBits.min
//       val neededShift = 24 - minBitSeen
//       println(s"\n  Deepest leading-one seen : bit $minBitSeen")
//       println(s"  Max left-shift needed    : $neededShift")
//       println(s"  Currently handled up to  : shift-left 3 (bit 20)")
//       if (neededShift > 3)
//         println(s"  *** ADD shift-left stages down to bit ${24 - neededShift} to fix remaining failures ***")
//       else
//         println(s"  Hardware coverage is sufficient for this input distribution.")
//       println("=" * 64 + "\n")

//       // test passes regardless — this is purely diagnostic
//       succeed
//     }
//   }
// }