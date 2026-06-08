// package Mixed
// import Mixed.TopMixedSA

// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec
// import scala.util.Random

// // ─────────────────────────────────────────────────────────────────
// //  SW reference model for the full SA
// // ─────────────────────────────────────────────────────────────────
// object SAModel {

//   // INT8 matmul: C[i][j] = sum_k A[i][k] * B[k][j]  (32-bit accumulation)
//   def int8Matmul(A: Seq[Seq[Int]], B: Seq[Seq[Int]]): Seq[Seq[Long]] = {
//     val rows = A.length
//     val cols = B.head.length
//     val k    = B.length
//     Seq.tabulate(rows, cols) { (i, j) =>
//       (0 until k).foldLeft(0L) { (acc, kk) =>
//         val a8 = A(i)(kk).toByte.toInt
//         val b8 = B(kk)(j).toByte.toInt
//         val prod16 = (a8 * b8) & 0xFFFF
//         (acc + prod16.toShort.toInt) & 0xFFFFFFFFL
//       }
//     }
//   }

//   // FP16 matmul: uses the same fp16ToDouble helper as the PE TB
//   def fp16ToDouble(bits: Int): Double = {
//     val s = (bits >> 15) & 1
//     val e = (bits >> 10) & 0x1F
//     val m =  bits        & 0x3FF
//     if (e == 0) return 0.0
//     val sign  = if (s == 1) -1.0 else 1.0
//     sign * (1 + m.toDouble / 1024.0) * math.pow(2.0, e - 15)
//   }

//   def fp32BitsToFloat(bits: Long): Float =
//     java.lang.Float.intBitsToFloat(bits.toInt)

//   def doubleToFp32Bits(v: Double): Long =
//     java.lang.Float.floatToIntBits(v.toFloat).toLong & 0xFFFFFFFFL

//   // FP16 matmul in SW (FP32 accumulation, matching HW precision)
//   def fp16Matmul(A: Seq[Seq[Int]], B: Seq[Seq[Int]]): Seq[Seq[Long]] = {
//     val rows = A.length
//     val cols = B.head.length
//     val k    = B.length
//     Seq.tabulate(rows, cols) { (i, j) =>
//       val result = (0 until k).foldLeft(0.0f) { (acc, kk) =>
//         acc + (fp16ToDouble(A(i)(kk)) * fp16ToDouble(B(kk)(j))).toFloat
//       }
//       doubleToFp32Bits(result.toDouble)
//     }
//   }
// }

// // ─────────────────────────────────────────────────────────────────
// //  Test suite
// // ─────────────────────────────────────────────────────────────────
// class TopMixedSATest extends AnyFlatSpec with ChiselScalatestTester {

//   val ROWS = 4
//   val COLS = 4

//   // ── helpers ──────────────────────────────────────────────────

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

//   def fp32Close(hw: Long, sw: Long, ulp: Int = 64): Boolean = {
//     val hwF = java.lang.Float.intBitsToFloat(hw.toInt)
//     val swF = java.lang.Float.intBitsToFloat(sw.toInt)
//     if (hwF.isNaN && swF.isNaN)         return true
//     if (hwF.isInfinite && swF.isInfinite) return hwF == swF
//     if (swF == 0.0f)                    return math.abs(hwF) < 1e-6f
//     math.abs(hwF - swF) <= ulp * math.ulp(swF)
//   }

//   // Drive the SA for one full matmul:
//   //   1. Reset
//   //   2. Feed rows/cols for `depth` cycles (skew already handled by HW)
//   //   3. Drain (rows-1 + cols-1 extra cycles for pipeline to flush)
//   //   4. Return the output grid
//   def runMatmul(
//     dut:      TopMixedSA,
//     rowsData: Seq[Seq[Int]],  // [rows][K] — activation vectors
//     colsData: Seq[Seq[Int]],  // [cols][K] — weight vectors (col-major)
//     mode:     Boolean,
//     depth:    Int             // K, inner dimension
//   ): Seq[Seq[Long]] = {

//     val rows   = rowsData.length
//     val cols   = colsData.length
//     val maxSkew = (rows - 1) + (cols - 1)
//     // Total cycles = K + maxSkew so every PE(i,j) sees exactly K valid MACs
//     val totalCycles = depth + maxSkew

