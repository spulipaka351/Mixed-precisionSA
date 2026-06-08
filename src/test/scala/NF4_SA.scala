package Pipe

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// ─────────────────────────────────────────────────────────────────────────────
// NF4TopTest  — NF4 (mode=2), FP16 (mode=1), INT8 (mode=0) tests sharing all protocol code
// ─────────────────────────────────────────────────────────────────────────────

class NF4TopTest extends AnyFlatSpec with ChiselScalatestTester {

  // ══════════════════════════════════════════════════════════════════════════
  // FP helpers
  // ══════════════════════════════════════════════════════════════════════════

  def toFP16(f: Float): Long = {
    val b = java.lang.Float.floatToRawIntBits(f)
    val e = ((b >>> 23) & 0xFF) - 127 + 15
    (((b >>> 31) & 1) << 15 | (e.max(0).min(31) << 10) | ((b >>> 13) & 0x3FF)).toLong & 0xFFFFL
  }
  def toFP32(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  def toFloat(bits: Long): Float = {
    java.lang.Float.intBitsToFloat(
      (if (bits > 0x7FFFFFFFL) bits - 0x100000000L else bits).toInt)
  }
  def toSigned32(bits: Long): Int =
    (if (bits > 0x7FFFFFFFL) bits - 0x100000000L else bits).toInt

  // ══════════════════════════════════════════════════════════════════════════
  // File loaders
  // ══════════════════════════════════════════════════════════════════════════

  def lines(path: String): Iterator[String] =
    scala.io.Source.fromFile(path).getLines().filterNot(_.trim.isEmpty)

  def loadFloatMatrix(path: String): Array[Array[Float]] =
    lines(path).map(_.trim.split("\\s+").map(_.toFloat)).toArray

  def loadIntMatrix(path: String): Array[Array[Int]] =
    lines(path).map(_.trim.split("\\s+").map(_.toInt)).toArray

  def loadFloat1D(path: String): Array[Float] =
    lines(path).flatMap(_.trim.split("\\s+").map(_.toFloat)).toArray

  def loadInt1D(path: String): Array[Int] =
    lines(path).flatMap(_.trim.split("\\s+").map(_.toInt)).toArray

  // ══════════════════════════════════════════════════════════════════════════
  // Packing helpers
  // ══════════════════════════════════════════════════════════════════════════

  def packNF4(i0: Int, i1: Int, i2: Int, i3: Int): Long =
    ((i3 & 0xF).toLong << 12) | ((i2 & 0xF).toLong << 8) |
    ((i1 & 0xF).toLong <<  4) |  (i0 & 0xF).toLong

  // ══════════════════════════════════════════════════════════════════════════
  // Shared timing constants
  // ══════════════════════════════════════════════════════════════════════════

  val PIPE_DEPTH = 3
  def maxSkew(rows: Int)    = (rows - 1) * PIPE_DEPTH
  def gap(rows: Int)        = PIPE_DEPTH + maxSkew(rows)
  def totalCycles(K: Int, rows: Int) = K * gap(rows) + maxSkew(rows)

  // ══════════════════════════════════════════════════════════════════════════
  // Shared tile-loop driver
  //
  // Drives reset → bias load → stream → drain for one tile and returns the
  // raw 32-bit output words.  Caller supplies:
  //   pokeWeights : (dut, cycle c, driving flag, tileCol base, tileCols) => Unit
  //   pokeRow     : (dut, row i,   cycle c,      driving flag, tileRow base, tileRows) => Unit
  //   pokeBias    : (dut, col j,   tileCol base) => Unit
  //   mode        : UInt literal to poke every cycle
  // ══════════════════════════════════════════════════════════════════════════

  def runTile(
      dut       : NF4Top,
      rows      : Int,
      cols      : Int,
      K         : Int,
      tileRows  : Int,
      tileCols  : Int,
      mode      : chisel3.UInt,
      pokeBias  : (NF4Top, Int) => Unit,               // (dut, colJ)
      pokeRow   : (NF4Top, Int, Int, Boolean) => Unit, // (dut, rowI, k, driving)
      pokeWeight: (NF4Top, Int, Boolean) => Unit        // (dut, cycleC, driving)
  ): Array[Array[Long]] = {

    val numDeq = (cols + 3) / 4

    // ── Reset ────────────────────────────────────────────────────────────────
    dut.io.res.poke(true.B)
    dut.io.en.poke(false.B)
    dut.io.mode.poke(mode)
    dut.io.load_bias.poke(false.B)
    for (i <- 0 until rows)  dut.io.row(i).poke(0.U)
    for (j <- 0 until cols)  dut.io.col(j).poke(0.U)
    for (d <- 0 until numDeq){ dut.io.packed_nf4(d).poke(0.U); dut.io.scale(d).poke(0.U) }
    for (j <- 0 until cols)  pokeBias(dut, j)
    dut.clock.step(1)
    dut.io.res.poke(false.B)

    // ── Stream ───────────────────────────────────────────────────────────────
    val tc = totalCycles(K, rows)
    for (c <- 0 until tc) {
      val k       = c / gap(rows)
      val driving = (c % gap(rows)) < PIPE_DEPTH && k < K
      dut.io.en.poke(true.B)
      dut.io.mode.poke(mode)
      dut.io.load_bias.poke((c < PIPE_DEPTH).B)
      for (i <- 0 until rows)  pokeRow(dut, i, k, driving)
      pokeWeight(dut, c, driving)
      dut.clock.step(1)
    }

    // ── Drain ────────────────────────────────────────────────────────────────
    dut.io.en.poke(false.B)
    dut.io.load_bias.poke(false.B)
    for (i <- 0 until rows)  dut.io.row(i).poke(0.U)
    for (j <- 0 until cols)  dut.io.col(j).poke(0.U)
    for (d <- 0 until numDeq){ dut.io.packed_nf4(d).poke(0.U); dut.io.scale(d).poke(0.U) }

    // Capture: (i+j) even at drain+1, odd at drain+3
    val raw = Array.ofDim[Long](rows, cols)
    dut.clock.step(1)
    for (i <- 0 until tileRows; j <- 0 until tileCols if (i + j) % 2 == 0)
      raw(i)(j) = dut.io.out_sum(i)(j).peek().litValue.toLong
    dut.clock.step(2)
    for (i <- 0 until tileRows; j <- 0 until tileCols if (i + j) % 2 == 1)
      raw(i)(j) = dut.io.out_sum(i)(j).peek().litValue.toLong
    raw
  }

  // ══════════════════════════════════════════════════════════════════════════
  // NF4 test  (mode = 2)
  // ══════════════════════════════════════════════════════════════════════════

  "NF4Top RTL dequantizer" should "match Python NF4 reference output" in {
    test(new NF4Top(4, 4))
      .withAnnotations(Seq(TreadleBackendAnnotation))({ dut =>

      val rows = 4; val cols = 4
      val numDeq = (cols + 3) / 4
      val base   = "src/test/scala/nf4_test_files/"

      val A       = loadFloatMatrix(s"${base}A.txt")
      val Bidx    = loadIntMatrix  (s"${base}B.txt")
      val Bscales = loadFloat1D    (s"${base}B_scales.txt")
      val biasM   = loadFloatMatrix(s"${base}bias.txt")
      val Xref    = loadFloatMatrix(s"${base}X.txt")
      val M = A.length; val K = A(0).length; val N = Bidx(0).length

      println(f"[NF4] M=$M K=$K N=$N  A(0)(0)=${A(0)(0)}%+.4f  X(0)(0)=${Xref(0)(0)}%+.4f")

      val C_out = Array.ofDim[Float](M, N)

      for (tileRow <- 0 until math.ceil(M.toDouble / rows).toInt;
           tileCol <- 0 until math.ceil(N.toDouble / cols).toInt) {

        val rBase = tileRow * rows;  val cBase = tileCol * cols
        val tR    = math.min(rows, M - rBase)
        val tC    = math.min(cols, N - cBase)

        val raw = runTile(
          dut, rows, cols, K, tR, tC,
          mode       = 2.U,
          pokeBias   = (d, j) => {
            val bVal = if ((cBase + j) < N) biasM(0)(cBase + j) else 0f
            d.io.bias(j).poke(toFP32(bVal).U)
          },
          pokeRow    = (d, i, k, driving) =>
            d.io.row(i).poke(
              (if (driving && i < tR) toFP16(A(rBase + i)(k)) else 0L).U),
          pokeWeight = (d, c, driving) => {
            val k = c / gap(rows)
            for (de <- 0 until numDeq) {
              if (driving) {
                def idx(n: Int) = { val col = cBase + de*4+n; if (n < tC && col < N) Bidx(k)(col) else 0 }
                d.io.packed_nf4(de).poke(packNF4(idx(0), idx(1), idx(2), idx(3)).U)
                d.io.scale(de).poke(toFP16(Bscales(k)).U)
              } else {
                d.io.packed_nf4(de).poke(0.U)
                d.io.scale(de).poke(0.U)
              }
            }
          }
        )

        for (i <- 0 until tR; j <- 0 until tC)
          C_out(rBase + i)(cBase + j) = toFloat(raw(i)(j))
      }

      // ── Error check ─────────────────────────────────────────────────────────
      var maxAbs = 0f; var trulyWrong = 0; var nearZero = 0
      for (i <- 0 until M; j <- 0 until N) {
        val abs = math.abs(C_out(i)(j) - Xref(i)(j))
        val rel = if (math.abs(Xref(i)(j)) > 1e-3f) abs / math.abs(Xref(i)(j)) else 0f
        if (abs > maxAbs) maxAbs = abs
        if (rel > 0.01f) {
          if (math.abs(Xref(i)(j)) < 0.5f) nearZero += 1 else trulyWrong += 1
          if (trulyWrong <= 5)
            println(f"  BAD ($i,$j): hw=${C_out(i)(j)}%.4f  ref=${Xref(i)(j)}%.4f")
        }
      }
      println(f"[NF4] maxAbsErr=$maxAbs%.4f  nearZero=$nearZero  trulyWrong=$trulyWrong")
      println("[NF4] HW:"); for (r <- C_out) println(r.map(v => f"$v%8.4f").mkString(" "))
      println("[NF4] Ref:"); for (r <- Xref)  println(r.map(v => f"$v%8.4f").mkString(" "))
      assert(trulyWrong == 0, s"$trulyWrong cell(s) >1% error (maxAbs=$maxAbs)")
    })
  }

  // ══════════════════════════════════════════════════════════════════════════
  // INT8 test  (mode = 0)
  // ══════════════════════════════════════════════════════════════════════════

  "NF4Top RTL in INT8 mode" should "match Python INT8 reference output" in {
    test(new NF4Top(4, 4))
      .withAnnotations(Seq(TreadleBackendAnnotation))({ dut =>

      val rows = 4; val cols = 4
      val base = "src/test/scala/INT8_test_files/"

      val A    = loadIntMatrix(s"${base}A.txt")
      val B    = loadIntMatrix(s"${base}B.txt")
      val bias = loadInt1D    (s"${base}bias.txt")
      val Xref = loadIntMatrix(s"${base}X.txt")
      val M = A.length; val K = A(0).length; val N = B(0).length

      println(f"[INT8] M=$M K=$K N=$N  A(0)=${A(0).toSeq}  X(0)=${Xref(0).toSeq}")

      val C_out = Array.ofDim[Int](M, N)

      for (tileRow <- 0 until math.ceil(M.toDouble / rows).toInt;
           tileCol <- 0 until math.ceil(N.toDouble / cols).toInt) {

        val rBase = tileRow * rows;  val cBase = tileCol * cols
        val tR    = math.min(rows, M - rBase)
        val tC    = math.min(cols, N - cBase)

        val raw = runTile(
          dut, rows, cols, K, tR, tC,
          mode       = 0.U,
          pokeBias   = (d, j) => {
            val bVal = if ((cBase + j) < N) bias(cBase + j) else 0
            d.io.bias(j).poke((bVal.toLong & 0xFFFFFFFFL).U)
          },
          pokeRow    = (d, i, k, driving) => {
            val v = if (driving && i < tR) A(rBase + i)(k) else 0
            d.io.row(i).poke((v & 0xFF).U)
          },
          pokeWeight = (d, c, driving) => {
            val k = c / gap(rows)
            for (j <- 0 until cols) {
              val col = cBase + j
              val w = if (driving && j < tC && col < N) B(k)(col) else 0
              d.io.col(j).poke((w & 0xFF).U)
            }
          }
        )

        for (i <- 0 until tR; j <- 0 until tC)
          C_out(rBase + i)(cBase + j) = toSigned32(raw(i)(j))
      }

      // ── Error check ─────────────────────────────────────────────────────────
      var maxAbs = 0; var wrong = 0
      for (i <- 0 until M; j <- 0 until N) {
        val abs = math.abs(C_out(i)(j) - Xref(i)(j))
        if (abs > maxAbs) maxAbs = abs
        if (abs > 0) { wrong += 1
          if (wrong <= 8) println(f"  BAD ($i,$j): hw=${C_out(i)(j)}  ref=${Xref(i)(j)}") }
      }
      println(f"[INT8] maxAbsErr=$maxAbs  wrong=$wrong/${M*N}")
      println("[INT8] HW:"); for (r <- C_out) println(r.map(v => f"$v%8d").mkString(" "))
      println("[INT8] Ref:"); for (r <- Xref)  println(r.map(v => f"$v%8d").mkString(" "))
      assert(maxAbs == 0, s"$wrong cell(s) mismatch (maxAbs=$maxAbs)")
    })
  }

  // ══════════════════════════════════════════════════════════════════════════
  // FP16 test  (mode = 1)
  //
  // Weights are raw FP16 — no NF4 dequantizer, no packing.
  // col(j) carries the FP16 bit pattern directly; packed_nf4/scale = 0.
  // Bias is FP32 (same as NF4 test).
  // Reference: C = A_fp16 @ B_fp16 + bias  (computed in Python with ml_dtypes)
  // Tolerance: same as NF4 — FP16 rounding noise, <1% on non-near-zero cells.
  // ══════════════════════════════════════════════════════════════════════════

  "NF4Top RTL in FP16 mode" should "match Python FP16 reference output" in {
    test(new NF4Top(4, 4))
      .withAnnotations(Seq(TreadleBackendAnnotation))({ dut =>

      val rows = 4; val cols = 4
      val base = "src/test/scala/fp16_test_files/"

      val A    = loadFloatMatrix(s"${base}A.txt")    // M×K  float32 in file
      val B    = loadFloatMatrix(s"${base}B.txt")    // K×N  float32 in file (FP16-rounded values)
      val bias = loadFloat1D    (s"${base}bias.txt") // N    float32 in file
      val Xref = loadFloatMatrix(s"${base}X.txt")    // M×N  FP32 reference output
      val M = A.length; val K = A(0).length; val N = B(0).length

      println(f"[FP16] M=$M K=$K N=$N  A(0)(0)=${A(0)(0)}%+.4f  X(0)(0)=${Xref(0)(0)}%+.4f")

      val C_out = Array.ofDim[Float](M, N)

      for (tileRow <- 0 until math.ceil(M.toDouble / rows).toInt;
           tileCol <- 0 until math.ceil(N.toDouble / cols).toInt) {

        val rBase = tileRow * rows;  val cBase = tileCol * cols
        val tR    = math.min(rows, M - rBase)
        val tC    = math.min(cols, N - cBase)

        val raw = runTile(
          dut, rows, cols, K, tR, tC,
          mode       = 1.U,
          pokeBias   = (d, j) => {
            val bVal = if ((cBase + j) < N) bias(cBase + j) else 0f
            d.io.bias(j).poke(toFP32(bVal).U)
          },
          pokeRow    = (d, i, k, driving) =>
            d.io.row(i).poke(
              (if (driving && i < tR) toFP16(A(rBase + i)(k)) else 0L).U),
          pokeWeight = (d, c, driving) => {
            val k = c / gap(rows)
            // Drive raw FP16 weight bits on col(j); packed_nf4/scale unused
            for (j <- 0 until cols) {
              val col = cBase + j
              val w = if (driving && j < tC && col < N) toFP16(B(k)(col)) else 0L
              d.io.col(j).poke(w.U)
            }
            // NF4 path: don't-care, tie to 0
            val numDeq = (cols + 3) / 4
            for (de <- 0 until numDeq) {
              d.io.packed_nf4(de).poke(0.U)
              d.io.scale(de).poke(0.U)
            }
          }
        )

        for (i <- 0 until tR; j <- 0 until tC)
          C_out(rBase + i)(cBase + j) = toFloat(raw(i)(j))
      }

      // ── Error check (same thresholds as NF4 test) ───────────────────────────
      var maxAbs = 0f; var trulyWrong = 0; var nearZero = 0
      for (i <- 0 until M; j <- 0 until N) {
        val abs = math.abs(C_out(i)(j) - Xref(i)(j))
        val rel = if (math.abs(Xref(i)(j)) > 1e-3f) abs / math.abs(Xref(i)(j)) else 0f
        if (abs > maxAbs) maxAbs = abs
        if (rel > 0.01f) {
          if (math.abs(Xref(i)(j)) < 0.5f) nearZero += 1 else trulyWrong += 1
          if (trulyWrong <= 5)
            println(f"  BAD ($i,$j): hw=${C_out(i)(j)}%.4f  ref=${Xref(i)(j)}%.4f")
        }
      }
      println(f"[FP16] maxAbsErr=$maxAbs%.4f   nearZero=$nearZero  trulyWrong=$trulyWrong")
      println("[FP16] HW:"); for (r <- C_out) println(r.map(v => f"$v%8.4f").mkString(" "))
      println("[FP16] Ref:"); for (r <- Xref)  println(r.map(v => f"$v%8.4f").mkString(" "))
      assert(trulyWrong == 0, s"$trulyWrong cell(s) >1% error (maxAbs=$maxAbs)")
    })
  }
}