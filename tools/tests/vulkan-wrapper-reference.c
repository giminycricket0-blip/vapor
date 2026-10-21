/* Native, offscreen reference-image probe for the Vulkan wrapper.
 *
 * Companion fixture for tools/tests/vulkan-wrapper-reference.py.  It never
 * starts a window, app, Wine or a game: it dlopens a Vulkan library (wrapper,
 * baseline or the system loader), creates one image, uploads caller data from
 * a file, copies it back to a file and prints machine-readable KEY=VALUE lines
 * for the host-side orchestrator.
 *
 * Usage:
 *   vulkan-wrapper-reference caps LIBRARY
 *   vulkan-wrapper-reference copy LIBRARY FORMAT INPUT OUTPUT WIDTH HEIGHT
 *   vulkan-wrapper-reference blit LIBRARY FORMAT INPUT OUTPUT WIDTH HEIGHT
 *
 * LIBRARY is a .so path (a wrapper/baseline build or /system/lib64/libvulkan.so).
 * FORMAT is one of: bc1 bc3 bc7 rgba8 d16 d24s8 d32.
 *
 * copy: creates an image with VK_IMAGE_USAGE_TRANSFER_DST_BIT |
 *       VK_IMAGE_USAGE_TRANSFER_SRC_BIT (plus SAMPLED for colour formats),
 *       uploads INPUT with vkCmdCopyBufferToImage, reads it back with
 *       vkCmdCopyImageToBuffer and writes the whole destination allocation to
 *       OUTPUT.  For a block-compressed FORMAT the input file holds raw BC
 *       blocks in block-row-major order.  When the wrapper emulates BCn the
 *       backing image is R8G8B8A8_UNORM and the readback is expanded RGBA8, so
 *       OUTPUT is always max(compressed size, width*height*4) bytes; for a
 *       native BC image the readback is the compressed blocks themselves.
 *       For depth formats only the DEPTH aspect is copied and one texel in the
 *       buffer is texel_bytes wide (d16=2, d24s8=4, d32=4).
 *
 * blit: block-compressed FORMAT only.  Uploads the BC blocks into a native BC
 *       image, then uses vkCmdBlitImage (NEAREST, 1:1) into a temporary
 *       R8G8B8A8_UNORM image and copies that out.  On the system loader this
 *       exposes the driver's own BC decode without a graphics pipeline; on the
 *       wrapper the BC image is already emulated, so the blit is a pass-through
 *       of the wrapper decode.  BLIT_SRC/DST format features are reported.
 *
 * Output protocol (one KEY=value per line, stdout):
 *   PROBE=copy|blit|caps, PROBE_FORMAT=..., PROBE_WIDTH/HEIGHT=...,
 *   DLOPEN=ok, INSTANCE=ok, DEVICE_NAME=<free text>, DEVICE_VENDOR=0x...,
 *   DEVICE_ID=0x..., DEVICE_API=x.y.z, PAGE_SIZE=n,
 *   FEATURE_textureCompressionBC=0|1,
 *   FORMAT_PROPS=name=<n> vk_format=<n> optimal=0x........ linear=0x........
 *                buffer=0x........ blit_src=0|1 blit_dst=0|1,
 *   STEP_<stage>=ok for every completed stage,
 *   RESULT=ok|fail [FAIL_STAGE=<stage> FAIL_RESULT=<n>],
 *   BYTES_OUT=<n> (copy/blit only).
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
   X(GetPhysicalDeviceFeatures) X(GetPhysicalDeviceQueueFamilyProperties) \
   X(GetPhysicalDeviceMemoryProperties) X(GetPhysicalDeviceFormatProperties) \
   X(GetPhysicalDeviceProperties2) X(CreateDevice) X(GetDeviceProcAddr)
#define DEVICE_COMMANDS(X) \
   X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(CreateBuffer) X(DestroyBuffer) \
   X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) \
   X(MapMemory) X(UnmapMemory) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) \
   X(BindImageMemory) X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) \
   X(FreeCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) \
   X(CmdCopyBufferToImage) X(CmdCopyImageToBuffer) X(CmdBlitImage) X(QueueSubmit) \
   X(CreateFence) X(WaitForFences) X(DestroyFence)

#define DECLARE(name) static PFN_vk##name vk##name;
INSTANCE_COMMANDS(DECLARE)
DEVICE_COMMANDS(DECLARE)
static PFN_vkGetInstanceProcAddr get_proc;
static VkInstance instance;
static VkPhysicalDevice physical;
static VkDevice device;
static VkPhysicalDeviceMemoryProperties memory_properties;

typedef struct {
   const char *name;
   VkFormat format;
   VkImageAspectFlags aspect;
   bool colour;         /* true: colour format, false: depth format */
   uint32_t block_bytes; /* 0 for uncompressed formats */
   uint32_t texel_bytes; /* 0 for block-compressed formats */
} ProbeFormat;

