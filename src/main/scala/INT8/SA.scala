package vectorPE

import chisel3._

class SA(val rows :Int ,val cols :Int ,val width :Int) extends Module{

    val io  = IO(new Bundle{
            val res = Input(Bool())

            val in_a = Input(Vec (rows,UInt(16.W)))
            val in_b =Input(Vec (cols,UInt(16.W)))
            val out_sum = Output(Vec(rows, Vec(cols, UInt(32.W))))
    })

    val pes = Seq.fill(rows)(Seq.fill(cols)(Module(new PE(width))))

    for (i<- 0 until rows){
        for (j<- 0 until cols){
            
            io.out_sum(i)(j) := pes(i)(j).io.out_sum
            
             pes(i)(j).io.res := io.res
            if (j == 0) {
        pes(i)(j).io.in_a := io.in_a(i)
      } else {
   
        pes(i)(j).io.in_a := pes(i)(j-1).io.out_a
      }

      if (i == 0) {
   
        pes(i)(j).io.in_b := io.in_b(j)
      } else {
    
        pes(i)(j).io.in_b := pes(i-1)(j).io.out_b
      }
    }
    }
}