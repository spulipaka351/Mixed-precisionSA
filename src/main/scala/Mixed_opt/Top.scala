package Mixed_opt

import chisel3._
import chisel3.util._
import common.SkewBuffers
class Top(val rows: Int, val cols: Int) extends Module {
  val io = IO(new Bundle {
    val res  = Input(Bool())
    val en   = Input(Bool())
    val mode = Input(Bool())                          // false=INT8, true=FP16

    val row  = Input(Vec(rows, UInt(16.W)))
    val col  = Input(Vec(cols, UInt(16.W)))

    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val skew_a = Module(new SkewBuffers(rows, 16))
  val skew_b = Module(new SkewBuffers(cols, 16))
  val sa     = Module(new SA(rows, cols))

  skew_a.io.inp := io.row
  skew_a.io.en  := io.en
  skew_a.io.res := io.res

  skew_b.io.inp := io.col
  skew_b.io.en  := io.en
  skew_b.io.res := io.res

  sa.io.in_a := skew_a.io.out
  sa.io.in_b := skew_b.io.out
  sa.io.res  := io.res
  sa.io.en   := io.en          // ← pass en so MixedSA can gate accumulation
  sa.io.mode := io.mode

  io.out_sum := sa.io.out_sum
}