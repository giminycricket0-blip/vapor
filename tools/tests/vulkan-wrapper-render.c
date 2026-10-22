/* Native, offscreen sampling/graphics-pipeline probe for the Vulkan wrapper.
 *
 * Companion fixture for tools/tests/vulkan-wrapper-render.py.  It never starts
 * a window, app, Wine or a game.  It dlopens a Vulkan library (a wrapper build,
 * the unpatched baseline or the system loader), uploads caller block data into
 * a block-compressed texture, samples that texture with NEAREST filtering
 * through a real graphics pipeline (fullscreen triangle) into an offscreen
 * R8G8B8A8_UNORM colour target, reads the target back to a file and prints
 * machine-readable KEY=VALUE lines.  The host orchestrator generates the input
 * blocks, the SPIR-V and the CPU reference image, then compares the readback.
 *
 * Usage:
 *   vulkan-wrapper-render render LIBRARY FORMAT INPUT OUTPUT W H RW RH VERT_SPV FRAG_SPV
 *
 * LIBRARY  .so path (wrapper/baseline build or /system/lib64/libvulkan.so).
 * FORMAT   bc1 | bc3 | bc1_srgb | bc3_srgb | rgba8.
 * INPUT    raw block data (BC formats, block-row-major) or RGBA8 texels (rgba8).
 * OUTPUT   render-target readback, exactly RW*RH*4 bytes (RGBA8, row-major).
 * W H      texture extent in pixels; a multiple of 4 for BC formats.
 * RW RH    render-target extent in pixels (offscreen, never presented).
 * VERT_SPV FRAG_SPV  SPIR-V modules for the fullscreen triangle and the
 *                    sampling fragment shader.
 *
 * Sampling convention: the vertex shader emits one oversized triangle whose
 * interpolated uv equals (x + 0.5) / extent at every render-target pixel
 * centre, and the sampler uses NEAREST filtering with CLAMP_TO_EDGE.  Pixel
 * (x,y) therefore samples texel floor((2x+1) * W / (2*RW)) (same for y), which
 * the host reproduces with an independent CPU decode.
 *
 * Output protocol (one KEY=value per line, stdout):
 *   PROBE=render, PROBE_FORMAT=..., PROBE_WIDTH/HEIGHT=..., RENDER_WIDTH/HEIGHT=...,
 *   PAGE_SIZE=n, DLOPEN=ok, INSTANCE=ok, DEVICE=ok,
 *   DEVICE_NAME=<free text>, DEVICE_VENDOR=0x...., DEVICE_ID=0x....,
 *   DEVICE_API=x.y.z, DEVICE_DRIVER_ID/NAME/INFO,
 *   FEATURE_textureCompressionBC=0|1,
 *   FORMAT_PROPS name=... vk_format=... optimal=0x........ optimal_sampled=0|1
 *                optimal_transfer_dst=0|1,
 *   INPUT_BYTES=n, OUTPUT_BYTES=n, SPIRV_VERT_BYTES=n, SPIRV_FRAG_BYTES=n,
 *   SAMPLER=nearest_clamp_edge, RENDER_TARGET_FORMAT=rgba8_unorm,
 *   STEP_<stage>=ok for every completed stage,
 *   RESULT=ok, or RESULT=fail FAIL_STAGE=<stage> FAIL_RESULT=<n>.
 * Exit code: 0 success, 1 runtime failure, 2 usage error.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define INSTANCE_COMMANDS(X) \
   X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
   X(GetPhysicalDeviceProperties2) X(GetPhysicalDeviceFeatures) \
   X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceMemoryProperties) \
   X(GetPhysicalDeviceFormatProperties) X(CreateDevice) X(GetDeviceProcAddr)
#define DEVICE_COMMANDS(X) \
   X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) \
   X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) X(AllocateMemory) \
   X(FreeMemory) X(BindBufferMemory) X(MapMemory) X(UnmapMemory) \
   X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(BindImageMemory) \
   X(CreateImageView) X(DestroyImageView) X(CreateSampler) X(DestroySampler) \
   X(CreateShaderModule) X(DestroyShaderModule) \
   X(CreateRenderPass) X(DestroyRenderPass) X(CreateFramebuffer) X(DestroyFramebuffer) \
   X(CreatePipelineLayout) X(DestroyPipelineLayout) X(CreateGraphicsPipelines) X(DestroyPipeline) \
   X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) \
   X(CreateDescriptorPool) X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) \
   X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(FreeCommandBuffers) \
   X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) \
   X(CmdCopyBufferToImage) X(CmdCopyImageToBuffer) \
   X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdBindPipeline) X(CmdBindDescriptorSets) X(CmdDraw) \
   X(QueueSubmit) X(CreateFence) X(WaitForFences) X(DestroyFence)

#define DECLARE(name) static PFN_vk##name vk##name;
INSTANCE_COMMANDS(DECLARE)
DEVICE_COMMANDS(DECLARE)
static PFN_vkGetInstanceProcAddr get_proc;
static VkInstance instance;
static VkPhysicalDevice physical;
static VkDevice device;
static uint32_t queue_family;
static VkQueue queue;
static VkPhysicalDeviceMemoryProperties memory_properties;
static const char *g_stage = "start";

typedef struct {
   const char *name;
   VkFormat format;
   VkImageAspectFlags aspect;
   uint32_t block_bytes; /* 0 for uncompressed */
   uint32_t texel_bytes; /* 0 for block-compressed */
} ProbeFormat;

