#include "pipeline.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <thread>
#include <cstdio>
#include <cstring>

#define TAG "ff-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Chỉ dùng trong hàm trả về bool của Pipeline.
#define VK_TRY(expr)                                   \
    do {                                               \
        VkResult r_ = (expr);                          \
        if (r_ != VK_SUCCESS) return Fail(#expr, r_);  \
    } while (0)

namespace ff {
namespace {

using Clock = std::chrono::steady_clock;

double MsSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

constexpr int kBlock = 16;                       // phải khớp blockmatch.comp
constexpr float kSceneCutDiff = 0.28f;           // mean |luma diff| (0..1) coi là chuyển cảnh
constexpr float kLargeMotionFrac = 0.35f;        // tỉ lệ block chạm biên vùng tìm kiếm
constexpr size_t kMaxAhbCache = 16;
constexpr uint64_t kAcquireTimeoutNs = 100000000ull;
constexpr int kBindingCount = 12;

uint32_t CeilDiv(int a, int b) { return static_cast<uint32_t>((a + b - 1) / b); }

}  // namespace

// ---------------------------------------------------------------------------------------------
// Vòng đời
// ---------------------------------------------------------------------------------------------

std::unique_ptr<Pipeline> Pipeline::Create(AAssetManager* assets, std::string* err) {
    std::unique_ptr<Pipeline> p(new Pipeline());
    if (!p->Init(assets)) {
        if (err) *err = p->LastError();
        return nullptr;
    }
    return p;
}

Pipeline::~Pipeline() {
    if (dev_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(dev_);
        DestroySwapchain();
        if (surface_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(inst_, surface_, nullptr);
        DestroyAhbCache();
        DestroyImg(lowPrev_);
        DestroyImg(lowCur_);
        DestroyImg(flow_);
        DestroyImg(aux_);
        DestroyImg(out_);
        DestroyBuf(nnIn_);
        DestroyBuf(nnFlow_);
        DestroyBuf(metrics_);
        vkDestroyPipeline(dev_, pPre_, nullptr);
        vkDestroyPipeline(dev_, pBlock_, nullptr);
        vkDestroyPipeline(dev_, pFlowPack_, nullptr);
        vkDestroyPipeline(dev_, pWarp_, nullptr);
        vkDestroyDescriptorPool(dev_, dpool_, nullptr);
        vkDestroyPipelineLayout(dev_, pl_, nullptr);
        vkDestroyDescriptorSetLayout(dev_, dsl_, nullptr);
        vkDestroySampler(dev_, sampler_, nullptr);
        vkDestroyQueryPool(dev_, qp_, nullptr);
        vkDestroyFence(dev_, fenceA_, nullptr);
        vkDestroyFence(dev_, fenceB_, nullptr);
        vkDestroySemaphore(dev_, acquireSem_, nullptr);
        vkDestroyCommandPool(dev_, pool_, nullptr);
        vkDestroyDevice(dev_, nullptr);
    }
    if (window_) ANativeWindow_release(window_);
    if (inst_ != VK_NULL_HANDLE) vkDestroyInstance(inst_, nullptr);
}

bool Pipeline::Fail(const char* what, VkResult r) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s -> VkResult %d", what, static_cast<int>(r));
    return FailMsg(buf);
}

bool Pipeline::FailMsg(const std::string& msg) {
    LOGE("%s", msg.c_str());
    std::lock_guard<std::mutex> l(errMu_);
    err_ = msg;
    return false;
}

std::string Pipeline::LastError() {
    std::lock_guard<std::mutex> l(errMu_);
    return err_;
}

bool Pipeline::Init(AAssetManager* assets) {
    // Instance (Vulkan 1.1)
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "fluidframes";
    ai.apiVersion = VK_API_VERSION_1_1;
    const char* instExts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &ai;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = instExts;
    VK_TRY(vkCreateInstance(&ici, nullptr, &inst_));

    uint32_t n = 0;
    VK_TRY(vkEnumeratePhysicalDevices(inst_, &n, nullptr));
    if (n == 0) return FailMsg("Không có thiết bị Vulkan");
    std::vector<VkPhysicalDevice> pds(n);
    VK_TRY(vkEnumeratePhysicalDevices(inst_, &n, pds.data()));
    pd_ = pds[0];

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(pd_, &props);
    LOGI("GPU: %s, Vulkan %u.%u", props.deviceName, VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion));
    if (props.apiVersion < VK_API_VERSION_1_1) return FailMsg("Cần Vulkan >= 1.1");
    tsPeriod_ = props.limits.timestampPeriod;
    vkGetPhysicalDeviceMemoryProperties(pd_, &memProps_);

    // Queue family có graphics + compute
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(pd_, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qprops(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(pd_, &qn, qprops.data());
    bool found = false;
    for (uint32_t i = 0; i < qn; ++i) {
        const VkQueueFlags want = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        if ((qprops[i].queueFlags & want) == want) {
            qf_ = i;
            found = true;
            const uint32_t bits = qprops[i].timestampValidBits;
            tsValid_ = bits > 0 && props.limits.timestampComputeAndGraphics;
            tsMask_ = bits >= 64 ? ~0ull : ((1ull << bits) - 1ull);
            break;
        }
    }
    if (!found) return FailMsg("Không có queue graphics+compute");

    // Extension thiết bị bắt buộc
    const char* devExts[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };
    uint32_t en = 0;
    vkEnumerateDeviceExtensionProperties(pd_, nullptr, &en, nullptr);
    std::vector<VkExtensionProperties> eprops(en);
    vkEnumerateDeviceExtensionProperties(pd_, nullptr, &en, eprops.data());
    for (const char* want : devExts) {
        bool ok = false;
        for (const auto& e : eprops) {
            if (strcmp(e.extensionName, want) == 0) { ok = true; break; }
        }
        if (!ok) return FailMsg(std::string("Thiếu extension: ") + want);
    }

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = qf_;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = static_cast<uint32_t>(sizeof(devExts) / sizeof(devExts[0]));
    dci.ppEnabledExtensionNames = devExts;
    VK_TRY(vkCreateDevice(pd_, &dci, nullptr, &dev_));
    vkGetDeviceQueue(dev_, qf_, 0, &queue_);

    pfnGetAhbProps_ = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(dev_, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!pfnGetAhbProps_) return FailMsg("Không lấy được vkGetAndroidHardwareBufferPropertiesANDROID");

    // Command pool + buffer
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpi.queueFamilyIndex = qf_;
    VK_TRY(vkCreateCommandPool(dev_, &cpi, nullptr, &pool_));
    VkCommandBuffer cbs[4];
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool_;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 4;
    VK_TRY(vkAllocateCommandBuffers(dev_, &cai, cbs));
    cmdInit_ = cbs[0];
    cmdPre_ = cbs[1];
    cmdWarp_ = cbs[2];
    cmdBlit_ = cbs[3];

    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VK_TRY(vkCreateFence(dev_, &fci, nullptr, &fenceA_));
    VK_TRY(vkCreateFence(dev_, &fci, nullptr, &fenceB_));
    VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VK_TRY(vkCreateSemaphore(dev_, &semi, nullptr, &acquireSem_));

    VkQueryPoolCreateInfo qpi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpi.queryCount = 8;
    VK_TRY(vkCreateQueryPool(dev_, &qpi, nullptr, &qp_));

    if (!MakeBuffer(metrics_, 64)) return false;
    if (!CreateDescriptors()) return false;
    if (!CreatePipelines(assets)) return false;
    return true;
}

// ---------------------------------------------------------------------------------------------
// Descriptor + pipeline
// ---------------------------------------------------------------------------------------------

bool Pipeline::CreateDescriptors() {
    VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.maxLod = 0.0f;
    VK_TRY(vkCreateSampler(dev_, &sci, nullptr, &sampler_));

    // 0 prev | 1 cur | 2 lowPrev | 3 lowCur | 4 nnIn | 5 nnFlow | 6 flow(storage) | 7 flow(sampled)
    // 8 out | 9 metrics | 10 aux(storage) | 11 aux(sampled)
    const VkDescriptorType CIS = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    const VkDescriptorType SI = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    const VkDescriptorType SB = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    const VkDescriptorType types[kBindingCount] = {CIS, CIS, SI, SI, SB, SB, SI, CIS, SI, SB, SI, CIS};
    VkDescriptorSetLayoutBinding b[kBindingCount];
    for (int i = 0; i < kBindingCount; ++i) {
        b[i] = {};
        b[i].binding = static_cast<uint32_t>(i);
        b[i].descriptorType = types[i];
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dli.bindingCount = kBindingCount;
    dli.pBindings = b;
    VK_TRY(vkCreateDescriptorSetLayout(dev_, &dli, nullptr, &dsl_));

    VkPushConstantRange pcr{};
    pcr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pcr.offset = 0;
    pcr.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &dsl_;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pcr;
    VK_TRY(vkCreatePipelineLayout(dev_, &pli, nullptr, &pl_));

    VkDescriptorPoolSize sizes[3] = {{CIS, 4}, {SI, 5}, {SB, 3}};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpi.maxSets = 1;
    dpi.poolSizeCount = 3;
    dpi.pPoolSizes = sizes;
    VK_TRY(vkCreateDescriptorPool(dev_, &dpi, nullptr, &dpool_));

    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dai.descriptorPool = dpool_;
    dai.descriptorSetCount = 1;
    dai.pSetLayouts = &dsl_;
    VK_TRY(vkAllocateDescriptorSets(dev_, &dai, &ds_));
    return true;
}

VkPipeline Pipeline::MakeComputePipeline(AAssetManager* assets, const char* name) {
    std::string path = std::string("shaders/") + name + ".comp.spv";
    AAsset* a = AAssetManager_open(assets, path.c_str(), AASSET_MODE_BUFFER);
    if (!a) {
        FailMsg("Không mở được asset " + path);
        return VK_NULL_HANDLE;
    }
    const size_t len = static_cast<size_t>(AAsset_getLength(a));
    std::vector<uint32_t> code((len + 3) / 4);
    memcpy(code.data(), AAsset_getBuffer(a), len);
    AAsset_close(a);

    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = len;
    smi.pCode = code.data();
    VkShaderModule mod = VK_NULL_HANDLE;
    VkResult r = vkCreateShaderModule(dev_, &smi, nullptr, &mod);
    if (r != VK_SUCCESS) {
        Fail("vkCreateShaderModule", r);
        return VK_NULL_HANDLE;
    }
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpi.stage.module = mod;
    cpi.stage.pName = "main";
    cpi.layout = pl_;
    VkPipeline p = VK_NULL_HANDLE;
    r = vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cpi, nullptr, &p);
    vkDestroyShaderModule(dev_, mod, nullptr);
    if (r != VK_SUCCESS) {
        Fail(("vkCreateComputePipelines " + std::string(name)).c_str(), r);
        return VK_NULL_HANDLE;
    }
    return p;
}

bool Pipeline::CreatePipelines(AAssetManager* assets) {
    pPre_ = MakeComputePipeline(assets, "preprocess");
    pBlock_ = MakeComputePipeline(assets, "blockmatch");
    pFlowPack_ = MakeComputePipeline(assets, "flowpack");
    pWarp_ = MakeComputePipeline(assets, "warp");
    return pPre_ && pBlock_ && pFlowPack_ && pWarp_;
}

void Pipeline::UpdateDescriptors(VkImageView prev, VkImageView cur) {
    const VkImageLayout RO = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    const VkImageLayout GEN = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo ii[9] = {
        {sampler_, prev, RO},               // 0
        {sampler_, cur, RO},                // 1
        {VK_NULL_HANDLE, lowPrev_.view, GEN},  // 2
        {VK_NULL_HANDLE, lowCur_.view, GEN},   // 3
        {VK_NULL_HANDLE, flow_.view, GEN},     // 6
        {sampler_, flow_.view, GEN},           // 7
        {VK_NULL_HANDLE, out_.view, GEN},      // 8
        {VK_NULL_HANDLE, aux_.view, GEN},      // 10
        {sampler_, aux_.view, GEN},            // 11
    };
    VkDescriptorBufferInfo bi[3] = {
        {nnIn_.buf, 0, VK_WHOLE_SIZE},
        {nnFlow_.buf, 0, VK_WHOLE_SIZE},
        {metrics_.buf, 0, VK_WHOLE_SIZE},
    };
    struct Map { uint32_t binding; VkDescriptorType type; int img; int buf; };
    const VkDescriptorType CIS = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    const VkDescriptorType SI = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    const VkDescriptorType SB = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    const Map map[kBindingCount] = {
        {0, CIS, 0, -1}, {1, CIS, 1, -1}, {2, SI, 2, -1},  {3, SI, 3, -1},
        {4, SB, -1, 0},  {5, SB, -1, 1},  {6, SI, 4, -1},  {7, CIS, 5, -1},
        {8, SI, 6, -1},  {9, SB, -1, 2},  {10, SI, 7, -1}, {11, CIS, 8, -1},
    };
    VkWriteDescriptorSet w[kBindingCount];
    for (int i = 0; i < kBindingCount; ++i) {
        w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w[i].dstSet = ds_;
        w[i].dstBinding = map[i].binding;
        w[i].descriptorCount = 1;
        w[i].descriptorType = map[i].type;
        if (map[i].img >= 0) w[i].pImageInfo = &ii[map[i].img];
        if (map[i].buf >= 0) w[i].pBufferInfo = &bi[map[i].buf];
    }
    vkUpdateDescriptorSets(dev_, kBindingCount, w, 0, nullptr);
}

// ---------------------------------------------------------------------------------------------
// Bộ nhớ
// ---------------------------------------------------------------------------------------------

uint32_t Pipeline::FindMemType(uint32_t bits, VkMemoryPropertyFlags want) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; ++i) {
        if (((bits >> i) & 1u) && (memProps_.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

void Pipeline::DestroyImg(Img& im) {
    if (im.view) vkDestroyImageView(dev_, im.view, nullptr);
    if (im.img) vkDestroyImage(dev_, im.img, nullptr);
    if (im.mem) vkFreeMemory(dev_, im.mem, nullptr);
    im = Img{};
}

bool Pipeline::MakeImage(Img& im, uint32_t w, uint32_t h, VkFormat fmt, VkImageUsageFlags usage) {
    DestroyImg(im);
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(vkCreateImage(dev_, &ci, nullptr, &im.img));

    VkMemoryRequirements mr;
    vkGetImageMemoryRequirements(dev_, im.img, &mr);
    uint32_t mt = FindMemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mt == UINT32_MAX) mt = FindMemType(mr.memoryTypeBits, 0);
    if (mt == UINT32_MAX) return FailMsg("Không có memory type cho image");
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = mt;
    VK_TRY(vkAllocateMemory(dev_, &mai, nullptr, &im.mem));
    VK_TRY(vkBindImageMemory(dev_, im.img, im.mem, 0));

    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = im.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = fmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(dev_, &vi, nullptr, &im.view));
    im.w = w;
    im.h = h;
    return true;
}

void Pipeline::DestroyBuf(Buf& b) {
    if (b.ptr) vkUnmapMemory(dev_, b.mem);
    if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
    if (b.mem) vkFreeMemory(dev_, b.mem, nullptr);
    b = Buf{};
}

bool Pipeline::MakeBuffer(Buf& b, VkDeviceSize size) {
    DestroyBuf(b);
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    ci.size = size;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_TRY(vkCreateBuffer(dev_, &ci, nullptr, &b.buf));
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev_, b.buf, &mr);
    const uint32_t mt = FindMemType(
        mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mt == UINT32_MAX) return FailMsg("Không có memory host-visible+coherent");
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = mt;
    VK_TRY(vkAllocateMemory(dev_, &mai, nullptr, &b.mem));
    VK_TRY(vkBindBufferMemory(dev_, b.buf, b.mem, 0));
    VK_TRY(vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.ptr));
    memset(b.ptr, 0, static_cast<size_t>(size));
    b.size = size;
    return true;
}

bool Pipeline::TransitionToGeneral(const std::vector<VkImage>& images) {
    VK_TRY(vkResetCommandBuffer(cmdInit_, 0));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY(vkBeginCommandBuffer(cmdInit_, &bi));
    std::vector<VkImageMemoryBarrier> bs;
    for (VkImage img : images) {
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        bs.push_back(b);
    }
    vkCmdPipelineBarrier(cmdInit_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, static_cast<uint32_t>(bs.size()), bs.data());
    VK_TRY(vkEndCommandBuffer(cmdInit_));
    return SubmitAndWait(cmdInit_, fenceA_);
}

bool Pipeline::EnsureOut(uint32_t w, uint32_t h) {
    if (out_.w == w && out_.h == h && out_.img) return true;
    vkDeviceWaitIdle(dev_);
    if (!MakeImage(out_, w, h, VK_FORMAT_R8G8B8A8_UNORM,
                   VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        return false;
    }
    return TransitionToGeneral({out_.img});
}

bool Pipeline::Configure(int procW, int procH) {
    std::lock_guard<std::mutex> lk(mu_);
    if (procW <= 0 || procH <= 0 || (procW % 32) != 0 || (procH % 32) != 0) {
        return FailMsg("procW/procH phải là bội số 32");
    }
    if (procW == procW_ && procH == procH_) return true;
    vkDeviceWaitIdle(dev_);
    curPrev_ = curCur_ = nullptr;
    procW_ = procW;
    procH_ = procH;
    flowW_ = procW / 2;
    flowH_ = procH / 2;
    const VkFormat f8 = VK_FORMAT_R8G8B8A8_UNORM;
    const VkFormat f16 = VK_FORMAT_R16G16B16A16_SFLOAT;
    if (!MakeImage(lowPrev_, procW_, procH_, f8, VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    if (!MakeImage(lowCur_, procW_, procH_, f8, VK_IMAGE_USAGE_STORAGE_BIT)) return false;
    const VkImageUsageFlags su = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (!MakeImage(flow_, flowW_, flowH_, f16, su)) return false;
    if (!MakeImage(aux_, flowW_, flowH_, f16, su)) return false;
    if (!MakeBuffer(nnIn_, static_cast<VkDeviceSize>(6) * procW_ * procH_ * sizeof(float))) return false;
    if (!MakeBuffer(nnFlow_, static_cast<VkDeviceSize>(5) * flowW_ * flowH_ * sizeof(float))) return false;
    return TransitionToGeneral({lowPrev_.img, lowCur_.img, flow_.img, aux_.img});
}

float* Pipeline::NnInput(size_t* bytes) {
    std::lock_guard<std::mutex> lk(mu_);
    if (bytes) *bytes = static_cast<size_t>(nnIn_.size);
    return static_cast<float*>(nnIn_.ptr);
}

float* Pipeline::NnFlow(size_t* bytes) {
    std::lock_guard<std::mutex> lk(mu_);
    if (bytes) *bytes = static_cast<size_t>(nnFlow_.size);
    return static_cast<float*>(nnFlow_.ptr);
}

// ---------------------------------------------------------------------------------------------
// AHardwareBuffer -> VkImage
// ---------------------------------------------------------------------------------------------

Pipeline::Ahb* Pipeline::ImportAhb(AHardwareBuffer* ahb) {
    uint64_t id = 0;
    AHardwareBuffer_getId(ahb, &id);
    auto it = ahb_.find(id);
    if (it != ahb_.end()) return &it->second;

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);

    VkAndroidHardwareBufferFormatPropertiesANDROID fmt{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &fmt;
    VkResult r = pfnGetAhbProps_(dev_, ahb, &props);
    if (r != VK_SUCCESS) {
        Fail("vkGetAndroidHardwareBufferPropertiesANDROID", r);
        return nullptr;
    }
    if (fmt.format == VK_FORMAT_UNDEFINED) {
        FailMsg("AHardwareBuffer dùng external format (cần YCbCr), chưa hỗ trợ");
        return nullptr;
    }

    Ahb a;
    VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.pNext = &ext;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt.format;
    ci.extent = {desc.width, desc.height, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    r = vkCreateImage(dev_, &ci, nullptr, &a.img);
    if (r != VK_SUCCESS) {
        Fail("vkCreateImage(AHB)", r);
        return nullptr;
    }

    VkImportAndroidHardwareBufferInfoANDROID imp{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    imp.buffer = ahb;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    ded.pNext = &imp;
    ded.image = a.img;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.pNext = &ded;
    mai.allocationSize = props.allocationSize;
    mai.memoryTypeIndex = FindMemType(props.memoryTypeBits, 0);
    r = vkAllocateMemory(dev_, &mai, nullptr, &a.mem);
    if (r == VK_SUCCESS) r = vkBindImageMemory(dev_, a.img, a.mem, 0);
    if (r != VK_SUCCESS) {
        Fail("import AHB memory", r);
        if (a.mem) vkFreeMemory(dev_, a.mem, nullptr);
        vkDestroyImage(dev_, a.img, nullptr);
        return nullptr;
    }

    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = a.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = fmt.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    r = vkCreateImageView(dev_, &vi, nullptr, &a.view);
    if (r != VK_SUCCESS) {
        Fail("vkCreateImageView(AHB)", r);
        vkFreeMemory(dev_, a.mem, nullptr);
        vkDestroyImage(dev_, a.img, nullptr);
        return nullptr;
    }
    a.w = desc.width;
    a.h = desc.height;
    return &(ahb_[id] = a);
}

void Pipeline::DestroyAhbCache() {
    for (auto& kv : ahb_) {
        vkDestroyImageView(dev_, kv.second.view, nullptr);
        vkDestroyImage(dev_, kv.second.img, nullptr);
        vkFreeMemory(dev_, kv.second.mem, nullptr);
    }
    ahb_.clear();
    curPrev_ = curCur_ = nullptr;
}

// ---------------------------------------------------------------------------------------------
// Swapchain
// ---------------------------------------------------------------------------------------------

bool Pipeline::SetSurface(ANativeWindow* window) {
    std::lock_guard<std::mutex> lk(mu_);
    vkDeviceWaitIdle(dev_);
    DestroySwapchain();
    if (surface_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(inst_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    scDirty_ = false;
    if (!window_) return true;
    return CreateSwapchain();
}

void Pipeline::DestroySwapchain() {
    for (VkSemaphore s : renderDone_) vkDestroySemaphore(dev_, s, nullptr);
    renderDone_.clear();
    scImages_.clear();
    if (sc_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(dev_, sc_, nullptr);
        sc_ = VK_NULL_HANDLE;
    }
}

bool Pipeline::CreateSwapchain() {
    if (!window_) return true;
    if (surface_ == VK_NULL_HANDLE) {
        VkAndroidSurfaceCreateInfoKHR si{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
        si.window = window_;
        VK_TRY(vkCreateAndroidSurfaceKHR(inst_, &si, nullptr, &surface_));
    }
    VkBool32 supported = VK_FALSE;
    VK_TRY(vkGetPhysicalDeviceSurfaceSupportKHR(pd_, qf_, surface_, &supported));
    if (!supported) return FailMsg("Queue không present được lên Surface này");

    VkSurfaceCapabilitiesKHR caps;
    VK_TRY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd_, surface_, &caps));
    if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
        return FailMsg("Swapchain không hỗ trợ TRANSFER_DST");
    }

    uint32_t fn = 0;
    VK_TRY(vkGetPhysicalDeviceSurfaceFormatsKHR(pd_, surface_, &fn, nullptr));
    std::vector<VkSurfaceFormatKHR> fmts(fn);
    VK_TRY(vkGetPhysicalDeviceSurfaceFormatsKHR(pd_, surface_, &fn, fmts.data()));
    if (fmts.empty()) return FailMsg("Không có surface format");
    VkSurfaceFormatKHR chosen = fmts[0];
    for (const auto& f : fmts) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM) { chosen = f; break; }
    }
    if (chosen.format != VK_FORMAT_R8G8B8A8_UNORM) {
        for (const auto& f : fmts) {
            if (f.format == VK_FORMAT_B8G8R8A8_UNORM) { chosen = f; break; }
        }
    }

    VkExtent2D ext;
    ext.width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window_)));
    ext.height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window_)));
    ext.width = std::clamp(ext.width, caps.minImageExtent.width, std::max(caps.minImageExtent.width, caps.maxImageExtent.width));
    ext.height = std::clamp(ext.height, caps.minImageExtent.height, std::max(caps.minImageExtent.height, caps.maxImageExtent.height));

    uint32_t imageCount = std::max(3u, caps.minImageCount);
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) imageCount = caps.maxImageCount;

    VkCompositeAlphaFlagBitsKHR composite = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if (!(caps.supportedCompositeAlpha & composite)) {
        const VkCompositeAlphaFlagBitsKHR alts[] = {
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR};
        for (auto c : alts) {
            if (caps.supportedCompositeAlpha & c) { composite = c; break; }
        }
    }
    const VkSurfaceTransformFlagBitsKHR pre =
        (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            : caps.currentTransform;

    VkSwapchainCreateInfoKHR sc{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sc.surface = surface_;
    sc.minImageCount = imageCount;
    sc.imageFormat = chosen.format;
    sc.imageColorSpace = chosen.colorSpace;
    sc.imageExtent = ext;
    sc.imageArrayLayers = 1;
    sc.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    sc.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    sc.preTransform = pre;
    sc.compositeAlpha = composite;
    sc.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    sc.clipped = VK_TRUE;
    VK_TRY(vkCreateSwapchainKHR(dev_, &sc, nullptr, &sc_));

    uint32_t cnt = 0;
    VK_TRY(vkGetSwapchainImagesKHR(dev_, sc_, &cnt, nullptr));
    scImages_.resize(cnt);
    VK_TRY(vkGetSwapchainImagesKHR(dev_, sc_, &cnt, scImages_.data()));
    renderDone_.resize(cnt);
    VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < cnt; ++i) {
        VK_TRY(vkCreateSemaphore(dev_, &semi, nullptr, &renderDone_[i]));
    }
    scExtent_ = ext;
    LOGI("Swapchain %ux%u, %u ảnh, format %d", ext.width, ext.height, cnt, static_cast<int>(chosen.format));
    return true;
}

