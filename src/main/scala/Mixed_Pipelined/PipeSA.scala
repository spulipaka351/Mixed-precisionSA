package Pipe

import chisel3._
import chisel3.util._

// Add PIPE_DEPTH with a default of 3 so your Top module doesn't break
class PipeSA(val rows: Int, val cols: Int, val PIPE_DEPTH: Int = 3) extends Module {
  val io = IO(new Bundle {
    val res       = Input(Bool())
    val en        = Input(Bool())
    val mode      = Input(Bool())
    val load_bias = Input(Bool())
    val in_a      = Input(Vec(rows, UInt(16.W)))
    val in_b      = Input(Vec(cols, UInt(16.W)))
    val bias      = Input(Vec(cols, UInt(32.W)))
    val out_sum   = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val pes = Seq.fill(rows)(Seq.fill(cols)(Module(new PipePE())))

  for (i <- 0 until rows) {
    for (j <- 0 until cols) {
      val pe = pes(i)(j)
      pe.io.mode := io.mode
      pe.io.in_a := (if (j == 0) io.in_a(i) else pes(i)(j-1).io.out_a)
      pe.io.in_b := (if (i == 0) io.in_b(j) else pes(i-1)(j).io.out_b)

      val fb_reg1 = RegNext(Mux(io.res, 0.U, Mux(io.en, pe.io.out, 0.U)), 0.U(32.W))
      val fb_reg2 = RegNext(Mux(io.res, 0.U, Mux(io.en, fb_reg1,   0.U)), 0.U(32.W))
      val fb_reg3 = RegNext(Mux(io.res, 0.U, Mux(io.en, fb_reg2,   0.U)), 0.U(32.W))
      val delayCycles = (i + j) * PIPE_DEPTH
      val local_load_bias = ShiftRegister(io.load_bias, delayCycles, false.B, io.en)

      pe.io.psum_in := Mux(local_load_bias, io.bias(j), fb_reg3)

      io.out_sum(i)(j) := pe.io.out
    }
  }
}