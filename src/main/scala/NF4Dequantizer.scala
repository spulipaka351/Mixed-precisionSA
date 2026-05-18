 package common

import chisel3._
import chisel3.util._

class NF4Dequantizer extends Module {
    val io = IO(new Bundle{
        val in = Input(UInt(32.W))
        val scale = Input(UInt(32.W))
        val out = Output(UInt(32.W))
    })
    
    val LookupTable = VecInit(Seq(
        0.U(32.W), 1.U(32.W), 2.U(32.W), 3.U(32.W),
        4.U(32.W), 5.U(32.W), 6.U(32.W), 7.U(32.W),
        8.U(32.W), 9.U(32.W), 10.U(32.W), 11.U(32.W),
        12.U(32.W), 13.U(32.W), 14.U(32.W), 15.U(32.W)
    ))
    
    io.out := LookupTable(io.in) * io.scale
    
    }
