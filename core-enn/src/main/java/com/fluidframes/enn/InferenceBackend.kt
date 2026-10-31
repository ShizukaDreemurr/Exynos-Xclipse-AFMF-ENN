package com.fluidframes.enn

import java.nio.ByteBuffer

/**
 * Hợp đồng mô hình flow (xem models/README.md):
 *  - input : float32 NCHW [1,6,H,W]  (prev RGB rồi cur RGB, 0..1), H,W bội số 32
 *  - output: float32 NCHW [1,5,H/2,W/2] = F_t→0 (2), F_t→1 (2) tính bằng pixel proc-res, mask (1, sigmoid)
 * Mỗi tier độ phân giải có một file mô hình riêng: models/flow_<longSide>.tflite trong assets.
 */
interface InferenceBackend {
    /** Tên hiển thị trên HUD, ví dụ "LiteRT/NPU". */
    val name: String

    /** true nếu thực sự chạy trên NPU (không rơi về GPU/CPU). */
    val onNpu: Boolean

    /** Nạp mô hình cho tier cạnh dài [longSide]. Trả false nếu không có file / không biên dịch được. */
    fun load(longSide: Int, procW: Int, procH: Int): Boolean

    /**
     * Chạy suy luận. [input] và [output] là direct buffer native-order, position = 0.
     * Trả thời gian chạy (ms), hoặc -1f nếu lỗi.
     */
    fun run(input: ByteBuffer, output: ByteBuffer): Float

    fun close()
}
