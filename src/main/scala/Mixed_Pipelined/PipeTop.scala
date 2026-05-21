package Pipe

import chisel3._
import chisel3.util._
import common.SkewBuffers

// Top — timing-accurate wrapper for the pipelined systolic array.
//
// Skew analysis:
//   PipePE.out_a = ShiftRegister(in_a, 3) — 3-cycle horizontal hop
//   PipePE.out_b = ShiftRegister(in_b, 3) — 3-cycle vertical hop
//
//   For PE(i,j) to receive a valid (a,b) token on the SAME cycle:
//     in_a(i) must be delayed by  j * 3  cycles (hops right before reaching col j)
//     in_b(j) must be delayed by  i * 3  cycles (hops down  before reaching row i)
//
//   SkewBuffers delays signal i by i*step cycles.
//   So:
//     SkewBuffers for rows (in_a): step = 1  → delay = i*1
//       BUT in_a feeds the FIRST COLUMN only; horizontal propagation via out_a
//       adds 3 more cycles per hop. So the row skew only needs to stagger
//       which row gets its token first — step=1 is correct for rows.
//
//     SkewBuffers for cols (in_b): step = 1  → delay = j*1
//       BUT in_b feeds the FIRST ROW only; vertical propagation via out_b
//       adds 3 more cycles per hop. So col skew also only needs step=1.
//
//   The hardware skew from ShiftRegister(3) is accounted for automatically:
//     PE(0,0) gets in_a at cycle 0, PE(0,1) gets it via out_a at cycle 3.
//     PE(0,0) gets in_b at cycle 0, PE(1,0) gets it via out_b at cycle 3.
//
//   For tokens to meet at PE(i,j):
//     in_a(i) must enter col 0 at cycle  j*3        (so it exits out_a at cycle j*3+3 = (j+1)*3 → arrives at col j at cycle j*3)
//     in_b(j) must enter row 0 at cycle  i*3        (so it exits out_b at cycle i*3)
//
//   Therefore:
//     SkewBuffers A: delay row i by i*3 cycles  → ShiftRegister(inp(i), i*3)
//     SkewBuffers B: delay col j by j*3 cycles  → ShiftRegister(inp(j), j*3)
//
//   Standard SkewBuffers delays by i*1. We need i*PIPE_DEPTH.
//   Solution: use a StridedSkewBuffers that multiplies the delay by pipeDepth.

// Skew buffers with configurable stride (delay = i * stride cycles)
class StridedSkewBuffers(val n: Int, val width: Int, val stride: Int) extends Module {
  val io = IO(new Bundle {
    val res = Input(Bool())
    val en  = Input(Bool())
    val inp = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W)))
  })

  for (i <- 0 until n) {
    val safe_in = Mux(io.res, 0.U, io.inp(i))
    val delay   = i * stride
    if (delay == 0)
      io.out(i) := safe_in
    else
      io.out(i) := ShiftRegister(safe_in, delay, 0.U, io.en)
  }
}

class Top(val rows: Int, val cols: Int) extends Module {

  // PipePE pipeline depth — must match ShiftRegister depth in PipePE.out_a/out_b
  val PIPE_DEPTH = 3

  val io = IO(new Bundle {
    val res  = Input(Bool())
    val en   = Input(Bool())
    val mode = Input(Bool())               // false=INT8, true=FP16

    val row     = Input(Vec(rows, UInt(16.W)))   // A matrix column slice
    val col     = Input(Vec(cols, UInt(16.W)))   // B matrix row slice

    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  // Skew row inputs: in_a(i) delayed by i*PIPE_DEPTH cycles
  // so token for row i arrives at column j exactly when out_a
  // from the previous PE delivers it.
  val skew_a = Module(new StridedSkewBuffers(rows, 16, PIPE_DEPTH))
  skew_a.io.inp := io.row
  skew_a.io.en  := io.en
  skew_a.io.res := io.res

  // Skew col inputs: in_b(j) delayed by j*PIPE_DEPTH cycles
  val skew_b = Module(new StridedSkewBuffers(cols, 16, PIPE_DEPTH))
  skew_b.io.inp := io.col
  skew_b.io.en  := io.en
  skew_b.io.res := io.res

  // Systolic array — simple direct wiring, psum_in=0
  val sa = Module(new PipeSA(rows, cols))
  sa.io.in_a := skew_a.io.out
  sa.io.in_b := skew_b.io.out
  sa.io.res  := io.res
  sa.io.en   := io.en
  sa.io.mode := io.mode

  io.out_sum := sa.io.out_sum
}