//     // ── 1. Reset ──────────────────────────────────────────────
//     dut.io.res.poke(true.B)
//     dut.io.en.poke(false.B)
//     dut.io.mode.poke(mode.B)
//     dut.io.row.foreach(_.poke(0.U))
//     dut.io.col.foreach(_.poke(0.U))
//     dut.clock.step(2)
//     dut.io.res.poke(false.B)

//     // ── 2. Feed: K cycles of real data + maxSkew cycles of zeros ──
//     // The skew buffers align data so PE(i,j) sees cycle t at time t+i+j.
//     // Padding with zeros ensures PE(N-1,N-1) gets all K valid inputs.
//     dut.io.en.poke(true.B)
//     for (cycle <- 0 until totalCycles) {
//       for (i <- 0 until rows)
//         dut.io.row(i).poke((if (cycle < depth) rowsData(i)(cycle) else 0).U)
//       for (j <- 0 until cols)
//         dut.io.col(j).poke((if (cycle < depth) colsData(j)(cycle) else 0).U)
//       dut.clock.step(1)
//     }

//     // ── 3. Read outputs (accumulators already settled) ────────
//     Seq.tabulate(rows, cols) { (i, j) =>
//       dut.io.out_sum(i)(j).peek().litValue.toLong & 0xFFFFFFFFL
//     }
//   }



//   // ── INT8 tests ───────────────────────────────────────────────

//   behavior of "TopMixedSA INT8 mode"

//   it should "compute identity * identity = identity pattern" in {
//     // A = I (identity), B = I → C = I
//     // INT8 identity: diagonal = 1, rest = 0
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       // For a 4x4 matmul we feed K=4 depth
//       // A[i][k] = if i==k then 1 else 0
//       // B[k][j] = if k==j then 1 else 0
//       val K = 4
//       val rowsData = Seq.tabulate(ROWS, K)((i, k) => packInt8(if (i == k) 1 else 0))
//       val colsData = Seq.tabulate(COLS, K)((j, k) => packInt8(if (k == j) 1 else 0))

//       val hw = runMatmul(dut, rowsData, colsData, mode = false, depth = K)
//       val sw = SAModel.int8Matmul(rowsData, colsData.transpose)

//       for (i <- 0 until ROWS; j <- 0 until COLS) {
//         val expected = if (i == j) 1L else 0L
//         assert(hw(i)(j) == expected,
//           s"Identity[$i][$j]: hw=${hw(i)(j)} expected=$expected")
//         assert(sw(i)(j) == expected)
//       }
//     }
//   }

//   it should "compute all-ones * all-ones = K for each output" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4
//       val rowsData = Seq.fill(ROWS, K)(packInt8(1))
//       val colsData = Seq.fill(COLS, K)(packInt8(1))

//       val hw = runMatmul(dut, rowsData, colsData, mode = false, depth = K)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw(i)(j) == K.toLong,
//           s"AllOnes[$i][$j]: hw=${hw(i)(j)} expected=$K")
//     }
//   }

//   it should "compute a known 4x4 INT8 matmul correctly" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4
//       // A rows (activations): each row is a vector of length K
//       val A = Seq(
//         Seq(1, 2, 3, 4),
//         Seq(5, 6, 7, 8),
//         Seq(1, 0, 1, 0),
//         Seq(3, 3, 3, 3)
//       )
//       // B cols (weights): each col is a vector of length K
//       // B matrix (row-major): B[k][j]
//       val B = Seq(
//         Seq(1, 0, 0, 1),
//         Seq(0, 1, 0, 1),
//         Seq(0, 0, 1, 1),
//         Seq(1, 1, 1, 1)
//       )

//       val rowsData = A.map(_.map(packInt8))
//       val colsData = Seq.tabulate(COLS, K)((j, k) => packInt8(B(k)(j)))

//       val hw = runMatmul(dut, rowsData, colsData, mode = false, depth = K)
//       val sw = SAModel.int8Matmul(A, B)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw(i)(j) == (sw(i)(j) & 0xFFFFFFFFL),
//           s"INT8 matmul[$i][$j]: hw=${hw(i)(j)} sw=${sw(i)(j)}")
//     }
//   }

