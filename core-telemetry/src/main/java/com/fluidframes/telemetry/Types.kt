package com.fluidframes.telemetry

/** Chế độ xử lý một frame. */
enum class Mode { PASSTHROUGH, FLOW_GPU, FLOW_NN }

/**
 * Độ phân giải xử lý (kích thước đầu vào NN / block-match). [longSide] tính theo cạnh dài,
 * cạnh ngắn suy ra theo tỉ lệ màn hình và làm tròn bội số 32. AUTO do Governor chọn.
 */
enum class ProcTier(val label: String, val longSide: Int) {
    AUTO("Mặc định (540p)", 0),
    P720("720p", 1280),
    P540("540p", 960),
    P360("360p", 640),
    P270("200p", 480),
    P216("160p", 384),
    P180("130p", 320),
}

enum class FlowBackend(val label: String) {
    AUTO("Tự động"),
    NPU("NPU (ENN/LiteRT)"),
    GPU("GPU block-match"),
    OFF("Tắt (passthrough)"),
}
