// package Mixed_opt

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class OptPESpec extends AnyFlatSpec with ChiselScalatestTester {

//   // ── Bit-level helpers ──────────────────────────────────────────────────

//   /** Pack a Float into its IEEE-754 32-bit representation (as Long, unsigned). */
//   def f32Bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL

//   /** Unpack IEEE-754 32-bit bits (as Long) back to Float. */
//   def bitsF32(bits: Long): Float = java.lang.Float.intBitsToFloat(bits.toInt)

//   /** Convert a Float to its FP16 raw bits (Int, lower 16 bits used). */
//   def toFP16(f: Float): Int = {
//     val b = java.lang.Float.floatToRawIntBits(f)
//     val sign    = (b >>> 31) & 0x1
//     val exp32   = (b >>> 23) & 0xFF
//     val man32   = b & 0x7FFFFF
//     val exp16   = exp32 - 127 + 15
//     val man16   = man32 >>> 13
//     (sign << 15) | (exp16 << 10) | man16
//   }

//   /** Convert FP16 raw bits back to Float. */
//   def fromFP16(raw: Int): Float = {
//     val sign  = (raw >>> 15) & 0x1
//     val exp16 = (raw >>> 10) & 0x1F
//     val man16 = raw & 0x3FF
//     val exp32 = exp16 - 15 + 127
//     val bits  = (sign << 31) | (exp32 << 23) | (man16 << 13)
//     java.lang.Float.intBitsToFloat(bits)
//   }

//   // ── INT8 helpers ───────────────────────────────────────────────────────

//   def int8Mac(a: Int, b: Int, psum: Int): Int = a * b + psum

//   def driveInt8(dut: OptPE, a: Int, b: Int, psum: Int): Int = {
//     dut.io.mode.poke(false.B)
//     dut.io.in_a.poke((a & 0xFF).U)
//     dut.io.in_b.poke((b & 0xFF).U)
//     dut.io.psum_in.poke((psum & 0xFFFFFFFFL).U)
//     dut.clock.step()
//     // sign-extend 32-bit unsigned output back to signed Int
//     dut.io.out.peek().litValue.toLong.toInt
//   }

//   // ── FP16 helpers ───────────────────────────────────────────────────────

//   def fp16Ref(ia: Int, ib: Int, psum: Float): Float =
//     fromFP16(ia).toFloat * fromFP16(ib).toFloat + psum

//   def driveFP16(dut: OptPE, ia: Int, ib: Int, psumF: Float): Float = {
//     dut.io.mode.poke(true.B)
//     dut.io.in_a.poke((ia & 0xFFFF).U)
//     dut.io.in_b.poke((ib & 0xFFFF).U)
//     dut.io.psum_in.poke(f32Bits(psumF).U)
//     dut.clock.step()
//     bitsF32(dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL)
//   }

//   def relErr(got: Float, ref: Float): Float = {
//     val abs = math.abs(got - ref).toFloat
//     if (math.abs(ref) < 1e-7f) abs else abs / math.abs(ref)
//   }

//   val TOL = 0.05f  // 5% relative tolerance for FP16 results

//   // ═══════════════════════════════════════════════════════════════════════
//   // INT8 TESTS
//   // ═══════════════════════════════════════════════════════════════════════

//   "OptPE INT8" should "compute a basic positive × positive MAC" in {
//     test(new OptPE()) { dut =>
//       val got  = driveInt8(dut, 3, 5, 0)
//       val gold = int8Mac(3, 5, 0)
//       assert(got == gold, s"3×5+0: got=$got gold=$gold")
//     }
//   }

//   it should "handle negative × positive (sign combinations)" in {
//     test(new OptPE()) { dut =>
//       for ((a, b) <- Seq((-7, 5), (7, -5), (-7, -5))) {
//         val got  = driveInt8(dut, a, b, 0)
//         val gold = int8Mac(a, b, 0)
//         assert(got == gold, s"$a×$b+0: got=$got gold=$gold")
//       }
//     }
//   }

//   it should "accumulate correctly across three sequential MACs" in {
//     test(new OptPE()) { dut =>
//       var acc  = 0
//       var gold = 0
//       for ((a, b) <- Seq((3, 5), (-2, 4), (7, -3))) {
//         acc  = driveInt8(dut, a, b, acc)
//         gold = int8Mac(a, b, gold)
//       }
//       assert(acc == gold, s"3-step INT8 accumulation: got=$acc gold=$gold")
//     }
//   }

//   it should "handle INT8 boundary values (127×127, -128×-128)" in {
//     test(new OptPE()) { dut =>
//       assert(driveInt8(dut, 127, 127, 0)   == int8Mac(127, 127, 0))
//       assert(driveInt8(dut, -128, -128, 0) == int8Mac(-128, -128, 0))
//       assert(driveInt8(dut, 127, -128, 0)  == int8Mac(127, -128, 0))
//     }
//   }

//   // ═══════════════════════════════════════════════════════════════════════
//   // FP16 TESTS
//   // ═══════════════════════════════════════════════════════════════════════

//   "OptPE FP16" should "compute 1.0 × 1.0 + 0 exactly" in {
//     test(new OptPE()) { dut =>
//       val ia  = toFP16(1.0f)
//       val ib  = toFP16(1.0f)
//       val got = driveFP16(dut, ia, ib, 0.0f)
//       val ref = fp16Ref(ia, ib, 0.0f)
//       assert(got == ref, s"1.0×1.0+0: got=$got ref=$ref")
//     }
//   }

//   it should "produce a negative result for positive × negative inputs" in {
//     test(new OptPE()) { dut =>
//       val ia  = toFP16(1.5f)
//       val ib  = toFP16(-2.0f)
//       val got = driveFP16(dut, ia, ib, 0.0f)
//       val ref = fp16Ref(ia, ib, 0.0f)
//       assert(got < 0, s"1.5×(-2.0) should be negative, got=$got")
//       assert(relErr(got, ref) < TOL, s"relErr=${relErr(got, ref)} ref=$ref got=$got")
//     }
//   }

//   it should "trigger mul_norm overflow branch (raw_man(21)==1) with ~2.0×2.0" in {
//     test(new OptPE()) { dut =>
//       val ia  = toFP16(1.999f)
//       val ib  = toFP16(1.999f)
//       val got = driveFP16(dut, ia, ib, 0.0f)
//       val ref = fp16Ref(ia, ib, 0.0f)
//       assert(relErr(got, ref) < TOL, s"overflow branch: got=$got ref=$ref")
//     }
//   }

//   it should "trigger FP32-add overflow normalization by accumulating into large psum" in {
//     test(new OptPE()) { dut =>
//       val ia    = toFP16(1.5f)
//       val ib    = toFP16(1.5f)
//       val psumF = fp16Ref(ia, ib, 0.0f)   // feed previous result as psum
//       val got   = driveFP16(dut, ia, ib, psumF)
//       val ref   = fp16Ref(ia, ib, psumF)
//       assert(relErr(got, ref) < TOL, s"add-overflow branch: got=$got ref=$ref")
//     }
//   }

//   it should "handle partial cancellation (left-shift normalization)" in {
//     test(new OptPE()) { dut =>
//       // 1.0×1.0 + (-1.5) → small positive, forces left-shift in normalization
//       val ia  = toFP16(1.0f)
//       val ib  = toFP16(1.0f)
//       val got = driveFP16(dut, ia, ib, -1.5f)
//       val ref = fp16Ref(ia, ib, -1.5f)
//       assert(relErr(got, ref) < TOL, s"cancellation: got=$got ref=$ref")
//     }
//   }

//   // ═══════════════════════════════════════════════════════════════════════
//   // MODE ISOLATION + PASS-THROUGH
//   // ═══════════════════════════════════════════════════════════════════════

//   "OptPE" should "produce different outputs for INT8 vs FP16 on the same raw bits" in {
//     test(new OptPE()) { dut =>
//       val raw = 0x0303

//       dut.io.mode.poke(false.B)
//       dut.io.in_a.poke(raw.U); dut.io.in_b.poke(raw.U); dut.io.psum_in.poke(0.U)
//       dut.clock.step()
//       val intOut = dut.io.out.peek().litValue

//       dut.io.mode.poke(true.B)
//       dut.io.in_a.poke(raw.U); dut.io.in_b.poke(raw.U); dut.io.psum_in.poke(0.U)
//       dut.clock.step()
//       val fpOut = dut.io.out.peek().litValue

//       assert(intOut != fpOut, s"Mode mux broken: both modes returned $intOut")
//     }
//   }

//   it should "pass in_a and in_b through out_a / out_b with 1-cycle delay" in {
//     test(new OptPE()) { dut =>
//       dut.io.mode.poke(false.B)
//       dut.io.in_a.poke(0xABCD.U)
//       dut.io.in_b.poke(0x1234.U)
//       dut.io.psum_in.poke(0.U)
//       dut.clock.step()
//       assert(dut.io.out_a.peek().litValue == 0xABCD,
//         s"out_a pass-through failed: ${dut.io.out_a.peek().litValue}")
//       assert(dut.io.out_b.peek().litValue == 0x1234,
//         s"out_b pass-through failed: ${dut.io.out_b.peek().litValue}")
//     }
//   }
// }