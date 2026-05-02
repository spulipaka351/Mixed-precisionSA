package FP16
import chisel3._
import chisel3.util._

import common.SkewBuffers
class TopSAFP(val rows :Int , val cols :Int , val width : Int) extends Module {
    val io = IO(new Bundle{
        val res = Input(Bool())
        val en = Input(Bool())
        val row = Input(Vec(rows,UInt(32.W)))
        val col = Input(Vec(cols,UInt(32.W)))

        val out_sum = Output(Vec(rows, Vec(cols, UInt((width*2).W))))
    })


    val skew_a =  Module( new SkewBuffers(rows, width))
    val skew_b =  Module (new SkewBuffers(cols,width))
    val Sa = Module( new SA_FP16(rows,cols,width))

    skew_a.io.inp := io.row
    skew_a.io.en := io.en
    skew_a.io.res := io.res


    skew_b.io.inp := io.col
    skew_b.io.en := io.en 
    skew_b.io.res := io.res

    Sa.io.in_a := skew_a.io.out
    Sa.io.in_b := skew_b.io.out

    Sa.io.res := io.res

    io.out_sum := Sa.io.out_sum
    

}