package vectorPE

import chisel3._

object ElaboratePE extends App {
  println("Generating Verilog for mixedPE...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new PE(16), 
    Array("--target-dir", "INT8")
  )
  println("Done! Check the 'INT8' folder.")
}

object ElaborateSA extends App {
  println("Generating Verilog for MixedSA (4x4)...")
  // You can change the 4, 4 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new SA(4, 4,16), 
    Array("--target-dir", "INT8")
  )
  println("Done! Check the 'INT8' folder.")
}


object ElaborateTopSA extends App {
  println("Generating Verilog for TopMixedSA (64x64)...")
  // You can change the 64, 64 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new TopSA(64, 64,16), 
    Array("--target-dir", "INT8")
  )
  println("Done! Check the 'INT8' folder.")
}