// ---------------------------------------------------------------------------------------------
// Lệnh dùng chung
// ---------------------------------------------------------------------------------------------

bool Pipeline::SubmitAndWait(VkCommandBuffer cb, VkFence fence) {
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    VK_TRY(vkQueueSubmit(queue_, 1, &si, fence));
    VK_TRY(vkWaitForFences(dev_, 1, &fence, VK_TRUE, UINT64_MAX));
    VK_TRY(vkResetFences(dev_, 1, &fence));
    return true;
}

// Nhận quyền sở hữu ảnh từ producer ngoài (compositor) trước khi sample.
void Pipeline::CmdAcquire(VkCommandBuffer cb, Ahb* a) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    b.dstQueueFamilyIndex = qf_;
    b.image = a->img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &b);
}

// Trả ảnh về producer sau khi dùng xong.
void Pipeline::CmdRelease(VkCommandBuffer cb, Ahb* a) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    b.dstAccessMask = 0;
    b.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = qf_;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    b.image = a->img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &b);
}

void Pipeline::CmdMemBarrier(VkCommandBuffer cb, VkPipelineStageFlags dstStages, VkAccessFlags dstAccess) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = dstAccess;
    vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, dstStages, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

bool Pipeline::ReadTimestamps(uint32_t first, uint32_t count, double* msOut) {
    if (!tsValid_ || count < 2) return false;
    uint64_t t[8] = {};
    VkResult r = vkGetQueryPoolResults(dev_, qp_, first, count, sizeof(uint64_t) * count, t,
                                       sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
    if (r != VK_SUCCESS) return false;
    for (uint32_t i = 0; i + 1 < count; ++i) {
        const uint64_t d = ((t[i + 1] & tsMask_) - (t[i] & tsMask_)) & tsMask_;
        msOut[i] = static_cast<double>(d) * tsPeriod_ / 1e6;
    }
    return true;
}

// ---------------------------------------------------------------------------------------------
// Giai đoạn 1
// ---------------------------------------------------------------------------------------------

int Pipeline::Begin(AHardwareBuffer* prev, AHardwareBuffer* cur, bool nn, bool blockMatch) {
    std::lock_guard<std::mutex> lk(mu_);
    const auto t0 = Clock::now();
    if (procW_ == 0) {
        FailMsg("Begin gọi trước Configure");
        return kBeginError;
    }
    if (ahb_.size() > kMaxAhbCache) DestroyAhbCache();
    Ahb* a = ImportAhb(prev);
    Ahb* b = a ? ImportAhb(cur) : nullptr;
    if (!a || !b) return kBeginError;
    if (!EnsureOut(b->w, b->h)) return kBeginError;
    curPrev_ = a;
    curCur_ = b;
    UpdateDescriptors(a->view, b->view);
    memset(metrics_.ptr, 0, 64);

    pc_ = PushConstants{};
    pc_.srcW = static_cast<int32_t>(b->w);
    pc_.srcH = static_cast<int32_t>(b->h);
    pc_.lowW = procW_;
    pc_.lowH = procH_;
    pc_.flowW = flowW_;
    pc_.flowH = flowH_;
    pc_.outW = static_cast<int32_t>(out_.w);
    pc_.outH = static_cast<int32_t>(out_.h);
    pc_.t = 0.5f;
    pc_.mode = 1;
    pc_.useNN = nn ? 1 : 0;
    pc_.flowScaleX = 1.0f / static_cast<float>(procW_);
    pc_.flowScaleY = 1.0f / static_cast<float>(procH_);
    pc_.staticThr = 0.02f;
    pc_.occThr = 0.30f;

    VkCommandBuffer cb = cmdPre_;
    if (vkResetCommandBuffer(cb, 0) != VK_SUCCESS) { FailMsg("reset cmdPre"); return kBeginError; }
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) { FailMsg("begin cmdPre"); return kBeginError; }
    if (tsValid_) {
        vkCmdResetQueryPool(cb, qp_, 0, 8);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp_, 0);
    }
    CmdAcquire(cb, a);
    if (b != a) CmdAcquire(cb, b);

    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl_, 0, 1, &ds_, 0, nullptr);
    vkCmdPushConstants(cb, pl_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc_);

    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pPre_);
    vkCmdDispatch(cb, CeilDiv(procW_, 8), CeilDiv(procH_, 8), 1);
    CmdMemBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                  VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_HOST_READ_BIT);
    if (tsValid_) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp_, 1);

    if (blockMatch) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pBlock_);
        const int bw = static_cast<int>(CeilDiv(procW_, kBlock));
        const int bh = static_cast<int>(CeilDiv(procH_, kBlock));
        vkCmdDispatch(cb, CeilDiv(bw, 8), CeilDiv(bh, 8), 1);
        CmdMemBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                      VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_HOST_READ_BIT);
    }
    if (tsValid_) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp_, 2);
    if (vkEndCommandBuffer(cb) != VK_SUCCESS) { FailMsg("end cmdPre"); return kBeginError; }
    if (!SubmitAndWait(cb, fenceA_)) return kBeginError;

    double ms[2] = {0, 0};
    if (ReadTimestamps(0, 3, ms)) {
        stats_[0] = static_cast<float>(ms[0]);
        stats_[1] = blockMatch ? static_cast<float>(ms[1]) : 0.f;
    }

    const uint32_t* m = static_cast<const uint32_t*>(metrics_.ptr);
    const float meanDiff = static_cast<float>(m[0]) / (255.0f * static_cast<float>(procW_) * static_cast<float>(procH_));
    float boundaryFrac = 0.f, residual = 0.f;
    if (blockMatch && m[2] > 0) {
        boundaryFrac = static_cast<float>(m[1]) / static_cast<float>(m[2]);
        residual = static_cast<float>(m[3]) / (255.0f * static_cast<float>(m[2]));
    }
    stats_[3] = meanDiff;
    stats_[4] = boundaryFrac;
    stats_[5] = residual;
    stats_[6] = static_cast<float>(MsSince(t0));

    if (meanDiff > kSceneCutDiff) return kBeginSceneCut;
    if (blockMatch && boundaryFrac > kLargeMotionFrac) return kBeginLargeMotion;
    return kBeginOk;
}

