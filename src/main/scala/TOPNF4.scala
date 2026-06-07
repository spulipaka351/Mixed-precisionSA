package Pipe

import chisel3._
import chisel3.util._

// ─────────────────────────────────────────────────────────────────────────────
// NF4Top
//
// Drop-in sibling of Top that replaces the raw FP16 weight column input with
// packed NF4 + per-block FP16 scale inputs, performing on-the-fly
// dequantization before feeding the systolic array.
//
// Interface mapping vs. Top
// ──────────────────────────────────────────────────────────────────────────────
// Top.col  : Vec(cols, UInt(16.W))      ← raw FP16 weights, one per column
// NF4Top   : packed_nf4 + scale          ← NF4 weights, 4 per 16-bit word
//
// NF4 grouping
// ─────────────────────────────────────────────────────────────────────────────
// Each NF4Dequantizer unpacks 4 FP16 values from one 16-bit packed_nf4 word
// (nibbles [3:0], [7:4], [11:8], [15:12]) and scales them by one FP16 scale.
//
// To feed all `cols` columns of the systolic array each cycle we need
//   numDeq = ceil(cols / 4)   dequantizers per row.
//
// The last dequantizer's extra outputs (when cols % 4 != 0) are simply unused.
//
// If cols is not a multiple of 4, the final packed_nf4 word only needs to
// carry (cols % 4) valid nibbles; the upper nibbles are ignored.
//
// Timing
// ─────────────────────────────────────────────────────────────────────────────
// NF4Dequantizer: 0-cycle (combinational output, no internal register in the
//                 current implementation — the register sits at the skew buffer
//                 input via ShiftRegister in StridedSkewBuffers).
//
// If NF4Dequantizer is later made registered (latency = 1), add a
// compensating ShiftRegister of depth 1 on the row (in_a) path to re-align
// activations and weights before the skew buffers.
//
// Mode
// ─────────────────────────────────────────────────────────────────────────────
// NF4 weights dequantize to FP16, so this module is intended for FP16 mode
// (io.mode = true). The mode port is kept for composability and completeness.
// ─────────────────────────────────────────────────────────────────────────────

class NF4Top(val rows: Int, val cols: Int) extends Module {

  // Number of NF4Dequantizer instances needed to cover all weight columns.
  // Each dequantizer produces 4 FP16 outputs from one 16-bit packed word.
  val numDeq = (cols + 3) / 4   // ceil(cols / 4)

  // Pipeline depth of PipePE — must match ShiftRegister depth in PipePE
  val PIPE_DEPTH = 3

  val io = IO(new Bundle {
    val res       = Input(Bool())
    val en        = Input(Bool())
    val mode      = Input(Bool())   // false=INT8, true=FP16 (intended: true for NF4)
    val load_bias = Input(Bool())
    val bias      = Input(Vec(cols, UInt(32.W)))

    // Activation inputs (A matrix column slice) — same as Top
    val row = Input(Vec(rows, UInt(16.W)))

    // Weight inputs as packed NF4 — one word per group-of-4 columns
    // packed_nf4(k): nibbles → columns 4k, 4k+1, 4k+2, 4k+3
    // scale(k)     : FP16 block scale shared by all 4 weights in word k
    val packed_nf4 = Input(Vec(numDeq, UInt(16.W)))
    val scale      = Input(Vec(numDeq, UInt(16.W)))

    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  // ── Step 1: Dequantize NF4 weights → FP16 ──────────────────────────────────
  // Instantiate numDeq dequantizers; wire packed_nf4 and scale directly.
  // NF4Dequantizer is combinational (no output register), so dequant_out
  // is available in the same cycle as the inputs.

  val deq = Seq.fill(numDeq)(Module(new NF4Dequantizer()))

  // Flattened FP16 weight bus: deq_fp16_flat(j) is the FP16 weight for column j
  // We only use the first `cols` entries; extras (if cols % 4 != 0) are dropped.
  val deq_fp16_flat = Wire(Vec(numDeq * 4, UInt(16.W)))

  for (k <- 0 until numDeq) {
    deq(k).io.packed_nf4 := io.packed_nf4(k)
    deq(k).io.scale      := io.scale(k)
    for (n <- 0 until 4) {
      deq_fp16_flat(k * 4 + n) := deq(k).io.out(n)
    }
  }

  // Slice to exactly `cols` FP16 weight values
  val col_fp16 = Wire(Vec(cols, UInt(16.W)))
  for (j <- 0 until cols) {
    col_fp16(j) := deq_fp16_flat(j)
  }

  // ── Step 2: Skew activation inputs (row) ───────────────────────────────────
  // Identical to Top: row i is delayed by i * PIPE_DEPTH cycles.
  val skew_a = Module(new StridedSkewBuffers(rows, 16, PIPE_DEPTH))
  skew_a.io.inp := io.row
  skew_a.io.en  := io.en
  skew_a.io.res := io.res

  // ── Step 3: Skew weight inputs (col_fp16) ─────────────────────────────────
  // Weight column j is delayed by j * PIPE_DEPTH cycles.
  val skew_b = Module(new StridedSkewBuffers(cols, 16, PIPE_DEPTH))
  skew_b.io.inp := col_fp16
  skew_b.io.en  := io.en
  skew_b.io.res := io.res

  // ── Step 4: Systolic array ─────────────────────────────────────────────────
  val sa = Module(new PipeSA(rows, cols))
  sa.io.in_a      := skew_a.io.out
  sa.io.in_b      := skew_b.io.out
  sa.io.res       := io.res
  sa.io.en        := io.en
  sa.io.mode      := io.mode
  sa.io.load_bias := io.load_bias
  sa.io.bias      := io.bias

  io.out_sum := sa.io.out_sum
}