static const ProbeFormat PROBE_FORMATS[] = {
   {"bc1", VK_FORMAT_BC1_RGBA_UNORM_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, true, 8, 0},
   {"bc3", VK_FORMAT_BC3_UNORM_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, true, 16, 0},
   {"bc7", VK_FORMAT_BC7_UNORM_BLOCK, VK_IMAGE_ASPECT_COLOR_BIT, true, 16, 0},
   {"rgba8", VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT, true, 0, 4},
   {"d16", VK_FORMAT_D16_UNORM, VK_IMAGE_ASPECT_DEPTH_BIT, false, 0, 2},
   {"d24s8", VK_FORMAT_D24_UNORM_S8_UINT, VK_IMAGE_ASPECT_DEPTH_BIT, false, 0, 4},
   {"d32", VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT, false, 0, 4},
};

static const struct { const char *name; VkFormat format; } CAPS_FORMATS[] = {
   {"bc1_rgb", VK_FORMAT_BC1_RGB_UNORM_BLOCK},
   {"bc1_rgba", VK_FORMAT_BC1_RGBA_UNORM_BLOCK},
   {"bc2", VK_FORMAT_BC2_UNORM_BLOCK},
   {"bc3", VK_FORMAT_BC3_UNORM_BLOCK},
   {"bc4", VK_FORMAT_BC4_UNORM_BLOCK},
   {"bc5", VK_FORMAT_BC5_UNORM_BLOCK},
   {"bc7", VK_FORMAT_BC7_UNORM_BLOCK},
   {"rgba8", VK_FORMAT_R8G8B8A8_UNORM},
   {"d16", VK_FORMAT_D16_UNORM},
   {"d16s8", VK_FORMAT_D16_UNORM_S8_UINT},
   {"d24s8", VK_FORMAT_D24_UNORM_S8_UINT},
   {"d32", VK_FORMAT_D32_SFLOAT},
   {"d32s8", VK_FORMAT_D32_SFLOAT_S8_UINT},
};

static const char *g_stage = "start";

static int fail(VkResult result) {
   printf("RESULT=fail\nFAIL_STAGE=%s\nFAIL_RESULT=%d\n", g_stage, (int)result);
   fflush(stdout);
   return 1;
}

#define TRY(call) do { \
   VkResult result_ = (call); \
   if (result_ != VK_SUCCESS) return fail(result_); \
   printf("STEP_%s=ok\n", g_stage); \
} while (0)

#define TRY_VOID(call) do { (call); printf("STEP_%s=ok\n", g_stage); } while (0)

static void usage(const char *argv0) {
   fprintf(stderr,
      "Usage: %s caps LIBRARY\n"
      "       %s copy LIBRARY FORMAT INPUT OUTPUT WIDTH HEIGHT\n"
      "       %s blit LIBRARY FORMAT INPUT OUTPUT WIDTH HEIGHT\n"
      "FORMAT: bc1 bc3 bc7 rgba8 d16 d24s8 d32 (blit: bc1 bc3 bc7)\n",
      argv0, argv0, argv0);
}

