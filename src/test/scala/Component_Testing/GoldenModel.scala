// package Mixed_opt

// /**
//  * GoldenModel.scala  — fixed v2
//  *
//  * Changes from v1:
//  *   fp16Mac: multiply-normalization now uses Cat(raw_man(20,0), 0.U(2.W)) and
//  *   Cat(raw_man(19,0), 0.U(3.W)) semantics, matching the Chisel RTL exactly.
//  *
//  *   The original used (rawMan >> 1) & mask which is a right-shift, not a Cat.
//  *   Cat(raw_man(20,0), "00") = (rawMan & 0x1FFFFF) << 2  — 21 lower bits, 2 zero LSBs.
//  *   Cat(raw_man(19,0), "000") = (rawMan & 0x0FFFFF) << 3 — 20 lower bits, 3 zero LSBs.
//  */
// object GoldenModel {

//   // ─────────────────────────────────────────────────────────────────────────
//   // INT8 MAC
//   // Mirrors OptPE: sign-extends in_a[7:0] and in_b[7:0], multiplies,
//   // truncates to 16 bits, sign-extends again into 32-bit accumulator.
//   // ─────────────────────────────────────────────────────────────────────────
//   def int8Mac(ia: Int, ib: Int, psum: Int): Int = {
//     // Extract lower 8 bits and sign-extend
//     val sa = (ia & 0xFF).toByte.toInt          // sign-extend to Int
//     val sb = (ib & 0xFF).toByte.toInt
//     val prod = sa * sb                          // full product
//     // RTL takes shared_mul_result(15,0) — lower 16 bits, sign-extended
//     val prod16 = (prod & 0xFFFF).toShort.toInt
//     psum + prod16
//   }

//   // ─────────────────────────────────────────────────────────────────────────
//   // Custom FP16 format: 1 sign | 5 exp (bias=15) | 10 mantissa
//   // ─────────────────────────────────────────────────────────────────────────
//   def floatToHalf(f: Float): Int = {
//     if (f == 0.0f) return 0
//     val sign   = if (f < 0) 1 else 0
//     val fAbs   = math.abs(f.toDouble)
//     var exp    = math.floor(math.log(fAbs) / math.log(2)).toInt
//     var expB   = exp + 15
//     if (expB <= 0) expB = 0
//     if (expB > 31) expB = 31
//     val man    = math.round((fAbs / math.pow(2, exp) - 1.0) * 1024).toInt
//       .max(0).min(1023)
//     (sign << 15) | (expB << 10) | man
//   }

//   def halfToFloat(h: Int): Double = {
//     val sign = (h >> 15) & 1
//     val exp  = (h >> 10) & 0x1F
//     val man  = h & 0x3FF
//     if (exp == 0 && man == 0) return 0.0
//     val v = (1.0 + man.toDouble / 1024.0) * math.pow(2, exp - 15)
//     if (sign == 1) -v else v
//   }

//   // Pack/unpack IEEE-754 FP32
//   def floatToFP32Bits(f: Float): Long =
//     java.lang.Float.floatToIntBits(f).toLong & 0xFFFFFFFFL

//   def fp32BitsToFloat(bits: Long): Float =
//     java.lang.Float.intBitsToFloat((bits & 0xFFFFFFFFL).toInt)

//   // ─────────────────────────────────────────────────────────────────────────
//   // FP16 → FP32 MAC — mirrors QAT-optimised OptPE bit-exactly.
//   //
//   // Multiply normalization (FIXED from v1):
//   //   RTL:    Cat(raw_man(20,0), 0.U(2.W))  = (rawMan & 0x1FFFFF) << 2
//   //   RTL:    Cat(raw_man(19,0), 0.U(3.W))  = (rawMan & 0x0FFFFF) << 3
//   //   old bug: (rawMan >> 1) & 0x7FFFFF    ← wrong: right-shift ≠ Cat+append zeros
//   //
//   // FP32-add normalization (3-case, unchanged):
//   //   sum_man(24)==1 → (sumMan >> 1) & 0x7FFFFF
//   //   sum_man(23)==1 → sumMan & 0x7FFFFF
//   //   sum_man(22)==1 → Cat(sum_man(21,0),"0") = (sumMan & 0x3FFFFF) << 1
//   //   otherwise      → flush to zero (deep cancellation within QAT range)
//   // ─────────────────────────────────────────────────────────────────────────
//   def fp16Mac(iaRaw: Int, ibRaw: Int, psumF: Float): Float = {
//     val signA = (iaRaw >> 15) & 1
//     val expA  = (iaRaw >> 10) & 0x1F
//     val manA  = iaRaw & 0x3FF
//     val signB = (ibRaw >> 15) & 1
//     val expB  = (ibRaw >> 10) & 0x1F
//     val manB  = ibRaw & 0x3FF

//     // QAT: hidden bit always 1 (no subnormals)
//     val fManA  = (1 << 10) | manA    // 11 bits
//     val fManB  = (1 << 10) | manB
//     val rawMan = fManA * fManB        // up to 22 bits [21:0]