//   it should "handle negative INT8 values correctly" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4
//       val A = Seq(
//         Seq(-1, -2, -3, -4),
//         Seq( 5, -6,  7, -8),
//         Seq(-1,  0, -1,  0),
//         Seq( 3, -3,  3, -3)
//       )
//       val B = Seq(
//         Seq( 1,  2,  3,  4),
//         Seq(-1, -2, -3, -4),
//         Seq( 1, -1,  1, -1),
//         Seq(-2,  2, -2,  2)
//       )

//       val rowsData = A.map(_.map(packInt8))
//       val colsData = Seq.tabulate(COLS, K)((j, k) => packInt8(B(k)(j)))

//       val hw = runMatmul(dut, rowsData, colsData, mode = false, depth = K)
//       val sw = SAModel.int8Matmul(A, B)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw(i)(j) == (sw(i)(j) & 0xFFFFFFFFL),
//           s"NegINT8[$i][$j]: hw=${hw(i)(j)} sw=${sw(i)(j)}")
//     }
//   }

//   it should "reset accumulator between runs" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4
//       val ones = Seq.fill(ROWS, K)(packInt8(1))
//       val onesC = Seq.fill(COLS, K)(packInt8(1))

//       // First run
//       val hw1 = runMatmul(dut, ones, onesC, mode = false, depth = K)
//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw1(i)(j) == K.toLong, s"Run1[$i][$j]=${hw1(i)(j)}")

//       // Second run — reset is called inside runMatmul, results must be same
//       val hw2 = runMatmul(dut, ones, onesC, mode = false, depth = K)
//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw2(i)(j) == K.toLong,
//           s"Reset check[$i][$j]: hw2=${hw2(i)(j)} (should equal hw1=${hw1(i)(j)})")
//     }
//   }

//   it should "match SW model for a random 4x4 INT8 matmul" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val rng = new Random(7)
//       val K   = 4
//       val A   = Seq.fill(ROWS, K)(rng.nextInt(256))
//       val B   = Seq.fill(K, COLS)(rng.nextInt(256))

//       val rowsData = A.map(_.map(packInt8))
//       val colsData = Seq.tabulate(COLS, K)((j, k) => packInt8(B(k)(j)))

//       val hw = runMatmul(dut, rowsData, colsData, mode = false, depth = K)
//       val sw = SAModel.int8Matmul(A, B)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hw(i)(j) == (sw(i)(j) & 0xFFFFFFFFL),
//           s"RandINT8[$i][$j]: hw=${hw(i)(j)} sw=${sw(i)(j)}")
//     }
//   }

//   // ── FP16 tests ───────────────────────────────────────────────

//   behavior of "TopMixedSA FP16 mode"

//   it should "compute 1.0 * 1.0 across all outputs = K" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K    = 4
//       val one  = floatToFp16(1.0f)
//       val rowsData = Seq.fill(ROWS, K)(one)
//       val colsData = Seq.fill(COLS, K)(one)

//       val hw = runMatmul(dut, rowsData, colsData, mode = true, depth = K)

//       for (i <- 0 until ROWS; j <- 0 until COLS) {
//         val hwF = java.lang.Float.intBitsToFloat(hw(i)(j).toInt)
//         assert(math.abs(hwF - K.toFloat) < 0.1f,
//           s"FP16 ones[$i][$j]: hw=$hwF expected=$K")
//       }
//     }
//   }

//   it should "compute a known 4x4 FP16 matmul within tolerance" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4
//       val AF = Seq(
//         Seq(1.0f, 2.0f, 0.5f, 1.5f),
//         Seq(3.0f, 1.0f, 2.0f, 0.5f),
//         Seq(0.5f, 0.5f, 0.5f, 0.5f),
//         Seq(2.0f, 2.0f, 2.0f, 2.0f)
//       )
//       val BF = Seq(         // B[k][j]
//         Seq(1.0f, 0.0f, 0.0f, 1.0f),
//         Seq(0.0f, 1.0f, 0.0f, 1.0f),
//         Seq(0.0f, 0.0f, 1.0f, 1.0f),
//         Seq(1.0f, 1.0f, 1.0f, 1.0f)
//       )

