package vectorPE
import  chisel3._

class PE(val width : Int) extends Module{
val io = IO(new Bundle{
   val res =Input(Bool())
   val in_a =Input(UInt(width.W))
   val in_b =Input(UInt(width.W))
   val out_a =Output(UInt((width).W))
   val out_b =Output(UInt((width).W))
   val out_sum=Output(UInt((width*2).W))
})
    val a_reg = RegNext(io.in_a, 0.U)
  val b_reg = RegNext(io.in_b, 0.U)

  io.out_a := a_reg
  io.out_b := b_reg

    val acc = RegInit(0.U((width*2).W))
    val  mul =io.in_a*io.in_b
when(io.res){
    acc := 0.U
}.otherwise{
    acc := acc + mul
}
io.out_sum := acc

}