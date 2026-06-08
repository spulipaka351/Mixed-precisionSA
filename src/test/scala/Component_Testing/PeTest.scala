// package Pipe.Component_Testing
// import chisel3._
// import chiseltest._
// import org.scalatest.flatspec.AnyFlatSpec

// class PeTest extends AnyFlatSpec with ChiselScalatestTester {


//     def calculateExpected(a: Int, b: Int, psum: Int): Int = {
//         return a * b + psum
//     }
    
//     def ReadDecimal(bits:Long): Float ={
//         return java.lang.Float.intBitsToFloat(bits.toInt)
//     }
//     def toFP32bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
   

//     def toFP16bits(f: Float): Int = {
//   val b    = java.lang.Float.floatToRawIntBits(f)
//   val sign = (b >>> 31) & 0x1
//   val exp  = ((b >>> 23) & 0xFF) - 127 + 15
//   val man  = (b >>> 13) & 0x3FF
//   ((sign << 15) | (exp << 10) | man) & 0xFFFF  // mask to unsigned 16-bit
// }


//   def driveDUT(dut: PipePE, a: Float, b: Float, psum: Float): Unit = {
//   dut.io.in_a.poke((toFP16bits(a) & 0xFFFF).toLong.U)
//   dut.io.in_b.poke((toFP16bits(b) & 0xFFFF).toLong.U)
//   dut.io.psum_in.poke(toFP32bits(psum).U)
//   dut.io.mode.poke(true.B)
// }
//   "Pe (MAC Unit)" should "multiply and accumulate correctly" in {
//     test(new PipePE()) { dut =>
      
//       // --- Cycle 1: Calculate 2 * 3 ---
//       // Changed 'in_a' -> 'a' and 'in_b' -> 'b'
//       val expected = calculateExpected(2, 3, 0).U // Assuming psum starts at 0
//      //cycle 0
//       var bitsA = toFP16bits(2.3f)
//       var bitsB = toFP16bits(3.3f)
//       var bitsPsum = toFP32bits(2.0f)

//      val sequence = Seq (
//         (1.0f, 2.0f, 0.0f), 
//         (2.0f, 3.0f, 0.0f), 
//         (4.0f, 5.0f, 10.0f),
//         (6.0f, 7.0f, 20.0f))
//         var cycle = 0
      
//        for (i <- sequence.indices) {
//         val (a, b, psum) = sequence(i)
//         driveDUT(dut, a, b, psum)
//         dut.clock.step(1) // Advance one cycle after poking inputs
//         cycle += 1
//         println(s"output after cylce ${cycle} ${ReadDecimal(dut.io.out.peek().litValue.toLong)}")

//        }

//        for (i <- sequence.indices) {
//         dut.io.in_a.poke(0.U)
//         dut.io.in_b.poke(0.U)
//         dut.io.psum_in.poke(0.U)    // hard zero, not toFP32bits
//         dut.io.mode.poke(true.B)// Drive zeros to keep pipeline moving
//         dut.clock.step(1) // Advance cycles to allow output to propagate
//         cycle += 1
//         println(s"output after cylce ${cycle} ${ReadDecimal(dut.io.out.peek().litValue.toLong)}")
//        }
      
//     }
//   }
//   "Pe" should "multiply and accumulate correctly" in {
//   test(new PipePE()) { dut =>

//     val sequence = Seq(
//       (1.0f, 2.0f,  0.0f),
//       (2.0f, 3.0f,  0.0f),
//       (4.0f, 5.0f, 10.0f),
//       (6.0f, 7.0f, 20.0f)
//     )
//     val expected = Seq(2.0f, 6.0f, 30.0f, 62.0f)
//     val results  = scala.collection.mutable.ArrayBuffer[Float]()
//     var cycle    = 0
//     val PIPE_DEPTH = 3   // 3 steps between poke and valid output

//     // fill
//     for (i <- sequence.indices) {
//       val (a, b, psum) = sequence(i)
//       driveDUT(dut, a, b, psum)
//       dut.clock.step(1)
//       cycle += 1
//       val out = ReadDecimal(dut.io.out.peek().litValue.toLong)
//       println(s"cycle $cycle [fill]: out=$out")
//       // collect valid outputs that appear during fill phase
//       // valid outputs start appearing at cycle PIPE_DEPTH
//       if (cycle >= PIPE_DEPTH) results += out
//     }

//     // drain — only need 3 more cycles to flush remaining results
//     for (i <- 0 until PIPE_DEPTH - 1) {
//       // explicitly poke every port — no shortcuts
//       dut.io.in_a.poke(0.U)
//       dut.io.in_b.poke(0.U)
//       dut.io.psum_in.poke(0.U)    // hard zero, not toFP32bits
//       dut.io.mode.poke(true.B)
//       dut.clock.step(1)
//       cycle += 1
//       val out = ReadDecimal(dut.io.out.peek().litValue.toLong)
//       println(s"cycle $cycle [drain]: out=$out")
//       results += out
//     }

//     // verify
//     println("\n=== Results ===")
//     expected.zipWithIndex.foreach { case (exp, i) =>
//       val got = results(i)
//       println(s"  MAC[$i]: got=$got  expected=$exp  ${if (math.abs(got-exp)<0.1f) "PASS" else "FAIL"}")
//       assert(math.abs(got - exp) < 0.1f, s"FAIL at $i: got $got expected $exp")
//     }
//     println("All passed.")
//   }
// }


// // after your passing test, add a zero-flush verification
// "PipePE debug" should "trace raw bits" in {
//   test(new PipePE()) { dut =>

//     println("=== push real input ===")
//     driveDUT(dut, 2.0f, 2.0f, 0.0f)
//     dut.clock.step(1)
//     println(s"after push: out=0x${(dut.io.out.peek().litValue.toLong & 0xFFFFFFFFL).toHexString}")

//     println("=== flush cycles ===")
    
//   }
// }
// }