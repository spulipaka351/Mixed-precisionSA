package FP16


import chisel3._

object ElaboratePE extends App {
  println("Generating Verilog for FP16 PE...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new PE_FP16(width = 16), 
    Array("--target-dir", "FP16")
  )
  println("Done! Check the 'verilog_output' folder.")
}

object ElaborateMixedSA extends App {
  println("Generating Verilog for FP16 (4x4)...")
  // You can change the 4, 4 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new SA_FP16(4, 4, 16), 
    Array("--target-dir", "FP16")
  )
  println("Done! Check the 'verilog_output' folder.")
}


object ElaborateTopSA extends App {
  println("Generating Verilog for TopMixedSA (64x64)...")
  // You can change the 64, 64 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new TopSAFP(64, 64,16), 
    Array("--target-dir", "FP16")
  )
  println("Done! Check the 'verilog_output' folder.")
}

