// package vectorPE

// import chisel3._
//  class VectorPE(val len: Int) extends Module{
//     val io = IO(new Bundle{
//         val a = Input(Vec(len,UInt(16.W)))
//         val b= Input(Vec(len,UInt(16.W)))
//         val res = Input(Bool())
//         val out = Output(UInt(32.W))

//     })

//         val pes = Seq.fill(len)(Module(new PE(16)))

//         for ( i <- 0 until len ){
//             pes(i).io.a := io.a(i)
//             pes(i).io.b := io.b(i)

//             pes(i).io.res := io.res

//             // io.out(i) := pes(i).io.out
//         }
//         val reduction = pes.map(_.io.out).reduce(_ +& _)
//         io.out := reduction

//  }