static const ProbeFormat PROBE_FORMATS[] = {
   {"bc1", VK_FORMAT_BC1_RGBA_UNORM_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, 8, 0},
   {"bc3", VK_FORMAT_BC3_UNORM_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, 16, 0},
   {"bc1_srgb", VK_FORMAT_BC1_RGBA_SRGB_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, 8, 0},
   {"bc3_srgb", VK_FORMAT_BC3_SRGB_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, 16, 0},
   {"rgba8", VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, 0, 4},
};

static int fail(VkResult result) {
   printf("RESULT=fail\nFAIL_STAGE=%s\nFAIL_RESULT=%d\n", g_stage, (int)result);
   fflush(stdout);
   return 1;
}

#define TRY(expr) do { \
   VkResult result_ = (expr); \
   if (result_ != VK_SUCCESS) return fail(result_); \
   printf("STEP_%s=ok\n", g_stage); \
   fflush(stdout); \
} while (0)

static void usage(const char *argv0) {
   fprintf(stderr,
      "Usage: %s render LIBRARY FORMAT INPUT OUTPUT W H RW RH VERT_SPV FRAG_SPV\n"
      "FORMAT: bc1 bc3 bc1_srgb bc3_srgb rgba8\n", argv0);
}

static const ProbeFormat *find_format(const char *name) {
   for (size_t i = 0; i < sizeof(PROBE_FORMATS) / sizeof(PROBE_FORMATS[0]); i++)
      if (!strcmp(PROBE_FORMATS[i].name, name)) return &PROBE_FORMATS[i];
   return NULL;
}

static size_t source_size(const ProbeFormat *format, uint32_t width, uint32_t height) {
   if (format->block_bytes)
      return (size_t)((width + 3) / 4) * ((height + 3) / 4) * format->block_bytes;
   return (size_t)width * height * format->texel_bytes;
}

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags) {
   for (uint32_t i = 0; i < memory_properties.memoryTypeCount; i++)
      if ((bits & (1u << i)) &&
          (memory_properties.memoryTypes[i].propertyFlags & flags) == flags)
         return i;
   fprintf(stderr, "FAIL stage=memory-type bits=%x flags=%x\n", bits, flags);
   exit(1);
}

static int load_file(const char *path, void **data, size_t *size) {
   FILE *file = fopen(path, "rb");
   if (!file) {
      fprintf(stderr, "FAIL stage=open-input path=%s\n", path);
      return 1;
   }
   if (fseek(file, 0, SEEK_END) || (*size = (size_t)ftell(file)) == (size_t)-1) {
      fclose(file);
      fprintf(stderr, "FAIL stage=size-input\n");
      return 1;
   }
   rewind(file);
   *data = malloc(*size ? *size : 1);
   if (!*data) { fclose(file); fprintf(stderr, "FAIL stage=alloc-input\n"); return 1; }
   if (fread(*data, 1, *size, file) != *size) {
      fclose(file);
      fprintf(stderr, "FAIL stage=read-input\n");
      return 1;
   }
   fclose(file);
   return 0;
}

