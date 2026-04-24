package Mixed

import chisel3._
import chisel3.util._

class MixedSA(val rows: Int, val cols: Int) extends Module {
  val io = IO(new Bundle {
    val res     = Input(Bool())
    val en      = Input(Bool())   // gate accumulation: 1=accumulate, 0=hold
    val mode    = Input(Bool())
    val in_a    = Input(Vec(rows, UInt(16.W)))
    val in_b    = Input(Vec(cols, UInt(16.W)))
    val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
  })

  val pes = Seq.fill(rows)(Seq.fill(cols)(Module(new mixedPE())))
  val acc = Seq.fill(rows)(Seq.fill(cols)(RegInit(0.U(32.W))))

  for (i <- 0 until rows) {
    for (j <- 0 until cols) {
      val pe = pes(i)(j)

      pe.io.mode := io.mode
      pe.io.in_a := (if (j == 0) io.in_a(i) else pes(i)(j-1).io.out_a)
      pe.io.in_b := (if (i == 0) io.in_b(j) else pes(i-1)(j).io.out_b)
      pe.io.psum_in := acc(i)(j)

      when(io.res) {
        acc(i)(j) := 0.U
      } .elsewhen(io.en) {
        // Only update accumulator when enabled — freezes during drain
        acc(i)(j) := pe.io.out
      }
      // when en=0 and res=0: acc holds its value (no assignment = no change)

      io.out_sum(i)(j) := acc(i)(j)
    }
  }
}