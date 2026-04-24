// src/main/scala/SimpleFlipFlop.scala
package vectorPE
import chisel3._
import chisel3.util._

class ALU extends Module {
  val io = IO(new Bundle {
    val en  = Input(Bool())   // Use Bool() for 1-bit control signals
    val ctrl = Input(UInt(2.W))
    val a = Input(UInt(8.W))
    val b = Input(UInt(8.W))
    val out = Output(UInt(8.W)) // 8-bit Output
  })
  
  // 00 -> add , 01->sub , 10 -> mul

  // 1. Declare the register ONCE. 
  // It is initialized to 0.
  val myReg = RegInit(0.U(8.W))

  // 2. Define the update logic using 'when' (Hardware Condition)
  // If 'en' is true, increment the register.
  // If 'en' is false, the register keeps its old value automatically.
  when(io.en) {
    switch(io.ctrl){
      is(0.U){
        myReg := io.a+io.b

      }
      is(1.U){
        myReg := io.a-io.b
      }
      is(2.U){
        myReg := io.a*io.b
      }
    }
  }

  // 3. Connect register to output
  io.out := myReg
}