static int store_file(const char *path, const void *data, size_t size) {
   FILE *file = fopen(path, "wb");
   if (!file) { fprintf(stderr, "FAIL stage=open-output path=%s\n", path); return 1; }
   if (fwrite(data, 1, size, file) != size) {
      fclose(file);
      fprintf(stderr, "FAIL stage=write-output\n");
      return 1;
   }
   fclose(file);
   return 0;
}

static VkBuffer make_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkDeviceMemory *memory) {
   VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size,
      .usage = usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
   VkBuffer buffer;
   g_stage = "buffer-create";
   VkResult result = vkCreateBuffer(device, &info, NULL, &buffer);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   VkMemoryRequirements requirements;
   vkGetBufferMemoryRequirements(device, buffer, &requirements);
   VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size,
      .memoryTypeIndex = memory_type(requirements.memoryTypeBits,
         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
   g_stage = "buffer-memory";
   result = vkAllocateMemory(device, &allocate, NULL, memory);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   g_stage = "buffer-bind";
   result = vkBindBufferMemory(device, buffer, *memory, 0);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   return buffer;
}

static VkImage make_image(VkFormat format, VkImageUsageFlags usage, uint32_t width,
                          uint32_t height, VkDeviceMemory *memory) {
   VkImageCreateInfo info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .imageType = VK_IMAGE_TYPE_2D, .format = format, .extent = {width, height, 1},
      .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
      .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = usage,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
   VkImage image;
   g_stage = "image-create";
   VkResult result = vkCreateImage(device, &info, NULL, &image);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   VkMemoryRequirements requirements;
   vkGetImageMemoryRequirements(device, image, &requirements);
   VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size,
      .memoryTypeIndex = memory_type(requirements.memoryTypeBits, 0)};
   g_stage = "image-memory";
   result = vkAllocateMemory(device, &allocate, NULL, memory);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   g_stage = "image-bind";
   result = vkBindImageMemory(device, image, *memory, 0);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   return image;
}

static VkImageView make_view(VkImage image, VkFormat format, VkImageAspectFlags aspect) {
   VkImageViewCreateInfo info = {.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      .image = image, .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = format,
      .subresourceRange = {aspect, 0, 1, 0, 1}};
   VkImageView view;
   g_stage = "image-view";
   VkResult result = vkCreateImageView(device, &info, NULL, &view);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   return view;
}

static void image_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                          VkImageLayout new_layout, VkAccessFlags src, VkAccessFlags dst,
                          VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                          VkImageAspectFlags aspect) {
   VkImageMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = src, .dstAccessMask = dst, .oldLayout = old_layout, .newLayout = new_layout,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = image,
      .subresourceRange = {aspect, 0, 1, 0, 1}};
   vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static int init_instance(const char *library_path) {
   void *library = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
   if (!library) {
      fprintf(stderr, "FAIL stage=dlopen error=%s\n", dlerror());
      return 1;
   }
   printf("DLOPEN=ok\n");
   get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vk_icdGetInstanceProcAddr");
   if (!get_proc) get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
   if (!get_proc) { fprintf(stderr, "FAIL stage=GetInstanceProcAddr\n"); return 1; }
   PFN_vkCreateInstance create_instance =
      (PFN_vkCreateInstance)get_proc(VK_NULL_HANDLE, "vkCreateInstance");
   if (!create_instance) { fprintf(stderr, "FAIL stage=vkCreateInstance\n"); return 1; }
   VkApplicationInfo application = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "Vapor wrapper render pipeline probe", .applicationVersion = 1,
      .apiVersion = VK_API_VERSION_1_2};
   VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &application};
   g_stage = "vkCreateInstance";
   TRY(create_instance(&info, NULL, &instance));
   printf("INSTANCE=ok\n");
#define LOAD_INSTANCE(name) vk##name = (PFN_vk##name)get_proc(instance, "vk" #name); \
   if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   INSTANCE_COMMANDS(LOAD_INSTANCE)
