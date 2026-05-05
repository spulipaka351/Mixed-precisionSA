package Mixed_opt

import chisel3._

object ElaborateTopSA extends App {
  println("Generating Verilog for TopMixedSA (4x4)...")
  // You can change the 4, 4 parameters to whatever grid size you need
  (new chisel3.stage.ChiselStage).emitVerilog(
    new Top(4, 4), 
    Array("--target-dir", "mixed_opt")
  )
  println("Done! Check the 'verilog_output' folder.")
}
