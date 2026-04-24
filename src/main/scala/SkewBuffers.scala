package vectorPE

import chisel3._
import chisel3.util._

class SkewBuffers(val n: Int, val width: Int) extends Module {
  val io = IO(new Bundle {
    val res = Input(Bool())              
    val en  = Input(Bool())              
    val inp = Input(Vec(n, UInt(width.W)))
    val out = Output(Vec(n, UInt(width.W))) 
  })


  for (i <- 0 until n) {
   
    val safe_in = Mux(io.res, 0.U, io.inp(i))
    io.out(i) := ShiftRegister(safe_in, i, 0.U, io.en)
    
  }
}