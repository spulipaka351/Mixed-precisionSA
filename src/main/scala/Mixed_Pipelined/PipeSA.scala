package Pipe

import chisel3._
import chisel3.util._

class PipeSA(val rows: Int, val cols: Int) extends Module {
  val io = IO(new Bundle {
    val res     = Input(Bool())
    val en      = Input(Bool())
    val mode    = Input(Bool())
    val in_a    = Input(Vec(rows, UInt(16.W)))
    val in_b    = Input(Vec(cols, UInt(16.W)))
    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val pes = Seq.fill(rows)(Seq.fill(cols)(Module(new PipePE())))

  for (i <- 0 until rows) {
    for (j <- 0 until cols) {
      val pe = pes(i)(j)
      pe.io.mode := io.mode
      pe.io.in_a := (if (j == 0) io.in_a(i) else pes(i)(j-1).io.out_a)
      pe.io.in_b := (if (i == 0) io.in_b(j) else pes(i-1)(j).io.out_b)

      // 3-cycle delayed feedback matching pipeline depth, flushable
      val fb_reg1 = RegNext(Mux(io.res, 0.U, Mux(io.en, pe.io.out,  0.U)), 0.U(32.W))
val fb_reg2 = RegNext(Mux(io.res, 0.U, Mux(io.en, fb_reg1,    0.U)), 0.U(32.W))
val fb_reg3 = RegNext(Mux(io.res, 0.U, Mux(io.en, fb_reg2,    0.U)), 0.U(32.W))
pe.io.psum_in := fb_reg3

      io.out_sum(i)(j) := pe.io.out
    }
  }
}