#undef LOAD_INSTANCE
   uint32_t count = 0;
   g_stage = "enumerate-physical-devices";
   TRY(vkEnumeratePhysicalDevices(instance, &count, NULL));
   if (!count) { fprintf(stderr, "FAIL stage=no-physical-device\n"); return 1; }
   VkPhysicalDevice *devices = calloc(count, sizeof(*devices));
   if (!devices) return 1;
   TRY(vkEnumeratePhysicalDevices(instance, &count, devices));
   physical = devices[0];
   free(devices);
   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(physical, &properties);
   printf("DEVICE_NAME=%s\n", properties.deviceName);
   printf("DEVICE_VENDOR=0x%04x\n", (unsigned)properties.vendorID);
   printf("DEVICE_ID=0x%04x\n", (unsigned)properties.deviceID);
   printf("DEVICE_API=%u.%u.%u\n", VK_VERSION_MAJOR(properties.apiVersion),
          VK_VERSION_MINOR(properties.apiVersion), VK_VERSION_PATCH(properties.apiVersion));
   if (properties.apiVersion >= VK_API_VERSION_1_2 && vkGetPhysicalDeviceProperties2) {
      VkPhysicalDeviceVulkan12Properties properties12 = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
      VkPhysicalDeviceProperties2 properties2 = {
         .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &properties12};
      vkGetPhysicalDeviceProperties2(physical, &properties2);
      printf("DEVICE_DRIVER_ID=0x%08x\n", (unsigned)properties12.driverID);
      printf("DEVICE_DRIVER_NAME=%s\n", properties12.driverName);
      printf("DEVICE_DRIVER_INFO=%s\n", properties12.driverInfo);
   }
   printf("PAGE_SIZE=%ld\n", sysconf(_SC_PAGESIZE));
   VkPhysicalDeviceFeatures features;
   vkGetPhysicalDeviceFeatures(physical, &features);
   printf("FEATURE_textureCompressionBC=%u\n", features.textureCompressionBC ? 1u : 0u);
   vkGetPhysicalDeviceMemoryProperties(physical, &memory_properties);
   fflush(stdout);
   return 0;
}

static int init_device(void) {
   uint32_t family_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, NULL);
   if (!family_count) { fprintf(stderr, "FAIL stage=no-queue-family\n"); return 1; }
   VkQueueFamilyProperties *families = calloc(family_count, sizeof(*families));
   if (!families) return 1;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, families);
   queue_family = UINT32_MAX;
   for (uint32_t i = 0; i < family_count; i++)
      if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
          (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { queue_family = i; break; }
   free(families);
   if (queue_family == UINT32_MAX) { fprintf(stderr, "FAIL stage=no-graphics-compute-queue\n"); return 1; }
   float priority = 1;
   VkDeviceQueueCreateInfo queue_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = queue_family, .queueCount = 1, .pQueuePriorities = &priority};
   VkPhysicalDeviceFeatures available, enabled = {0};
   vkGetPhysicalDeviceFeatures(physical, &available);
   enabled.shaderStorageImageExtendedFormats = available.shaderStorageImageExtendedFormats;
   VkDeviceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_info, .pEnabledFeatures = &enabled};
   g_stage = "vkCreateDevice";
   TRY(vkCreateDevice(physical, &info, NULL, &device));
   printf("DEVICE=ok\n");
#define LOAD_DEVICE(name) vk##name = (PFN_vk##name)vkGetDeviceProcAddr(device, "vk" #name); \
   if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   DEVICE_COMMANDS(LOAD_DEVICE)
#undef LOAD_DEVICE
   vkGetDeviceQueue(device, queue_family, 0, &queue);
   fflush(stdout);
   return 0;
}

static VkShaderModule make_shader_module(const char *path) {
   void *data = NULL;
   size_t size = 0;
   if (load_file(path, &data, &size)) return VK_NULL_HANDLE;
   if (size < 4 || (size % 4)) {
      fprintf(stderr, "FAIL stage=spirv-size path=%s size=%zu\n", path, size);
      free(data);
      return VK_NULL_HANDLE;
   }
   VkShaderModuleCreateInfo info = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = size, .pCode = data};
   VkShaderModule module;
   VkResult result = vkCreateShaderModule(device, &info, NULL, &module);
   free(data);
   if (result != VK_SUCCESS) { fail(result); return VK_NULL_HANDLE; }
   return module;
}

