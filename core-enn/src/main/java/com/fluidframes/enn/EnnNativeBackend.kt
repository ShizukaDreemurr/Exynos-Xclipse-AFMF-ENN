package com.fluidframes.enn

import java.nio.ByteBuffer

/**
 * ADAPTER CHỜ SDK — CHƯA CÓ CHỨC NĂNG.
 *
 * Đây là chỗ cắm ENN SDK / Exynos AI LiteCore native (nạp model .nnc đã biên dịch AOT cho E9965,
 * truyền buffer zero-copy). Tôi chưa có header/AAR của SDK nên KHÔNG bịa chữ ký hàm.
 * Khi bạn lấy được SDK (docs/ENN_SDK_GUIDE.md), thay phần thân bằng các lời gọi JNI thật
 * (ví dụ: initialize → open model → commit buffer → execute → release) rồi đổi [available] thành true.
 */
class EnnNativeBackend : InferenceBackend {
    override val name = "ENN(native, stub)"
    override val onNpu = false

    val available: Boolean = false

    override fun load(longSide: Int, procW: Int, procH: Int): Boolean = false
    override fun run(input: ByteBuffer, output: ByteBuffer): Float = -1f
    override fun close() {}
}