//       val rowsData = AF.map(_.map(floatToFp16))
//       val colsData = Seq.tabulate(COLS, K)((j, k) => floatToFp16(BF(k)(j)))
//       val swA = AF.map(_.map(v => floatToFp16(v)))
//       val swB = Seq.tabulate(K, COLS)((k, j) => floatToFp16(BF(k)(j)))

//       val hw = runMatmul(dut, rowsData, colsData, mode = true, depth = K)
//       val sw = SAModel.fp16Matmul(swA, swB)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(fp32Close(hw(i)(j), sw(i)(j), ulp = 128),
//           f"FP16 matmul[$i][$j]: " +
//           f"hw=${java.lang.Float.intBitsToFloat(hw(i)(j).toInt)}%.4f " +
//           f"sw=${java.lang.Float.intBitsToFloat(sw(i)(j).toInt)}%.4f")
//     }
//   }

//   it should "produce zero output for zero inputs" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K    = 4
//       val zero = floatToFp16(0.0f)
//       val one  = floatToFp16(1.0f)
//       val rowsData = Seq.fill(ROWS, K)(zero)
//       val colsData = Seq.fill(COLS, K)(one)

//       val hw = runMatmul(dut, rowsData, colsData, mode = true, depth = K)

//       for (i <- 0 until ROWS; j <- 0 until COLS) {
//         val hwF = java.lang.Float.intBitsToFloat(hw(i)(j).toInt)
//         assert(math.abs(hwF) < 1e-6f, s"ZeroFP16[$i][$j]: hw=$hwF")
//       }
//     }
//   }

//   it should "match SW for random 4x4 FP16 matmul" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val rng = new Random(42)
//       val K   = 4

//       // Use small exponents to avoid FP32 overflow during accumulation
//       def randFp16(): Int = {
//         val exp  = rng.nextInt(10) + 10   // exp in [10..19] → values ~[1..512]
//         val sign = rng.nextInt(2)
//         val man  = rng.nextInt(1024)
//         (sign << 15) | (exp << 10) | man
//       }

//       val rowsData = Seq.fill(ROWS, K)(randFp16())
//       val colsData = Seq.fill(COLS, K)(randFp16())
//       val swB      = Seq.tabulate(K, COLS)((k, j) => colsData(j)(k))

//       val hw = runMatmul(dut, rowsData, colsData, mode = true, depth = K)
//       val sw = SAModel.fp16Matmul(rowsData, swB)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(fp32Close(hw(i)(j), sw(i)(j), ulp = 256),
//           f"RandFP16[$i][$j]: " +
//           f"hw=${java.lang.Float.intBitsToFloat(hw(i)(j).toInt)}%.4f " +
//           f"sw=${java.lang.Float.intBitsToFloat(sw(i)(j).toInt)}%.4f")
//     }
//   }

//   // ── Mode isolation ───────────────────────────────────────────

//   behavior of "TopMixedSA mode isolation"

//   it should "not corrupt outputs when switching from INT8 to FP16" in {
//     test(new TopMixedSA(ROWS, COLS)) { dut =>
//       val K = 4

//       // INT8 run: all ones → each output = K
//       val onesI = Seq.fill(ROWS, K)(packInt8(1))
//       val onesIc = Seq.fill(COLS, K)(packInt8(1))
//       val hwInt = runMatmul(dut, onesI, onesIc, mode = false, depth = K)

//       for (i <- 0 until ROWS; j <- 0 until COLS)
//         assert(hwInt(i)(j) == K.toLong, s"INT8 run[$i][$j]=${hwInt(i)(j)}")

//       // FP16 run on same DUT (reset is called inside runMatmul)
//       val one   = floatToFp16(1.0f)
//       val onesF = Seq.fill(ROWS, K)(one)
//       val onesFc = Seq.fill(COLS, K)(one)
//       val hwFp = runMatmul(dut, onesF, onesFc, mode = true, depth = K)

//       for (i <- 0 until ROWS; j <- 0 until COLS) {
//         val hwF = java.lang.Float.intBitsToFloat(hwFp(i)(j).toInt)
//         assert(math.abs(hwF - K.toFloat) < 0.5f,
//           s"FP16 after INT8[$i][$j]: hw=$hwF expected=$K")
//       }
//     }
//   }
// }