// ---------------------------------------------------------------------------------------------
// Giai đoạn 2
// ---------------------------------------------------------------------------------------------

bool Pipeline::Finish(bool interp, bool nnFlowReady, float gapMs) {
    std::lock_guard<std::mutex> lk(mu_);
    if (!curPrev_ || !curCur_) return FailMsg("Finish gọi khi chưa có Begin");
    presentWaitMs_ = 0.0;
    bool ok = true;
    if (interp) {
        ok = OutPass(1, nnFlowReady, /*acquire=*/false, /*release=*/false);
        // Nhịp present: frame thật cách frame nội suy đúng nửa chu kỳ nguồn.
        if (gapMs > 0.5f && gapMs < 60.f) {
            std::this_thread::sleep_for(std::chrono::microseconds(static_cast<int64_t>(gapMs * 1000.f)));
        }
    }
    const bool ok2 = OutPass(0, false, /*acquire=*/false, /*release=*/true);
    stats_[7] = static_cast<float>(presentWaitMs_);
    curPrev_ = curCur_ = nullptr;
    return ok && ok2;
}

bool Pipeline::PresentReal(AHardwareBuffer* cur) {
    std::lock_guard<std::mutex> lk(mu_);
    if (procW_ == 0) return FailMsg("PresentReal gọi trước Configure");
    if (ahb_.size() > kMaxAhbCache) DestroyAhbCache();
    Ahb* b = ImportAhb(cur);
    if (!b) return false;
    if (!EnsureOut(b->w, b->h)) return false;
    curPrev_ = curCur_ = b;
    UpdateDescriptors(b->view, b->view);
    pc_ = PushConstants{};
    pc_.srcW = static_cast<int32_t>(b->w);
    pc_.srcH = static_cast<int32_t>(b->h);
    pc_.lowW = procW_;
    pc_.lowH = procH_;
    pc_.flowW = flowW_;
    pc_.flowH = flowH_;
    pc_.outW = static_cast<int32_t>(out_.w);
    pc_.outH = static_cast<int32_t>(out_.h);
    presentWaitMs_ = 0.0;
    const bool ok = OutPass(0, false, /*acquire=*/true, /*release=*/true);
    stats_[7] = static_cast<float>(presentWaitMs_);
    curPrev_ = curCur_ = nullptr;
    return ok;
}