static const ProbeFormat *find_format(const char *name) {
   for (size_t i = 0; i < sizeof(PROBE_FORMATS) / sizeof(PROBE_FORMATS[0]); i++)
      if (!strcmp(PROBE_FORMATS[i].name, name)) return &PROBE_FORMATS[i];
   return NULL;
}

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags) {
   for (uint32_t i = 0; i < memory_properties.memoryTypeCount; i++)
      if ((bits & (1u << i)) &&
          (memory_properties.memoryTypes[i].propertyFlags & flags) == flags)
         return i;
   fprintf(stderr, "FAIL stage=memory-type bits=%x flags=%x\n", bits, flags);
   exit(1);
}

static void print_format_props(const char *name, VkFormat format) {
   VkFormatProperties props;
   vkGetPhysicalDeviceFormatProperties(physical, format, &props);
   const VkFormatFeatureFlags b = props.optimalTilingFeatures;
   printf("FORMAT_PROPS name=%s vk_format=%u optimal=0x%08x linear=0x%08x buffer=0x%08x "
          "optimal_transfer_src=%u optimal_transfer_dst=%u optimal_sampled=%u "
          "optimal_blit_src=%u optimal_blit_dst=%u\n",
      name, (unsigned)format, (unsigned)props.optimalTilingFeatures,
      (unsigned)props.linearTilingFeatures, (unsigned)props.bufferFeatures,
      (b & VK_FORMAT_FEATURE_TRANSFER_SRC_BIT) ? 1u : 0u,
      (b & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) ? 1u : 0u,
      (b & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? 1u : 0u,
      (b & VK_FORMAT_FEATURE_BLIT_SRC_BIT) ? 1u : 0u,
      (b & VK_FORMAT_FEATURE_BLIT_DST_BIT) ? 1u : 0u);
   fflush(stdout);
}

static int load_files(const char *path, void **buffer, size_t *size) {
   FILE *file = fopen(path, "rb");
   if (!file) { fprintf(stderr, "FAIL stage=open-input path=%s\n", path); return 1; }
   if (fseek(file, 0, SEEK_END) || (*size = (size_t)ftell(file)) == (size_t)-1) {
      fclose(file); fprintf(stderr, "FAIL stage=size-input\n"); return 1;
   }
   rewind(file);
   *buffer = malloc(*size ? *size : 1);
   if (!*buffer) { fclose(file); fprintf(stderr, "FAIL stage=alloc-input\n"); return 1; }
   if (fread(*buffer, 1, *size, file) != *size) {
      fclose(file); fprintf(stderr, "FAIL stage=read-input\n"); return 1;
   }
   fclose(file);
   return 0;
}

static int store_file(const char *path, const void *buffer, size_t size) {
   FILE *file = fopen(path, "wb");
   if (!file) { fprintf(stderr, "FAIL stage=open-output path=%s\n", path); return 1; }
   if (fwrite(buffer, 1, size, file) != size) {
      fclose(file); fprintf(stderr, "FAIL stage=write-output\n"); return 1;
   }
   fclose(file);
   return 0;
}

static uint32_t memory_type_for(VkMemoryRequirements requirements, VkMemoryPropertyFlags flags) {
   return memory_type(requirements.memoryTypeBits, flags);
}

static VkBuffer make_buffer(VkDeviceSize size, VkBufferUsageFlags usage,
                            VkDeviceMemory *memory, bool zero) {
   VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
      .size = size, .usage = usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
   VkBuffer buffer;
   TRY_VOID(vkCreateBuffer(device, &info, NULL, &buffer));
   VkMemoryRequirements requirements;
   vkGetBufferMemoryRequirements(device, buffer, &requirements);
   VkMemoryAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size,
      .memoryTypeIndex = memory_type_for(requirements,
         VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
   TRY_VOID(vkAllocateMemory(device, &alloc, NULL, memory));
   TRY_VOID(vkBindBufferMemory(device, buffer, *memory, 0));
   if (zero) {
      void *mapped;
      TRY_VOID(vkMapMemory(device, *memory, 0, VK_WHOLE_SIZE, 0, &mapped));
      memset(mapped, 0, (size_t)size);
      vkUnmapMemory(device, *memory);
   }
   return buffer;
}

static VkDeviceMemory make_image_memory(VkImage image) {
   VkMemoryRequirements requirements;
   vkGetImageMemoryRequirements(device, image, &requirements);
   VkMemoryAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size,
      .memoryTypeIndex = memory_type_for(requirements, 0)};
   VkDeviceMemory memory;
   TRY_VOID(vkAllocateMemory(device, &alloc, NULL, &memory));
   TRY_VOID(vkBindImageMemory(device, image, memory, 0));
   return memory;
}

static VkImage make_image(VkFormat format, VkImageUsageFlags usage, uint32_t width,
                          uint32_t height) {
   VkImageCreateInfo info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .imageType = VK_IMAGE_TYPE_2D, .format = format,
      .extent = {width, height, 1}, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
   VkImage image;
   TRY_VOID(vkCreateImage(device, &info, NULL, &image));
   return image;
}

static void image_barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                          VkImageLayout new_layout, VkAccessFlags src, VkAccessFlags dst,
                          VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage,
                          VkImageAspectFlags aspect) {
   VkImageMemoryBarrier info = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = src, .dstAccessMask = dst, .oldLayout = old_layout,
      .newLayout = new_layout,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = image,
      .subresourceRange = {aspect, 0, 1, 0, 1}};
   vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &info);
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
   VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "Vapor wrapper reference probe",
      .applicationVersion = 1, .apiVersion = VK_API_VERSION_1_2};
   VkInstanceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
      .pApplicationInfo = &app};
   TRY_VOID(create_instance(&info, NULL, &instance));
   printf("INSTANCE=ok\n");
