#pragma once

#include <android/asset_manager.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace ff {

// Phải khớp khối push_constant trong mọi shader (64 byte).
struct PushConstants {
    int32_t srcW, srcH;
    int32_t lowW, lowH;
    int32_t flowW, flowH;
    int32_t outW, outH;
    float t;
    int32_t mode;   // 0 = chép frame hiện tại, 1 = nội suy
    int32_t useNN;  // 1 = mask/flow lấy từ NN, 0 = flow block-match + mask 0.5
    int32_t pad0;
    float flowScaleX, flowScaleY;  // đổi đơn vị pixel proc-res -> UV chuẩn hóa
    float staticThr;               // dưới ngưỡng này coi là vùng tĩnh (HUD) -> giữ nguyên frame thật
    float occThr;                  // warped hai phía lệch hơn ngưỡng này -> trộn không warp
};
static_assert(sizeof(PushConstants) == 64, "PushConstants phải 64 byte");

enum BeginResult {
    kBeginError = -1,
    kBeginOk = 0,
    kBeginSceneCut = 1,
    kBeginLargeMotion = 2,
};

// Toàn bộ Vulkan: import AHardwareBuffer, compute (preprocess / block-match / flow-pack / warp),
// blit + present qua swapchain trên ANativeWindow của overlay.
// Mọi hàm public khóa mu_; gọi chủ yếu từ thread pipeline, SetSurface gọi từ main thread.
class Pipeline {
public:
    static std::unique_ptr<Pipeline> Create(AAssetManager* assets, std::string* err);
    ~Pipeline();

    // Nhận 1 tham chiếu ANativeWindow (đã acquire); nullptr = hủy swapchain.
    bool SetSurface(ANativeWindow* window);

    // Cấp phát tài nguyên ở độ phân giải xử lý (procW/procH bội số 32).
    bool Configure(int procW, int procH);

    // Giai đoạn 1: import 2 frame, preprocess, (tùy chọn) block-match. Chặn tới khi GPU xong.
    int Begin(AHardwareBuffer* prev, AHardwareBuffer* cur, bool nn, bool blockMatch);

    // Giai đoạn 2: present [nội suy, đợi gapMs, frame thật] hoặc chỉ [frame thật].
    // gapMs = nửa chu kỳ nguồn: giữ nhịp đều 60 fps (không present dồn cục).
    bool Finish(bool interp, bool nnFlowReady, float gapMs);

    // Chỉ present frame thật (passthrough).
    bool PresentReal(AHardwareBuffer* cur);

    float* NnInput(size_t* bytes);  // NCHW float32 [1,6,procH,procW]
    float* NnFlow(size_t* bytes);   // NCHW float32 [1,5,procH/2,procW/2]: flow t0(2) flow t1(2) mask(1)

    void GetStats(float* out, int n);
    std::string LastError();

private:
    struct Img {
        VkImage img = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t w = 0, h = 0;
    };
    struct Buf {
        VkBuffer buf = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        void* ptr = nullptr;
        VkDeviceSize size = 0;
    };
    struct Ahb {
        VkImage img = VK_NULL_HANDLE;
        VkDeviceMemory mem = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        uint32_t w = 0, h = 0;
    };

    Pipeline() = default;
    bool Init(AAssetManager* assets);
    bool CreateDescriptors();
    bool CreatePipelines(AAssetManager* assets);
    VkPipeline MakeComputePipeline(AAssetManager* assets, const char* name);

    uint32_t FindMemType(uint32_t bits, VkMemoryPropertyFlags want) const;
    bool MakeImage(Img& im, uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage);
    void DestroyImg(Img& im);
    bool MakeBuffer(Buf& b, VkDeviceSize size);
    void DestroyBuf(Buf& b);
    bool TransitionToGeneral(const std::vector<VkImage>& images);
    bool EnsureOut(uint32_t w, uint32_t h);

    Ahb* ImportAhb(AHardwareBuffer* ahb);
    void DestroyAhbCache();

    bool CreateSwapchain();
    void DestroySwapchain();

    void UpdateDescriptors(VkImageView prev, VkImageView cur);
    void CmdAcquire(VkCommandBuffer cb, Ahb* a);
    void CmdRelease(VkCommandBuffer cb, Ahb* a);
    void CmdMemBarrier(VkCommandBuffer cb, VkPipelineStageFlags dstStages, VkAccessFlags dstAccess);

    bool OutPass(int mode, bool packFlow, bool acquire, bool release);
    bool SubmitAndWait(VkCommandBuffer cb, VkFence fence);
    bool ReadTimestamps(uint32_t first, uint32_t count, double* msOut);

    bool Fail(const char* what, VkResult r);
    bool FailMsg(const std::string& msg);

    // Thiết bị
    VkInstance inst_ = VK_NULL_HANDLE;
    VkPhysicalDevice pd_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t qf_ = 0;
    VkPhysicalDeviceMemoryProperties memProps_{};
    float tsPeriod_ = 0.f;
    bool tsValid_ = false;
    uint64_t tsMask_ = ~0ull;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID pfnGetAhbProps_ = nullptr;

    // Lệnh
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmdInit_ = VK_NULL_HANDLE;
    VkCommandBuffer cmdPre_ = VK_NULL_HANDLE;
    VkCommandBuffer cmdWarp_ = VK_NULL_HANDLE;
    VkCommandBuffer cmdBlit_ = VK_NULL_HANDLE;
    VkFence fenceA_ = VK_NULL_HANDLE;
    VkFence fenceB_ = VK_NULL_HANDLE;
    VkQueryPool qp_ = VK_NULL_HANDLE;

    // Descriptor + pipeline
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl_ = VK_NULL_HANDLE;
    VkPipelineLayout pl_ = VK_NULL_HANDLE;
    VkDescriptorPool dpool_ = VK_NULL_HANDLE;
    VkDescriptorSet ds_ = VK_NULL_HANDLE;
    VkPipeline pPre_ = VK_NULL_HANDLE;
    VkPipeline pBlock_ = VK_NULL_HANDLE;
    VkPipeline pFlowPack_ = VK_NULL_HANDLE;
    VkPipeline pWarp_ = VK_NULL_HANDLE;

    // Tài nguyên
    Img lowPrev_, lowCur_, flow_, aux_, out_;
    Buf nnIn_, nnFlow_, metrics_;
    std::unordered_map<uint64_t, Ahb> ahb_;
    Ahb* curPrev_ = nullptr;
    Ahb* curCur_ = nullptr;

    // Swapchain
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR sc_ = VK_NULL_HANDLE;
    std::vector<VkImage> scImages_;
    std::vector<VkSemaphore> renderDone_;
    VkSemaphore acquireSem_ = VK_NULL_HANDLE;
    VkExtent2D scExtent_{};
    bool scDirty_ = false;

    int procW_ = 0, procH_ = 0, flowW_ = 0, flowH_ = 0;
    PushConstants pc_{};
    float stats_[16] = {};
    double presentWaitMs_ = 0.0;

    std::mutex mu_;
    std::mutex errMu_;
    std::string err_;
};

}  // namespace ff
