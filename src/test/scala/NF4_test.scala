package Pipe


import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class NF4_Test extends AnyFlatSpec with ChiselScalatestTester {

def packNF4(values: Seq[Int]): Long = {
  
  values.zipWithIndex
        .map { case (v, i) => (v.toLong & 0xF) << (i * 4) }
        .reduce(_ | _)
}
def toFP16(f: Float): Long = {
  // use Java's built-in float16 conversion via short bits
  val bits = java.lang.Float.floatToRawIntBits(f)
  val sign = (bits >>> 31) & 0x1
  val exp  = ((bits >>> 23) & 0xFF) - 127 + 15
  val man  = (bits >>> 13) & 0x3FF

  // handle underflow/overflow
  val clampedExp = if (exp <= 0) 0 else if (exp >= 31) 31 else exp
  ((sign << 15) | (clampedExp << 10) | man).toLong & 0xFFFFL
}

    def fp16BitsToFloat(fp16: Long): Float = {
  val sign = (fp16 >> 15) & 0x1
  val exp  = ((fp16 >> 10) & 0x1F) - 15 + 127   // unbias FP16, rebias FP32
  val man  = (fp16 & 0x3FF) << 13                // 10-bit mantissa → 23-bit
  val fp32 = (sign << 31) | (exp << 23) | man
  java.lang.Float.intBitsToFloat(fp32.toInt)
}
  def toFloat(bits: Long): Float = {
  val signed = if (bits > 0x7FFFFFFFL) bits - 0x100000000L else bits
  java.lang.Float.intBitsToFloat(signed.toInt)
}

    def toFP32Bits(f: Float): Long =
  java.lang.Float.floatToRawIntBits(f).toLong & 0xFFFFFFFFL
  
  
"this test" should "pass" in {
  test(new NF4Dequantizer()).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
    var lookup = Seq(
       -1.0000,-0.6962,-0.5251,-0.3949,
       -0.2844, -0.1847, -0.0911, 0.0000,
       0.0796, 0.1609,0.2461,  0.3379,  
       0.4407,0.5626, 0.7230, 1.0000
    )
    var inputValues = Seq(1, 2, 3, 4) 
    var scale = -2.0f
    val packedNF4 = packNF4(inputValues)
    dut.io.packed_nf4.poke(packedNF4.U)  
    dut.io.scale.poke(toFP16(scale).U)
    
    dut.clock.step(1)
    for (i <- 0 until 4) {
      val out = dut.io.out(i).peek().litValue.toLong
      assert(math.abs(fp16BitsToFloat(out) - lookup(inputValues(i)) * scale) < 5e-3, "Output mismatch for index " + i)
      println(s"Output for NF4 value ${lookup(inputValues(i))} x ${scale}: ${fp16BitsToFloat(out)} with error ${math.abs(fp16BitsToFloat(out) - lookup(inputValues(i)) * scale)}")
    }

  }

}
}