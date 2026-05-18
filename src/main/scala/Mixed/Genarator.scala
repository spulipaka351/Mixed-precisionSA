package Mixed

import chisel3._

object ElaboratePE extends App {
  println("Generating Verilog for mixedPE...")
  (new chisel3.stage.ChiselStage).emitVerilog(
    new mixedPE(), 
    Array("--target-dir", "verilog_output")
  )
  println("Done! Check the 'verilog_output' folder.")
}

object ElaborateMixedSA extends App {
  println("Generating Verilog for MixedSA (4x4)...")
  // You can change the 4, 4 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new MixedSA(4, 4), 
    Array("--target-dir", "verilog_output")
  )
  println("Done! Check the 'verilog_output' folder.")
}


object ElaborateTopSA extends App {
  println("Generating Verilog for TopMixedSA (64x64)...")
  // You can change the 64, 64 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new TopMixedSA(64, 64), 
    Array("--target-dir", "mixed_opt_64")
  )
  println("Done! Check the 'mixed_opt_64' folder.")
}