//     val outSign = signA ^ signB
//     val expRaw  = expA + expB

//     // ── Multiply normalization — FIXED ────────────────────────────────────
//     // RTL: raw_man = shared_mul_result(21,0).asUInt
//     // when(raw_man(21)===1.U):
//     //   mul_norm_man := Cat(raw_man(20,0), 0.U(2.W))
//     //                 = (rawMan & 0x1FFFFF) << 2   [21 bits ++ "00" → 23 bits]
//     // .otherwise:
//     //   mul_norm_man := Cat(raw_man(19,0), 0.U(3.W))
//     //                 = (rawMan & 0x0FFFFF) << 3   [20 bits ++ "000" → 23 bits]
//     val (mulNormMan, mulNormExp) =
//       if (((rawMan >> 21) & 1) == 1)
//         (((rawMan & 0x1FFFFF) << 2) & 0x7FFFFF, math.min(expRaw + 98, 255))
//       else
//         (((rawMan & 0x0FFFFF) << 3) & 0x7FFFFF, math.min(expRaw + 97, 255))

//     // ── FP32 psum ─────────────────────────────────────────────────────────
//     val psumBits = floatToFP32Bits(psumF)
//     val psumSign = ((psumBits >> 31) & 1).toInt
//     val psumExp  = ((psumBits >> 23) & 0xFF).toInt
//     val psumMan  = (psumBits & 0x7FFFFF).toInt

//     // QAT: always normal — hidden bit hardwired
//     val psumManFull = (1 << 23) | psumMan
//     val mulManFull  = (1 << 23) | mulNormMan

//     // ── Exponent alignment ────────────────────────────────────────────────
//     val (alignedMul, alignedPsum, commonExp) =
//       if (mulNormExp > psumExp) {
//         val diff = mulNormExp - psumExp
//         (mulManFull, if (diff < 32) psumManFull >>> diff else 0, mulNormExp)
//       } else if (psumExp > mulNormExp) {
//         val diff = psumExp - mulNormExp
//         (if (diff < 32) mulManFull >>> diff else 0, psumManFull, psumExp)
//       } else {
//         (mulManFull, psumManFull, mulNormExp)
//       }

//     // ── Add / subtract ────────────────────────────────────────────────────
//     val (sumMan, finalSign) =
//       if (outSign == psumSign)
//         (alignedMul + alignedPsum, outSign)
//       else if (alignedMul >= alignedPsum)
//         (alignedMul - alignedPsum, outSign)
//       else
//         (alignedPsum - alignedMul, psumSign)

//     // ── FP32 normalization (3-case, QAT) ─────────────────────────────────
//     val (normMan, normExp) =
//       if (sumMan == 0)
//         (0, 0)
//       else if (((sumMan >> 24) & 1) == 1)
//         ((sumMan >>> 1) & 0x7FFFFF, math.min(commonExp + 1, 255))
//       else if (((sumMan >> 23) & 1) == 1)
//         (sumMan & 0x7FFFFF, commonExp)
//       else if (((sumMan >> 22) & 1) == 1)
//         // RTL: Cat(sum_man(21,0), 0.U(1.W)) = (sumMan & 0x3FFFFF) << 1
//         (((sumMan & 0x3FFFFF) << 1) & 0x7FFFFF, if (commonExp > 0) commonExp - 1 else 0)
//       else
//         (0, 0)  // flush to zero — deep cancellation, matches RTL "otherwise"

//     val outBits = ((finalSign.toLong << 31) | (normExp.toLong << 23) | normMan.toLong) & 0xFFFFFFFFL
//     fp32BitsToFloat(outBits)
//   }

//   // ─────────────────────────────────────────────────────────────────────────
//   // Systolic-array golden matmul with skew accounted for
//   // A: rows×K, B: K×cols  →  C: rows×cols
//   // ─────────────────────────────────────────────────────────────────────────
//   def matmulInt8(A: Seq[Seq[Int]], B: Seq[Seq[Int]]): Seq[Seq[Int]] = {
//     val rows = A.length; val K = A(0).length; val cols = B(0).length
//     val C = Array.ofDim[Int](rows, cols)
//     for (i <- 0 until rows; j <- 0 until cols; k <- 0 until K)
//       C(i)(j) = int8Mac(A(i)(k), B(k)(j), C(i)(j))
//     C.map(_.toSeq).toSeq
//   }

//   def matmulFP16(A: Seq[Seq[Int]], B: Seq[Seq[Int]]): Seq[Seq[Float]] = {
//     val rows = A.length; val K = A(0).length; val cols = B(0).length
//     val C = Array.ofDim[Float](rows, cols)
//     for (i <- 0 until rows; j <- 0 until cols; k <- 0 until K)
//       C(i)(j) = fp16Mac(A(i)(k), B(k)(j), C(i)(j))
//     C.map(_.toSeq).toSeq
//   }

//   // Relative error helper for FP comparisons
//   def relErr(got: Float, ref: Float): Double = {
//     val d = math.abs(got - ref)
//     if (math.abs(ref) < 1e-6f) d else d / math.abs(ref)
//   }
// }