#define LOAD_INSTANCE(name) vk##name = (PFN_vk##name)get_proc(instance, "vk" #name); \
   if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   INSTANCE_COMMANDS(LOAD_INSTANCE)
   uint32_t count = 0;
   TRY_VOID(vkEnumeratePhysicalDevices(instance, &count, NULL));
   if (!count) { fprintf(stderr, "FAIL stage=no-physical-device\n"); return 1; }
   VkPhysicalDevice *devices = calloc(count, sizeof(*devices));
   if (!devices) return 1;
   TRY_VOID(vkEnumeratePhysicalDevices(instance, &count, devices));
   physical = devices[0];
   free(devices);
   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(physical, &properties);
   printf("DEVICE_NAME=%s\n", properties.deviceName);
   printf("DEVICE_VENDOR=0x%04x\n", (unsigned)properties.vendorID);
   printf("DEVICE_ID=0x%04x\n", (unsigned)properties.deviceID);
   printf("DEVICE_API=%u.%u.%u\n", VK_VERSION_MAJOR(properties.apiVersion),
      VK_VERSION_MINOR(properties.apiVersion), VK_VERSION_PATCH(properties.apiVersion));
   printf("DEVICE_DRIVER_VERSION=0x%08x\n", (unsigned)properties.driverVersion);
   printf("DEVICE_TYPE=%u\n", (unsigned)properties.deviceType);
   if (properties.apiVersion >= VK_API_VERSION_1_2) {
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
   uint32_t family;
   for (family = 0; family < family_count; family++)
      if ((families[family].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
          (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) break;
   free(families);
   if (family == family_count) { fprintf(stderr, "FAIL stage=no-graphics-compute-queue\n"); return 1; }
   float priority = 1;
   VkDeviceQueueCreateInfo queue = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
   VkPhysicalDeviceFeatures available, enabled = {0};
   vkGetPhysicalDeviceFeatures(physical, &available);
   enabled.shaderStorageImageExtendedFormats = available.shaderStorageImageExtendedFormats;
   VkDeviceCreateInfo info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue, .pEnabledFeatures = &enabled};
   TRY_VOID(vkCreateDevice(physical, &info, NULL, &device));
   printf("DEVICE=ok\n");
#define LOAD_DEVICE(name) vk##name = (PFN_vk##name)vkGetDeviceProcAddr(device, "vk" #name); \
   if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   DEVICE_COMMANDS(LOAD_DEVICE)
   fflush(stdout);
   return 0;
}

static int caps_main(void) {
   for (size_t i = 0; i < sizeof(CAPS_FORMATS) / sizeof(CAPS_FORMATS[0]); i++)
      print_format_props(CAPS_FORMATS[i].name, CAPS_FORMATS[i].format);
   printf("RESULT=ok\n");
   fflush(stdout);
   return 0;
}

static size_t src_size(const ProbeFormat *format, uint32_t width, uint32_t height) {
   if (format->block_bytes)
      return (size_t)((width + 3) / 4) * ((height + 3) / 4) * format->block_bytes;
   return (size_t)width * height * format->texel_bytes;
}

static int blit_main(const ProbeFormat *format, const char *input_path,
                     const char *output_path, uint32_t width, uint32_t height) {
   if (!format->block_bytes) { fprintf(stderr, "FAIL stage=blit-requires-bc\n"); return 2; }
   VkFormatProperties props;
   vkGetPhysicalDeviceFormatProperties(physical, format->format, &props);
   bool blit_supported = (props.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0;
   printf("BLIT_SRC_SUPPORTED=%u\n", blit_supported ? 1u : 0u);
   fflush(stdout);

   size_t in_size = src_size(format, width, height);
   size_t out_size = (size_t)width * height * 4;
   void *input = NULL;
   size_t actual = 0;
   if (load_files(input_path, &input, &actual)) return 1;
   if (actual != in_size) {
      fprintf(stderr, "FAIL stage=input-size expected=%zu actual=%zu\n", in_size, actual);
      free(input);
      return 1;
   }

   VkDeviceMemory src_memory, dst_memory;
   g_stage = "src-buffer";
   VkBuffer src = make_buffer((VkDeviceSize)in_size,
      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
      VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &src_memory, false);
   g_stage = "dst-buffer";
   VkBuffer dst = make_buffer((VkDeviceSize)out_size,
      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
      &dst_memory, true);
   void *mapped;
   g_stage = "src-upload";
   TRY_VOID(vkMapMemory(device, src_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
   memcpy(mapped, input, in_size);
   vkUnmapMemory(device, src_memory);
   free(input);

   g_stage = "bc-image";
   VkImage bc_image = make_image(format->format,
      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
      width, height);
   make_image_memory(bc_image);
   g_stage = "rgba-image";
   VkImage rgba_image = make_image(VK_FORMAT_R8G8B8A8_UNORM,
      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
      width, height);
   make_image_memory(rgba_image);

   g_stage = "command-pool";
   VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      .queueFamilyIndex = 0};
   VkCommandPool pool;
   TRY_VOID(vkCreateCommandPool(device, &pool_info, NULL, &pool));
   VkCommandBufferAllocateInfo cmd_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
   VkCommandBuffer cmd;
   g_stage = "command-buffer";
   TRY_VOID(vkAllocateCommandBuffers(device, &cmd_info, &cmd));
   VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
   TRY_VOID(vkBeginCommandBuffer(cmd, &begin));

   g_stage = "barriers";
   image_barrier(cmd, bc_image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
   VkBufferImageCopy region = {.bufferOffset = 0, .bufferRowLength = 0, .bufferImageHeight = 0,
      .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .imageOffset = {0, 0, 0}, .imageExtent = {width, height, 1}};
   g_stage = "upload";
   vkCmdCopyBufferToImage(cmd, src, bc_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
   printf("STEP_upload=ok\n");
   image_barrier(cmd, bc_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
   image_barrier(cmd, rgba_image, VK_IMAGE_LAYOUT_UNDEFINED,
      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_IMAGE_ASPECT_COLOR_BIT);
   printf("STEP_barriers=ok\n");

   g_stage = "blit";
   VkImageBlit blit = {.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .srcOffsets = {{0, 0, 0}, {(int32_t)width, (int32_t)height, 1}},
      .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .dstOffsets = {{0, 0, 0}, {(int32_t)width, (int32_t)height, 1}}};
   vkCmdBlitImage(cmd, bc_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rgba_image,
      VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
   printf("STEP_blit=ok\n");
   image_barrier(cmd, rgba_image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
   g_stage = "readback";
   vkCmdCopyImageToBuffer(cmd, rgba_image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, 1, &region);
   VkBufferMemoryBarrier host = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
      .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .buffer = dst, .offset = 0, .size = VK_WHOLE_SIZE};
   vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
      0, NULL, 1, &host, 0, NULL);
   printf("STEP_readback=ok\n");
   g_stage = "end";
   TRY_VOID(vkEndCommandBuffer(cmd));
   VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
   VkFence fence;
   g_stage = "fence";
   TRY_VOID(vkCreateFence(device, &fence_info, NULL, &fence));
   VkQueue queue;
   vkGetDeviceQueue(device, 0, 0, &queue);
   VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
      .commandBufferCount = 1, .pCommandBuffers = &cmd};
   g_stage = "submit";
   TRY_VOID(vkQueueSubmit(queue, 1, &submit, fence));
   g_stage = "wait";
   TRY(vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ULL));

   g_stage = "map-output";
   TRY_VOID(vkMapMemory(device, dst_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
   if (store_file(output_path, mapped, out_size)) return 1;
   vkUnmapMemory(device, dst_memory);
   printf("STEP_output=ok\n");
   printf("BYTES_OUT=%zu\n", out_size);
   printf("RESULT=ok\n");
   fflush(stdout);
   return 0;
}

static int copy_main(const ProbeFormat *format, const char *input_path,
                     const char *output_path, uint32_t width, uint32_t height) {
   size_t in_size = src_size(format, width, height);
   size_t out_size = in_size > (size_t)width * height * 4 ? in_size
                                                          : (size_t)width * height * 4;
   printf("INPUT_BYTES=%zu\n", in_size);
   printf("ALLOC_BYTES=%zu\n", out_size);
   fflush(stdout);
   void *input = NULL;
   size_t actual = 0;
   if (load_files(input_path, &input, &actual)) return 1;
   if (actual != in_size) {
      fprintf(stderr, "FAIL stage=input-size expected=%zu actual=%zu\n", in_size, actual);
      free(input);
      return 1;
   }

   VkDeviceMemory src_memory, dst_memory;
   g_stage = "src-buffer";
   VkBuffer src = make_buffer((VkDeviceSize)in_size,
      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
      VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, &src_memory, false);
   g_stage = "dst-buffer";
   VkBuffer dst = make_buffer((VkDeviceSize)out_size,
      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
      &dst_memory, true);
   void *mapped;
   g_stage = "src-upload";
   TRY_VOID(vkMapMemory(device, src_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
   memcpy(mapped, input, in_size);
   vkUnmapMemory(device, src_memory);
   free(input);

   VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
   if (format->colour) usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
   g_stage = "image-create";
   VkImage image = make_image(format->format, usage, width, height);
   g_stage = "image-bind";
   make_image_memory(image);

   g_stage = "command-pool";
   VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
      .queueFamilyIndex = 0};
   VkCommandPool pool;
   TRY_VOID(vkCreateCommandPool(device, &pool_info, NULL, &pool));
   VkCommandBufferAllocateInfo cmd_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
   VkCommandBuffer cmd;
   g_stage = "command-buffer";
   TRY_VOID(vkAllocateCommandBuffers(device, &cmd_info, &cmd));
   VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
   TRY_VOID(vkBeginCommandBuffer(cmd, &begin));

   g_stage = "barriers";
   image_barrier(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, format->aspect);
   VkBufferImageCopy region = {.bufferOffset = 0, .bufferRowLength = 0, .bufferImageHeight = 0,
      .imageSubresource = {format->aspect, 0, 0, 1},
      .imageOffset = {0, 0, 0}, .imageExtent = {width, height, 1}};
   g_stage = "upload";
   vkCmdCopyBufferToImage(cmd, src, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
   printf("STEP_upload=ok\n");
   image_barrier(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
      VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
      VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT, format->aspect);
   VkBufferImageCopy readback = {.bufferOffset = 0, .bufferRowLength = 0,
      .bufferImageHeight = 0, .imageSubresource = {format->aspect, 0, 0, 1},
      .imageOffset = {0, 0, 0}, .imageExtent = {width, height, 1}};
   g_stage = "readback";
   vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, 1, &readback);
   VkBufferMemoryBarrier host = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
      .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .buffer = dst, .offset = 0, .size = VK_WHOLE_SIZE};
   vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
      0, NULL, 1, &host, 0, NULL);
   printf("STEP_readback=ok\n");
   g_stage = "end";
   TRY_VOID(vkEndCommandBuffer(cmd));
   VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
   VkFence fence;
   g_stage = "fence";
   TRY_VOID(vkCreateFence(device, &fence_info, NULL, &fence));
   VkQueue queue;
   vkGetDeviceQueue(device, 0, 0, &queue);
   VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
      .commandBufferCount = 1, .pCommandBuffers = &cmd};
   g_stage = "submit";
   TRY_VOID(vkQueueSubmit(queue, 1, &submit, fence));
   g_stage = "wait";
   TRY(vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ULL));

   g_stage = "map-output";
   TRY_VOID(vkMapMemory(device, dst_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
   if (store_file(output_path, mapped, out_size)) return 1;
   vkUnmapMemory(device, dst_memory);
   printf("STEP_output=ok\n");
   printf("BYTES_OUT=%zu\n", out_size);
   printf("RESULT=ok\n");
   fflush(stdout);
   return 0;
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   if (argc < 3) { usage(argv[0]); return 2; }
   bool caps = !strcmp(argv[1], "caps");
   bool blit = !strcmp(argv[1], "blit");
   bool copy = !strcmp(argv[1], "copy");
   if (!caps && !blit && !copy) { usage(argv[0]); return 2; }
   if (caps) {
      if (argc != 3) { usage(argv[0]); return 2; }
      printf("PROBE=caps\n");
   } else {
      if (argc != 8) { usage(argv[0]); return 2; }
   }
   const ProbeFormat *format = NULL;
   if (!caps) {
      format = find_format(argv[3]);
      if (!format) { usage(argv[0]); return 2; }
      if (blit && !format->block_bytes) {
         fprintf(stderr, "FAIL stage=blit-requires-bc-format\n");
         return 2;
      }
      printf("PROBE=%s\n", blit ? "blit" : "copy");
      printf("PROBE_FORMAT=%s\n", format->name);
      printf("PROBE_WIDTH=%u\n", (unsigned)strtoul(argv[6], NULL, 10));
      printf("PROBE_HEIGHT=%u\n", (unsigned)strtoul(argv[7], NULL, 10));
      fflush(stdout);
   }
   if (init_instance(argv[2])) return 1;
   if (caps) return caps_main();
   uint32_t width = (uint32_t)strtoul(argv[6], NULL, 10);
   uint32_t height = (uint32_t)strtoul(argv[7], NULL, 10);
   if (!width || !height || width > 4096 || height > 4096) {
      fprintf(stderr, "FAIL stage=extent\n"); return 2;
   }
   print_format_props(format->name, format->format);
   if (init_device()) return 1;
   int result;
   if (blit) {
      result = blit_main(format, argv[4], argv[5], width, height);
   } else {
      result = copy_main(format, argv[4], argv[5], width, height);
   }
   if (!result) {
      g_stage = "idle";
      TRY_VOID(vkDeviceWaitIdle(device));
      vkDestroyDevice(device, NULL);
      vkDestroyInstance(instance, NULL);
      printf("RESULT_clean=ok\n");
      fflush(stdout);
   }
   return result;
}