// Một lượt: (flow-pack) + warp/blend/occlusion (hoặc chép frame) vào out_, rồi blit lên swapchain và present.
bool Pipeline::OutPass(int mode, bool packFlow, bool acquire, bool release) {
    if (sc_ == VK_NULL_HANDLE && !scDirty_) return true;  // chưa có Surface: bỏ qua im lặng
    if (scDirty_) {
        vkDeviceWaitIdle(dev_);
        DestroySwapchain();
        scDirty_ = false;
        if (!CreateSwapchain()) return false;
    }
    pc_.mode = mode;

    // 1) Compute
    VK_TRY(vkResetCommandBuffer(cmdWarp_, 0));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY(vkBeginCommandBuffer(cmdWarp_, &bi));
    VkCommandBuffer cb = cmdWarp_;
    if (tsValid_) {
        vkCmdResetQueryPool(cb, qp_, 3, 2);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp_, 3);
    }
    if (acquire) {
        CmdAcquire(cb, curPrev_);
        if (curCur_ != curPrev_) CmdAcquire(cb, curCur_);
    }
    vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl_, 0, 1, &ds_, 0, nullptr);
    vkCmdPushConstants(cb, pl_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pc_);
    if (packFlow) {
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pFlowPack_);
        vkCmdDispatch(cb, CeilDiv(flowW_, 8), CeilDiv(flowH_, 8), 1);
        CmdMemBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }
    vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pWarp_);
    vkCmdDispatch(cb, CeilDiv(static_cast<int>(out_.w), 8), CeilDiv(static_cast<int>(out_.h), 8), 1);
    CmdMemBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    if (release) {
        CmdRelease(cb, curPrev_);
        if (curCur_ != curPrev_) CmdRelease(cb, curCur_);
    }
    if (tsValid_) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp_, 4);
    VK_TRY(vkEndCommandBuffer(cb));
    if (!SubmitAndWait(cb, fenceA_)) return false;
    if (mode == 1) {
        double ms[1] = {0};
        if (ReadTimestamps(3, 2, ms)) stats_[2] = static_cast<float>(ms[0]);
    }

    // 2) Lấy ảnh swapchain (chặn theo vsync nhờ FIFO), blit, present
    const auto t0 = Clock::now();
    uint32_t idx = 0;
    const VkResult ar = vkAcquireNextImageKHR(dev_, sc_, kAcquireTimeoutNs, acquireSem_, VK_NULL_HANDLE, &idx);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) { scDirty_ = true; return true; }
    if (ar == VK_TIMEOUT || ar == VK_NOT_READY) return true;  // bỏ lượt, không phải lỗi
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) return Fail("vkAcquireNextImageKHR", ar);
    if (ar == VK_SUBOPTIMAL_KHR) scDirty_ = true;

    VK_TRY(vkResetCommandBuffer(cmdBlit_, 0));
    VK_TRY(vkBeginCommandBuffer(cmdBlit_, &bi));
    VkImage dst = scImages_[idx];
    VkImageMemoryBarrier toDst{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    toDst.srcAccessMask = 0;
    toDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.image = dst;
    toDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmdBlit_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toDst);

    VkImageBlit region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.srcOffsets[1] = {static_cast<int32_t>(out_.w), static_cast<int32_t>(out_.h), 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstOffsets[1] = {static_cast<int32_t>(scExtent_.width), static_cast<int32_t>(scExtent_.height), 1};
    vkCmdBlitImage(cmdBlit_, out_.img, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &region, VK_FILTER_LINEAR);

    VkImageMemoryBarrier toPresent = toDst;
    toPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toPresent.dstAccessMask = 0;
    toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    vkCmdPipelineBarrier(cmdBlit_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &toPresent);
    VK_TRY(vkEndCommandBuffer(cmdBlit_));

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &acquireSem_;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmdBlit_;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &renderDone_[idx];
    VK_TRY(vkQueueSubmit(queue_, 1, &si, fenceB_));

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &renderDone_[idx];
    pi.swapchainCount = 1;
    pi.pSwapchains = &sc_;
    pi.pImageIndices = &idx;
    const VkResult pr = vkQueuePresentKHR(queue_, &pi);
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || pr == VK_SUBOPTIMAL_KHR) {
        scDirty_ = true;
    } else if (pr != VK_SUCCESS) {
        Fail("vkQueuePresentKHR", pr);
    }
    VK_TRY(vkWaitForFences(dev_, 1, &fenceB_, VK_TRUE, UINT64_MAX));
    VK_TRY(vkResetFences(dev_, 1, &fenceB_));
    presentWaitMs_ += MsSince(t0);
    return true;
}

void Pipeline::GetStats(float* out, int n) {
    std::lock_guard<std::mutex> lk(mu_);
    const int c = std::min(n, 16);
    for (int i = 0; i < c; ++i) out[i] = stats_[i];
}

}  // namespace ff