static int render_main(const ProbeFormat *format, const char *input_path, const char *output_path,
                       uint32_t width, uint32_t height, uint32_t render_width, uint32_t render_height,
                       const char *vert_path, const char *frag_path) {
   size_t input_size = source_size(format, width, height);
   size_t output_size = (size_t)render_width * render_height * 4;
   printf("PROBE=render\n");
   printf("PROBE_FORMAT=%s\n", format->name);
   printf("PROBE_WIDTH=%u\n", width);
   printf("PROBE_HEIGHT=%u\n", height);
   printf("RENDER_WIDTH=%u\n", render_width);
   printf("RENDER_HEIGHT=%u\n", render_height);
   printf("INPUT_BYTES=%zu\n", input_size);
   printf("OUTPUT_BYTES=%zu\n", output_size);
   printf("SAMPLER=nearest_clamp_edge\n");
   printf("RENDER_TARGET_FORMAT=rgba8_unorm\n");
   VkFormatProperties format_props;
   vkGetPhysicalDeviceFormatProperties(physical, format->format, &format_props);
   printf("FORMAT_PROPS name=%s vk_format=%u optimal=0x%08x optimal_sampled=%u "
          "optimal_transfer_dst=%u\n",
          format->name, (unsigned)format->format, (unsigned)format_props.optimalTilingFeatures,
          (format_props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? 1u : 0u,
          (format_props.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) ? 1u : 0u);
   fflush(stdout);

   void *input = NULL;
   size_t actual = 0;
   if (load_file(input_path, &input, &actual)) return 1;
   if (actual != input_size) {
      fprintf(stderr, "FAIL stage=input-size expected=%zu actual=%zu\n", input_size, actual);
      free(input);
      return 1;
   }

   /* upload buffer + readback buffer */
   VkDeviceMemory upload_memory = VK_NULL_HANDLE;
   g_stage = "upload-buffer";
   VkBuffer upload = make_buffer((VkDeviceSize)input_size,
      VK_BUFFER_USAGE_TRANSFER_SRC_BIT, &upload_memory);
   if (!upload) return 1;
   void *mapped = NULL;
   g_stage = "upload-map";
   TRY(vkMapMemory(device, upload_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
   memcpy(mapped, input, input_size);
   vkUnmapMemory(device, upload_memory);
   free(input);
   VkDeviceMemory readback_memory = VK_NULL_HANDLE;
   g_stage = "readback-buffer";
   VkBuffer readback = make_buffer((VkDeviceSize)output_size,
      VK_BUFFER_USAGE_TRANSFER_DST_BIT, &readback_memory);
   if (!readback) return 1;

   /* offscreen render target (never presented) */
   VkDeviceMemory target_memory = VK_NULL_HANDLE;
   g_stage = "render-target-image";
   VkImage target = make_image(VK_FORMAT_R8G8B8A8_UNORM,
      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
      render_width, render_height, &target_memory);
   if (!target) return 1;
   g_stage = "render-target-view";
   VkImageView target_view = make_view(target, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT);
   if (!target_view) return 1;

   /* texture */
   VkDeviceMemory texture_memory = VK_NULL_HANDLE;
   g_stage = "texture-image";
   VkImage texture = make_image(format->format,
      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
      width, height, &texture_memory);
   if (!texture) return 1;
   g_stage = "texture-view";
   VkImageView texture_view = make_view(texture, format->format, format->aspect);
   if (!texture_view) return 1;
   VkSamplerCreateInfo sampler_info = {.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
      .magFilter = VK_FILTER_NEAREST, .minFilter = VK_FILTER_NEAREST,
      .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
      .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .minLod = 0, .maxLod = 0};
   VkSampler sampler;
   g_stage = "sampler";
   TRY(vkCreateSampler(device, &sampler_info, NULL, &sampler));

   /* render pass, framebuffer */
   VkAttachmentDescription colour = {.format = VK_FORMAT_R8G8B8A8_UNORM,
      .samples = VK_SAMPLE_COUNT_1_BIT, .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
      .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
      .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
      .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
      .finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL};
   VkAttachmentReference colour_ref = {.attachment = 0,
      .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
   VkSubpassDescription subpass = {.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
      .colorAttachmentCount = 1, .pColorAttachments = &colour_ref};
   VkSubpassDependency dependency = {.srcSubpass = VK_SUBPASS_EXTERNAL, .dstSubpass = 0,
      .srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
      .srcAccessMask = 0, .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
   VkRenderPassCreateInfo render_pass_info = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
      .attachmentCount = 1, .pAttachments = &colour, .subpassCount = 1, .pSubpasses = &subpass,
      .dependencyCount = 1, .pDependencies = &dependency};
   VkRenderPass render_pass;
   g_stage = "render-pass";
   TRY(vkCreateRenderPass(device, &render_pass_info, NULL, &render_pass));
   VkFramebufferCreateInfo framebuffer_info = {.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
      .renderPass = render_pass, .attachmentCount = 1, .pAttachments = &target_view,
      .width = render_width, .height = render_height, .layers = 1};
   VkFramebuffer framebuffer;
   g_stage = "framebuffer";
   TRY(vkCreateFramebuffer(device, &framebuffer_info, NULL, &framebuffer));

   /* shaders + descriptor set */
   size_t vert_size = 0, frag_size = 0;
   g_stage = "shader-vert";
   VkShaderModule vertex_module = make_shader_module(vert_path);
   if (!vertex_module) return 1;
   g_stage = "shader-frag";
   VkShaderModule fragment_module = make_shader_module(frag_path);
   if (!fragment_module) return 1;
   {
      FILE *file = fopen(vert_path, "rb");
      if (file) { fseek(file, 0, SEEK_END); vert_size = (size_t)ftell(file); fclose(file); }
      file = fopen(frag_path, "rb");
      if (file) { fseek(file, 0, SEEK_END); frag_size = (size_t)ftell(file); fclose(file); }
   }
   printf("SPIRV_VERT_BYTES=%zu\n", vert_size);
   printf("SPIRV_FRAG_BYTES=%zu\n", frag_size);

   VkDescriptorSetLayoutBinding binding = {.binding = 0,
      .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .descriptorCount = 1,
      .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT};
   VkDescriptorSetLayoutCreateInfo set_layout_info = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
      .bindingCount = 1, .pBindings = &binding};
   VkDescriptorSetLayout set_layout;
   g_stage = "descriptor-set-layout";
   TRY(vkCreateDescriptorSetLayout(device, &set_layout_info, NULL, &set_layout));
   VkDescriptorPoolSize pool_size = {.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
      .descriptorCount = 1};
   VkDescriptorPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
      .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &pool_size};
   VkDescriptorPool descriptor_pool;
   g_stage = "descriptor-pool";
   TRY(vkCreateDescriptorPool(device, &pool_info, NULL, &descriptor_pool));
   VkDescriptorSetAllocateInfo set_info = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      .descriptorPool = descriptor_pool, .descriptorSetCount = 1, .pSetLayouts = &set_layout};
   VkDescriptorSet descriptor_set;
   g_stage = "descriptor-set";
   TRY(vkAllocateDescriptorSets(device, &set_info, &descriptor_set));
   VkDescriptorImageInfo image_info = {.sampler = sampler, .imageView = texture_view,
      .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
   VkWriteDescriptorSet write = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
      .dstSet = descriptor_set, .dstBinding = 0, .descriptorCount = 1,
      .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &image_info};
   vkUpdateDescriptorSets(device, 1, &write, 0, NULL);
   printf("STEP_descriptor-write=ok\n");

   /* pipeline */
   VkPipelineLayoutCreateInfo layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      .setLayoutCount = 1, .pSetLayouts = &set_layout};
   VkPipelineLayout pipeline_layout;
   g_stage = "pipeline-layout";
   TRY(vkCreatePipelineLayout(device, &layout_info, NULL, &pipeline_layout));
   VkPipelineShaderStageCreateInfo stages[2] = {
      {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
       .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vertex_module, .pName = "main"},
      {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
       .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fragment_module, .pName = "main"},
   };
   VkPipelineVertexInputStateCreateInfo vertex_input = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
   VkPipelineInputAssemblyStateCreateInfo input_assembly = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
      .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
   VkViewport viewport = {.x = 0, .y = 0, .width = (float)render_width,
      .height = (float)render_height, .minDepth = 0, .maxDepth = 1};
   VkRect2D scissor = {{0, 0}, {render_width, render_height}};
   VkPipelineViewportStateCreateInfo viewport_state = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
      .viewportCount = 1, .pViewports = &viewport, .scissorCount = 1, .pScissors = &scissor};
   VkPipelineRasterizationStateCreateInfo rasterizer = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
      .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE,
      .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE, .lineWidth = 1};
   VkPipelineMultisampleStateCreateInfo multisample = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
      .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
   VkPipelineColorBlendAttachmentState blend_attachment = {
      .blendEnable = VK_FALSE,
      .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                        VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT};
   VkPipelineColorBlendStateCreateInfo colour_blend = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
      .attachmentCount = 1, .pAttachments = &blend_attachment};
   VkGraphicsPipelineCreateInfo pipeline_info = {
      .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
      .stageCount = 2, .pStages = stages,
      .pVertexInputState = &vertex_input, .pInputAssemblyState = &input_assembly,
      .pViewportState = &viewport_state, .pRasterizationState = &rasterizer,
      .pMultisampleState = &multisample, .pColorBlendState = &colour_blend,
      .layout = pipeline_layout, .renderPass = render_pass, .subpass = 0};
   VkPipeline pipeline;
   g_stage = "graphics-pipeline";
   TRY(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &pipeline));

   /* command recording */
   VkCommandPoolCreateInfo command_pool_info = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = queue_family};
   VkCommandPool command_pool;
   g_stage = "command-pool";
   TRY(vkCreateCommandPool(device, &command_pool_info, NULL, &command_pool));
   VkCommandBufferAllocateInfo command_info = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = command_pool,
      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
   VkCommandBuffer command_buffer;
   g_stage = "command-buffer";
   TRY(vkAllocateCommandBuffers(device, &command_info, &command_buffer));
   VkCommandBufferBeginInfo begin_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
   g_stage = "begin-command-buffer";
   TRY(vkBeginCommandBuffer(command_buffer, &begin_info));

   g_stage = "texture-upload-barrier";
   image_barrier(command_buffer, texture, VK_IMAGE_LAYOUT_UNDEFINED,
      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, format->aspect);
   VkBufferImageCopy upload_region = {.bufferOffset = 0, .bufferRowLength = 0,
      .bufferImageHeight = 0, .imageSubresource = {format->aspect, 0, 0, 1},
      .imageOffset = {0, 0, 0}, .imageExtent = {width, height, 1}};
   vkCmdCopyBufferToImage(command_buffer, upload, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      1, &upload_region);
   printf("STEP_texture-upload=ok\n");
   image_barrier(command_buffer, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, format->aspect);
   printf("STEP_texture-sample-barrier=ok\n");

   VkClearValue clear = {.color = {{0.0f, 0.0f, 0.0f, 1.0f}}};
   VkRenderPassBeginInfo render_pass_begin = {.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
      .renderPass = render_pass, .framebuffer = framebuffer,
      .renderArea = {{0, 0}, {render_width, render_height}}, .clearValueCount = 1,
      .pClearValues = &clear};
   vkCmdBeginRenderPass(command_buffer, &render_pass_begin, VK_SUBPASS_CONTENTS_INLINE);
   vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
   vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout, 0, 1,
      &descriptor_set, 0, NULL);
   vkCmdDraw(command_buffer, 3, 1, 0, 0);
   vkCmdEndRenderPass(command_buffer);
   printf("STEP_draw=ok\n");

   VkBufferImageCopy readback_region = {.bufferOffset = 0, .bufferRowLength = 0,
      .bufferImageHeight = 0, .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .imageOffset = {0, 0, 0}, .imageExtent = {render_width, render_height, 1}};
   vkCmdCopyImageToBuffer(command_buffer, target, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
      readback, 1, &readback_region);
   VkBufferMemoryBarrier host_barrier = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
      .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .buffer = readback,
      .offset = 0, .size = VK_WHOLE_SIZE};
   vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &host_barrier, 0, NULL);
   g_stage = "end-command-buffer";
   TRY(vkEndCommandBuffer(command_buffer));

   VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
   VkFence fence;
   g_stage = "fence";
   TRY(vkCreateFence(device, &fence_info, NULL, &fence));
   VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1,
      .pCommandBuffers = &command_buffer};
   g_stage = "queue-submit";
   TRY(vkQueueSubmit(queue, 1, &submit, fence));
   g_stage = "fence-wait";
   TRY(vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ULL));
   printf("STEP_submit-wait=ok\n");
   vkDestroyFence(device, fence, NULL);

   void *readback_mapped = NULL;
   g_stage = "readback-map";
   TRY(vkMapMemory(device, readback_memory, 0, VK_WHOLE_SIZE, 0, &readback_mapped));
   g_stage = "readback-store";
   if (store_file(output_path, readback_mapped, output_size)) return 1;
   vkUnmapMemory(device, readback_memory);
   printf("WROTE_OUTPUT=ok\n");
   vkDeviceWaitIdle(device);
   g_stage = "cleanup";
   vkDestroyPipeline(device, pipeline, NULL);
   vkDestroyPipelineLayout(device, pipeline_layout, NULL);
   vkDestroyDescriptorPool(device, descriptor_pool, NULL);
   vkDestroyDescriptorSetLayout(device, set_layout, NULL);
   vkDestroyFramebuffer(device, framebuffer, NULL);
   vkDestroyRenderPass(device, render_pass, NULL);
   vkDestroyShaderModule(device, vertex_module, NULL);
   vkDestroyShaderModule(device, fragment_module, NULL);
   vkDestroySampler(device, sampler, NULL);
   vkDestroyImageView(device, texture_view, NULL);
   vkDestroyImageView(device, target_view, NULL);
   vkDestroyImage(device, texture, NULL);
   vkDestroyImage(device, target, NULL);
   vkDestroyBuffer(device, upload, NULL);
   vkDestroyBuffer(device, readback, NULL);
   vkFreeMemory(device, texture_memory, NULL);
   vkFreeMemory(device, target_memory, NULL);
   vkFreeMemory(device, upload_memory, NULL);
   vkFreeMemory(device, readback_memory, NULL);
   vkDestroyCommandPool(device, command_pool, NULL);
   vkDestroyDevice(device, NULL);
   vkDestroyInstance(instance, NULL);
   printf("CLEANUP=ok\n");
   printf("RESULT=ok\n");
   fflush(stdout);
   return 0;
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   if (argc < 2) { usage(argv[0]); return 2; }
   if (!strcmp(argv[1], "caps")) {
      if (argc != 3) { usage(argv[0]); return 2; }
      if (init_instance(argv[2])) return 1;
      for (size_t i = 0; i < sizeof(PROBE_FORMATS) / sizeof(PROBE_FORMATS[0]); i++) {
         VkFormatProperties props;
         vkGetPhysicalDeviceFormatProperties(physical, PROBE_FORMATS[i].format, &props);
         printf("FORMAT_PROPS name=%s vk_format=%u optimal=0x%08x optimal_sampled=%u "
                "optimal_transfer_dst=%u\n", PROBE_FORMATS[i].name,
                (unsigned)PROBE_FORMATS[i].format, (unsigned)props.optimalTilingFeatures,
                (props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? 1u : 0u,
                (props.optimalTilingFeatures & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) ? 1u : 0u);
      }
      printf("RESULT=ok\n");
      fflush(stdout);
      return 0;
   }
   if (strcmp(argv[1], "render")) { usage(argv[0]); return 2; }
   if (argc != 12) { usage(argv[0]); return 2; }
   const ProbeFormat *format = find_format(argv[3]);
   if (!format) { usage(argv[0]); return 2; }
   uint32_t width = (uint32_t)strtoul(argv[6], NULL, 10);
   uint32_t height = (uint32_t)strtoul(argv[7], NULL, 10);
   uint32_t render_width = (uint32_t)strtoul(argv[8], NULL, 10);
   uint32_t render_height = (uint32_t)strtoul(argv[9], NULL, 10);
   if (!width || !height || !render_width || !render_height) { usage(argv[0]); return 2; }
   if (format->block_bytes && (width % 4 || height % 4)) { usage(argv[0]); return 2; }
   if (init_instance(argv[2])) return 1;
   if (init_device()) return 1;
   return render_main(format, argv[4], argv[5], width, height, render_width, render_height,
                      argv[10], argv[11]);
}
