package Pipe

import chisel3._
import chisel3.util._


class NF4Top(val rows: Int, val cols: Int) extends Module {

  val numDeq     = (cols + 3) / 4
  val PIPE_DEPTH = 3

  val io = IO(new Bundle {
    val res       = Input(Bool())
    val en        = Input(Bool())
    val mode      = Input(UInt(2.W))   // 0=INT8, 1=FP16, 2=NF4
    val load_bias = Input(Bool())
    val bias      = Input(Vec(cols, UInt(32.W)))

    // Activation inputs — 16-bit; lower 8 bits used in INT8 mode
    val row = Input(Vec(rows, UInt(16.W)))

    // Unified weight column port
    val col = Input(Vec(cols, UInt(16.W)))

    // NF4-only inputs — tie to 0 for modes 0 and 1
    val packed_nf4 = Input(Vec(numDeq, UInt(16.W)))
    val scale      = Input(Vec(numDeq, UInt(16.W)))

    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val deq           = Seq.fill(numDeq)(Module(new NF4Dequantizer()))
  val deq_fp16_flat = Wire(Vec(numDeq * 4, UInt(16.W)))

  for (k <- 0 until numDeq) {
    deq(k).io.packed_nf4 := io.packed_nf4(k)
    deq(k).io.scale      := io.scale(k)
    for (n <- 0 until 4)
      deq_fp16_flat(k * 4 + n) := deq(k).io.out(n)
  }

  val col_nf4 = Wire(Vec(cols, UInt(16.W)))
  for (j <- 0 until cols)
    col_nf4(j) := deq_fp16_flat(j)

  // ── Step 2: Unified weight mux 
  val col_skew_in = Wire(Vec(cols, UInt(16.W)))
  for (j <- 0 until cols) {
    col_skew_in(j) := MuxCase(
      Cat(0.U(8.W), io.col(j)(7, 0)),    // default (INT8, mode=0)
      Seq(
        (io.mode === 2.U) -> col_nf4(j), // NF4:  dequantized FP16
        (io.mode === 1.U) -> io.col(j)   // FP16: pass-through
      )
    )
  }

  
  val skew_a = Module(new StridedSkewBuffers(rows, 16, PIPE_DEPTH))
  skew_a.io.inp := io.row
  skew_a.io.en  := io.en
  skew_a.io.res := io.res


  val skew_b = Module(new StridedSkewBuffers(cols, 16, PIPE_DEPTH))
  skew_b.io.inp := col_skew_in
  skew_b.io.en  := io.en
  skew_b.io.res := io.res
  val sa_mode = io.mode =/= 0.U  


  val sa = Module(new PipeSA(rows, cols))
  sa.io.in_a      := skew_a.io.out
  sa.io.in_b      := skew_b.io.out
  sa.io.res       := io.res
  sa.io.en        := io.en
  sa.io.mode      := sa_mode        
  sa.io.load_bias := io.load_bias
  sa.io.bias      := io.bias

  io.out_sum := sa.io.out_sum
}