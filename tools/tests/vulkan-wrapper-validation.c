/* Native, offscreen probe that chains the Khronos validation layer manually
 * in front of a Vulkan driver/wrapper (the way the Vulkan loader does).
 *
 * No loader is involved: we dlopen the layer and the driver, obtain each
 * library's vkGetInstanceProcAddr (driver side may only expose
 * vk_icdGetInstanceProcAddr, as the wrapper does), and hand-build the
 * VkLayerInstanceCreateInfo / VkLayerDeviceCreateInfo VK_LAYER_LINK_INFO
 * chains plus the VK_LOADER_DATA_CALLBACK infos.  The workload is the same
 * BCn upload/readback workload as the other native probes, so the layer sees
 * the application-facing wrapper API calls.
 *
 * The probe does not install its own debug callback: the validation layer's
 * default callback prints "Validation Error: [ VUID-... ]" lines to stdout
 * and to logcat tag VALIDATION, which the runner captures.  It only counts
 * what it sees; classification happens in the runner/evidence.
 *
 * Usage:
 *   vulkan-wrapper-validation <driver.so> <layer.so|none> <mode> <cycles> <edge>
 *   mode: bc1 | bc3 | rgba | bc1-multi | caps
 *
 * Vendored from tools/tests/vulkan-wrapper-native.c (same workload/pattern)
 * so the validation run is comparable with the established probes.
 */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#include <dlfcn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define INSTANCE_COMMANDS(X)                                                                                  \
   X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) X(GetPhysicalDeviceFeatures) \
           X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceMemoryProperties) X(CreateDevice)    \
               X(GetDeviceProcAddr)

#define DEVICE_COMMANDS(X)                                                                                    \
   X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(CreateBuffer) X(DestroyBuffer)                      \
       X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) X(MapMemory)        \
           X(UnmapMemory) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(BindImageMemory)      \
               X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) X(FreeCommandBuffers)     \
                   X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdCopyBufferToImage)    \
                       X(CmdCopyImageToBuffer) X(QueueSubmit) X(CreateFence) X(WaitForFences) X(DestroyFence)

#define DECLARE(name) static PFN_vk##name vk##name;
INSTANCE_COMMANDS(DECLARE)
DEVICE_COMMANDS(DECLARE)
#undef DECLARE

static PFN_vkGetInstanceProcAddr g_driver_gipa;
static PFN_vkGetInstanceProcAddr g_layer_gipa;
static PFN_vkGetDeviceProcAddr g_driver_gdpa;
static VkInstance g_driver_instance = VK_NULL_HANDLE;

static VkPhysicalDeviceMemoryProperties g_memory_properties;
static VkInstance g_instance = VK_NULL_HANDLE;
static VkDevice g_device = VK_NULL_HANDLE;
static VkPhysicalDevice g_physical = VK_NULL_HANDLE;
static VkQueue g_queue = VK_NULL_HANDLE;

static const char *g_mode = "bc1";
static unsigned g_cycles = 8;
static unsigned g_edge = 16;

/* Default VVL callback installs itself when no user callback is provided; we
 * don't call into VK_EXT_debug_utils (the wrapper deliberately blocks it), so
 * messages are printed to stdout/logcat by the layer itself. */
static int g_failures;

static void fail(const char *stage, const char *fmt, ...) {
   va_list args;
   va_start(args, fmt);
   fprintf(stderr, "FAIL stage=%s ", stage);
   vfprintf(stderr, fmt, args);
   fprintf(stderr, "\n");
   va_end(args);
   fflush(stderr);
   g_failures++;
}

static bool check(VkResult result, const char *stage) {
   if (result != VK_SUCCESS) {
      fail(stage, "result=%d%s", result,
           result == VK_ERROR_VALIDATION_FAILED_EXT ? " (VK_ERROR_VALIDATION_FAILED_EXT)" : "");
      return false;
   }
   return true;
}

/* ------------------------------------------------------------------------
 * Manual loader-link shims
 * ------------------------------------------------------------------------ */

/* The validation layer's chassis calls this for device entrypoints.  When the
 * library below is an ICD-style wrapper there is no vkGetDeviceProcAddr export,
 * but its vkGetInstanceProcAddr/ vk_icdGetInstanceProcAddr can resolve it. */
static PFN_vkVoidFunction VKAPI_CALL next_device_proc(VkDevice device, const char *name) {
   if (!g_driver_gdpa && g_driver_gipa && g_driver_instance != VK_NULL_HANDLE) {
      g_driver_gdpa = (PFN_vkGetDeviceProcAddr)g_driver_gipa(g_driver_instance, "vkGetDeviceProcAddr");
   }
   if (g_driver_gdpa) return g_driver_gdpa(device, name);
   /* Fallback used by ICDs: device functions are also resolvable through the
    * instance proc addr, dispatching on the (dispatchable) device handle. */
   return g_driver_gipa(g_driver_instance, name);
}

/* VK_LOADER_DATA_CALLBACK entry points.  The loader uses these to store its
 * dispatch pointer in dispatchable objects; VVL 1.4 does not call them (it
 * keeps its own handle maps), but the info is supplied for fidelity with the
 * loader chain. */
static VkResult VKAPI_CALL set_instance_loader_data(VkInstance instance, void *object) {
   (void)instance;
   (void)object;
   return VK_SUCCESS;
}

static VkResult VKAPI_CALL set_device_loader_data(VkDevice device, void *object) {
   (void)device;
   (void)object;
   return VK_SUCCESS;
}

/* ------------------------------------------------------------------------
 * Workload
 * ------------------------------------------------------------------------ */

static void *map_memory(VkDeviceMemory memory, VkDeviceSize size, const char *stage) {
   void *ptr = NULL;
   if (!check(vkMapMemory(g_device, memory, 0, size, 0, &ptr), stage)) return NULL;
   return ptr;
}

static void unmap(VkDeviceMemory memory) { vkUnmapMemory(g_device, memory); }

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags) {
   for (uint32_t i = 0; i < g_memory_properties.memoryTypeCount; i++) {
      if ((bits & (1u << i)) &&
          (g_memory_properties.memoryTypes[i].propertyFlags & flags) == flags)
         return i;
   }
   fail("memory-type", "no type for bits=0x%x flags=0x%x", bits, flags);
   return 0;
}

static VkDeviceMemory allocate(VkMemoryRequirements requirements, VkMemoryPropertyFlags flags, const char *stage) {
   VkMemoryAllocateInfo info = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                .allocationSize = requirements.size,
                                .memoryTypeIndex = memory_type(requirements.memoryTypeBits, flags)};
   VkDeviceMemory memory = VK_NULL_HANDLE;
   check(vkAllocateMemory(g_device, &info, NULL, &memory), stage);
   return memory;
}

static VkBuffer create_buffer(VkDeviceSize size, VkBufferUsageFlags usage, VkDeviceMemory *memory, const char *stage) {
   VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                              .size = size,
                              .usage = usage,
                              .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
   VkBuffer buffer = VK_NULL_HANDLE;
   if (!check(vkCreateBuffer(g_device, &info, NULL, &buffer), stage)) return VK_NULL_HANDLE;
   VkMemoryRequirements requirements;
   vkGetBufferMemoryRequirements(g_device, buffer, &requirements);
   *memory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stage);
   if (*memory == VK_NULL_HANDLE) return VK_NULL_HANDLE;
   check(vkBindBufferMemory(g_device, buffer, *memory, 0), stage);
   return buffer;
}

/* BC1/BC3 block payload patterns.  The established native probes use c0 = red
 * on even cycles and c0 = green on odd cycles, c1 = black, index 0 everywhere;
 * that decodes to a flat red/green image through both the wrapper's compute
 * decoder and the system driver. */
static void fill_bc_block(unsigned char *block, VkFormat format, bool red) {
   uint16_t c0 = red ? 0xF800 : 0x07E0; /* red : green */
   uint16_t c1 = 0x0000;
   if (format == VK_FORMAT_BC3_UNORM_BLOCK) {
      memset(block, 0, 16);
      block[0] = 255; /* a0 */
      block[1] = 0;   /* a1 */
      block[8] = (unsigned char)(c0 & 0xFF);
      block[9] = (unsigned char)(c0 >> 8);
      block[10] = (unsigned char)(c1 & 0xFF);
      block[11] = (unsigned char)(c1 >> 8);
   } else {
      memset(block, 0, 8);
      block[0] = (unsigned char)(c0 & 0xFF);
      block[1] = (unsigned char)(c0 >> 8);
      block[2] = (unsigned char)(c1 & 0xFF);
      block[3] = (unsigned char)(c1 >> 8);
   }
}

static bool run_workload(void) {
   bool is_bc1 = strcmp(g_mode, "bc1") == 0 || strcmp(g_mode, "bc1-multi") == 0 ||
                 strcmp(g_mode, "bc1-native") == 0;
   bool is_bc3 = strcmp(g_mode, "bc3") == 0;
   bool is_multi = strcmp(g_mode, "bc1-multi") == 0;
   bool is_rgba = strcmp(g_mode, "rgba") == 0;
   bool is_native = strcmp(g_mode, "bc1-native") == 0;
   VkFormat format = is_bc1 ? VK_FORMAT_BC1_RGBA_UNORM_BLOCK
                            : (is_bc3 ? VK_FORMAT_BC3_UNORM_BLOCK : VK_FORMAT_R8G8B8A8_UNORM);
   unsigned edge = is_bc1 ? 16u : (is_bc3 ? 16u : g_edge);
   size_t pixels = (size_t)edge * edge;
   size_t block_bytes = is_bc3 ? 16 : 8;
   size_t blocks = (size_t)((edge + 3) / 4) * ((edge + 3) / 4);
   size_t packed_size = blocks * block_bytes;
   unsigned region_count = is_multi ? 2u : 1u;
   if (is_multi) {
      blocks = (size_t)((edge / 2 + 3) / 4) * ((edge + 3) / 4);
      packed_size = blocks * block_bytes * 2;
   }
   size_t source_size = is_rgba ? pixels * 4 : packed_size;
   size_t readback_size = pixels * 4;

   printf("WORKLOAD mode=%s format=%d edge=%u regions=%u cycles=%u\n", g_mode, (int)format, edge,
          region_count, g_cycles);
   fflush(stdout);

   VkImage image = VK_NULL_HANDLE;
   VkDeviceMemory image_memory = VK_NULL_HANDLE;
   VkBuffer source = VK_NULL_HANDLE;
   VkDeviceMemory source_memory = VK_NULL_HANDLE;
   VkBuffer readback = VK_NULL_HANDLE;
   VkDeviceMemory readback_memory = VK_NULL_HANDLE;
   VkCommandPool pool = VK_NULL_HANDLE;
   bool ok = true;

   {
      VkImageCreateInfo info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                                .imageType = VK_IMAGE_TYPE_2D,
                                .format = format,
                                .extent = {edge, edge, 1},
                                .mipLevels = 1,
                                .arrayLayers = 1,
                                .samples = VK_SAMPLE_COUNT_1_BIT,
                                .tiling = VK_IMAGE_TILING_OPTIMAL,
                                .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                                .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                                .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
      ok &= check(vkCreateImage(g_device, &info, NULL, &image), "create-image");
      if (image != VK_NULL_HANDLE) {
         VkMemoryRequirements requirements;
         vkGetImageMemoryRequirements(g_device, image, &requirements);
         image_memory = allocate(requirements, 0, "image-memory");
         if (image_memory != VK_NULL_HANDLE)
            ok &= check(vkBindImageMemory(g_device, image, image_memory, 0), "bind-image");
      }
   }

   source = create_buffer(source_size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                          &source_memory, "create-source");
   readback = create_buffer(readback_size,
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            &readback_memory, "create-readback");
   if (!source || !readback) return false;

   VkCommandPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                        .queueFamilyIndex = 0};
   ok &= check(vkCreateCommandPool(g_device, &pool_info, NULL, &pool), "create-pool");
   if (!ok) {
      printf("RESULT fail stage=resources\n");
      return false;
   }

   for (unsigned cycle = 0; cycle < g_cycles; cycle++) {
      bool red = (cycle & 1u) == 0u;
      /* Fill the compressed (or RGBA) source buffer for this cycle. */
      void *source_ptr = map_memory(source_memory, source_size, "map-source");
      if (!source_ptr) return false;
      if (is_rgba) {
         unsigned char *pixels8 = (unsigned char *)source_ptr;
         for (size_t i = 0; i < pixels; i++) {
            unsigned char *p = pixels8 + i * 4;
            p[0] = red ? 255 : 0;
            p[1] = red ? 0 : 255;
            p[2] = 0;
            p[3] = 255;
         }
      } else {
         unsigned char *blocks8 = (unsigned char *)source_ptr;
         for (size_t b = 0; b < (is_multi ? blocks * 2 : (blocks)); b++) {
            bool block_red = red;
            if (is_multi) block_red = (b / blocks) == 0; /* first region red, second green */
            fill_bc_block(blocks8 + b * block_bytes, format, block_red);
         }
      }
      unmap(source_memory);

      /* Poison the readback buffer so a missed copy is visible. */
      void *readback_ptr = map_memory(readback_memory, readback_size, "map-readback");
      if (!readback_ptr) return false;
      memset(readback_ptr, 0xCD, readback_size);
      unmap(readback_memory);

      VkCommandBufferAllocateInfo allocate_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                                   .commandPool = pool,
                                                   .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                                   .commandBufferCount = 1};
      VkCommandBuffer cmd = VK_NULL_HANDLE;
      ok &= check(vkAllocateCommandBuffers(g_device, &allocate_info, &cmd), "allocate-cmd");
      VkCommandBufferBeginInfo begin_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
      ok &= check(vkBeginCommandBuffer(cmd, &begin_info), "begin-cmd");

      VkImageMemoryBarrier to_dst = {
         .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
         .srcAccessMask = cycle ? VK_ACCESS_TRANSFER_READ_BIT : 0,
         .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
         .oldLayout = cycle ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
         .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .image = image,
         .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
      vkCmdPipelineBarrier(cmd, cycle ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &to_dst);

      if (is_multi) {
         VkBufferImageCopy regions[2];
         memset(regions, 0, sizeof(regions));
         for (unsigned r = 0; r < 2; r++) {
            regions[r].imageSubresource = (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            regions[r].imageOffset = (VkOffset3D){(int32_t)(r * (edge / 2)), 0, 0};
            regions[r].imageExtent = (VkExtent3D){edge / 2, edge, 1};
            regions[r].bufferOffset = (VkDeviceSize)(r * blocks * block_bytes);
         }
         vkCmdCopyBufferToImage(cmd, source, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 2, regions);
      } else {
         VkBufferImageCopy region = {.bufferOffset = 0,
                                     .bufferRowLength = 0,
                                     .bufferImageHeight = 0,
                                     .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                                     .imageOffset = {0, 0, 0},
                                     .imageExtent = {edge, edge, 1}};
         vkCmdCopyBufferToImage(cmd, source, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
      }

      VkImageMemoryBarrier to_src = {
         .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
         .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
         .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .image = image,
         .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL,
                           0, NULL, 1, &to_src);

      VkBufferImageCopy read_region = {.bufferOffset = 0,
                                       .bufferRowLength = 0,
                                       .bufferImageHeight = 0,
                                       .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
                                       .imageOffset = {0, 0, 0},
                                       .imageExtent = {edge, edge, 1}};
      vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &read_region);

      VkBufferMemoryBarrier host_barrier = {
         .sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
         .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
         .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
         .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .buffer = readback,
         .offset = 0,
         .size = readback_size};
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1,
                           &host_barrier, 0, NULL);
      ok &= check(vkEndCommandBuffer(cmd), "end-cmd");

      VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
      VkFence fence = VK_NULL_HANDLE;
      ok &= check(vkCreateFence(g_device, &fence_info, NULL, &fence), "create-fence");
      VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                             .commandBufferCount = 1,
                             .pCommandBuffers = &cmd};
      ok &= check(vkQueueSubmit(g_queue, 1, &submit, fence), "queue-submit");
      ok &= check(vkWaitForFences(g_device, 1, &fence, VK_TRUE, 30000000000ULL), "wait-fence");

      void *result_ptr = map_memory(readback_memory, readback_size, "map-result");
      if (!result_ptr) return false;
      bool pixels_ok = true;
      const unsigned char *result = (const unsigned char *)result_ptr;
      if (is_native) {
         /* The native driver keeps the image compressed: the readback must be
          * the exact BC1 block bytes that were uploaded (tightly packed). */
         unsigned char expected[256];
         size_t block_count = (size_t)((edge + 3) / 4) * ((edge + 3) / 4);
         for (size_t b = 0; b < block_count; b++)
            fill_bc_block(expected + b * 8, format, red);
         if (memcmp(result, expected, block_count * 8) != 0) {
            fail("native-blocks", "cycle=%u compressed readback differs from uploaded BC1 blocks", cycle);
            pixels_ok = false;
         }
      }
      for (unsigned y = 0; y < edge && pixels_ok && !is_native; y++) {
         for (unsigned x = 0; x < edge && pixels_ok; x++) {
            unsigned px = x, py = y;
            bool expect_red = red;
            if (is_multi) expect_red = x < edge / 2;
            const unsigned char *p = result + ((size_t)py * edge + px) * 4;
            if (p[0] != (expect_red ? 255 : 0) || p[1] != (expect_red ? 0 : 255) || p[2] != 0 ||
                p[3] != 255) {
               fail("pixels", "cycle=%u x=%u y=%u rgba=%u,%u,%u,%u expected_red=%d", cycle, px, py, p[0],
                    p[1], p[2], p[3], (int)expect_red);
               pixels_ok = false;
            }
         }
      }
      unmap(readback_memory);
      ok &= pixels_ok;
      printf("PASS cycle=%u pixels=%zu regions=%u%s\n", cycle, pixels, region_count,
             pixels_ok ? "" : " VERIFY-FAIL");
      fflush(stdout);

      vkDestroyFence(g_device, fence, NULL);
      vkFreeCommandBuffers(g_device, pool, 1, &cmd);
      if (!ok) return false;
   }

   check(vkDeviceWaitIdle(g_device), "device-wait-idle");
   vkDestroyCommandPool(g_device, pool, NULL);
   vkDestroyBuffer(g_device, source, NULL);
   vkFreeMemory(g_device, source_memory, NULL);
   vkDestroyBuffer(g_device, readback, NULL);
   vkFreeMemory(g_device, readback_memory, NULL);
   vkDestroyImage(g_device, image, NULL);
   vkFreeMemory(g_device, image_memory, NULL);
   printf("RESULT %s mode=%s cycles=%u\n", ok ? "ok" : "fail", g_mode, g_cycles);
   fflush(stdout);
   return ok;
}

/* ------------------------------------------------------------------------
 * Instance / device creation
 * ------------------------------------------------------------------------ */

static void print_layer_properties(PFN_vkGetInstanceProcAddr gipa) {
   uint32_t count = 0;
   PFN_vkEnumerateInstanceLayerProperties enumerate =
      (PFN_vkEnumerateInstanceLayerProperties)gipa(NULL, "vkEnumerateInstanceLayerProperties");
   if (!enumerate) return;
   VkResult result = enumerate(&count, NULL);
   printf("LAYER_PROPERTIES count=%u result=%d\n", count, result);
   if (result == VK_SUCCESS && count > 0) {
      VkLayerProperties layers[8];
      if (count > 8) count = 8;
      result = enumerate(&count, layers);
      if (result == VK_SUCCESS) {
         for (uint32_t i = 0; i < count; i++) {
            printf("LAYER name=%s spec=%u.%u.%u impl=%u description=%s\n", layers[i].layerName,
                   VK_API_VERSION_MAJOR(layers[i].specVersion), VK_API_VERSION_MINOR(layers[i].specVersion),
                   VK_API_VERSION_PATCH(layers[i].specVersion), layers[i].implementationVersion,
                   layers[i].description);
         }
      }
   }
   fflush(stdout);
}

static void print_instance_extension_properties(PFN_vkGetInstanceProcAddr gipa) {
   PFN_vkEnumerateInstanceExtensionProperties enumerate =
      (PFN_vkEnumerateInstanceExtensionProperties)gipa(NULL, "vkEnumerateInstanceExtensionProperties");
   if (!enumerate) return;
   uint32_t count = 0;
   if (enumerate(NULL, &count, NULL) != VK_SUCCESS) return;
   VkExtensionProperties properties[256];
   if (count > 256) count = 256;
   if (enumerate(NULL, &count, properties) != VK_SUCCESS) return;
   printf("INSTANCE_EXTENSIONS count=%u\n", count);
   for (uint32_t i = 0; i < count; i++) {
      if (strstr(properties[i].extensionName, "debug_utils") ||
          strstr(properties[i].extensionName, "debug_report"))
         printf("INSTANCE_EXTENSION name=%s spec=%u\n", properties[i].extensionName,
                properties[i].specVersion);
   }
   fflush(stdout);
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   if (argc < 6) {
      fprintf(stderr,
              "Usage: %s <driver.so> <layer.so|none> <mode: bc1|bc3|rgba|bc1-multi|caps> <cycles> <edge>\n",
              argv[0]);
      return 2;
   }
   const char *driver_path = argv[1];
   const char *layer_path = argv[2];
   g_mode = argv[3];
   g_cycles = (unsigned)strtoul(argv[4], NULL, 10);
   g_edge = (unsigned)strtoul(argv[5], NULL, 10);
   bool chained = strcmp(layer_path, "none") != 0;
   printf("PROBE driver=%s layer=%s chained=%d mode=%s cycles=%u edge=%u page_size=%ld\n", driver_path,
          layer_path, chained, g_mode, g_cycles, g_edge, sysconf(_SC_PAGESIZE));
   fflush(stdout);

   void *driver_handle = dlopen(driver_path, RTLD_NOW | RTLD_LOCAL);
   if (!driver_handle) {
      fail("dlopen-driver", "%s", dlerror());
      printf("RESULT fail stage=dlopen-driver\n");
      return 1;
   }
   g_driver_gipa = (PFN_vkGetInstanceProcAddr)dlsym(driver_handle, "vk_icdGetInstanceProcAddr");
   if (!g_driver_gipa) g_driver_gipa = (PFN_vkGetInstanceProcAddr)dlsym(driver_handle, "vkGetInstanceProcAddr");
   if (!g_driver_gipa) {
      fail("dlsym-driver-gipa", "%s", dlerror());
      printf("RESULT fail stage=driver-interface\n");
      return 1;
   }
   printf("DRIVER_INTERFACE driver=%s icd_gipa=%d exports_vkGetDeviceProcAddr=%d\n", driver_path,
          dlsym(driver_handle, "vk_icdGetInstanceProcAddr") != NULL,
          dlsym(driver_handle, "vkGetDeviceProcAddr") != NULL);
   fflush(stdout);

   void *layer_handle = NULL;
   PFN_vkGetInstanceProcAddr layer_gipa = NULL;
   if (chained) {
      layer_handle = dlopen(layer_path, RTLD_NOW | RTLD_LOCAL);
      if (!layer_handle) {
         fail("dlopen-layer", "%s", dlerror());
         printf("RESULT fail stage=dlopen-layer\n");
         return 1;
      }
      layer_gipa = (PFN_vkGetInstanceProcAddr)dlsym(layer_handle, "vkGetInstanceProcAddr");
      printf("LAYER_INTERFACE layer=%s gipa=%d negotiate=%d\n", layer_path, layer_gipa != NULL,
             dlsym(layer_handle, "vkNegotiateLoaderLayerInterfaceVersion") != NULL);
      if (!layer_gipa) {
         fail("dlsym-layer-gipa", "%s", dlerror());
         printf("RESULT fail stage=layer-interface\n");
         return 1;
      }
      g_layer_gipa = layer_gipa;
      print_layer_properties(layer_gipa);
   }

   PFN_vkCreateInstance create_instance = chained ? (PFN_vkCreateInstance)layer_gipa(NULL, "vkCreateInstance")
                                                  : (PFN_vkCreateInstance)g_driver_gipa(NULL, "vkCreateInstance");
   if (!create_instance) {
      fail("resolve-create-instance", "chain=%d", chained);
      printf("RESULT fail stage=resolve-create-instance\n");
      return 1;
   }

   /* Manual loader chain: VkLayerInstanceCreateInfo(VK_LAYER_LINK_INFO) with
    * the driver's GIPA, then VK_LOADER_DATA_CALLBACK (supplied for fidelity;
    * VVL 1.4 keeps its own dispatch maps and does not call it). */
   VkLayerInstanceLink instance_link = {
      .pNext = NULL,
      .pfnNextGetInstanceProcAddr = g_driver_gipa,
      /* VVL's chassis does not use pfnNextGetPhysicalDeviceProcAddr; physical
       * device entrypoints resolve through pfnNextGetInstanceProcAddr. */
      .pfnNextGetPhysicalDeviceProcAddr = NULL,
   };
   VkLayerInstanceCreateInfo instance_data_callback = {
      .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
      .pNext = NULL,
      .function = VK_LOADER_DATA_CALLBACK,
      .u.pfnSetInstanceLoaderData = set_instance_loader_data,
   };
   VkLayerInstanceCreateInfo instance_link_info = {
      .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
      .pNext = chained ? (const void *)&instance_data_callback : NULL,
      .function = VK_LAYER_LINK_INFO,
      .u.pLayerInfo = &instance_link,
   };

   VkApplicationInfo application_info = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                         .pApplicationName = "Vapor validation-chain probe",
                                         .applicationVersion = 1,
                                         .pEngineName = "none",
                                         .engineVersion = 1,
                                         .apiVersion = VK_API_VERSION_1_2};
   VkInstanceCreateInfo instance_info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                         .pNext = chained ? (const void *)&instance_link_info : NULL,
                                         .pApplicationInfo = &application_info};
   VkResult result = create_instance(&instance_info, NULL, &g_instance);
   if (!check(result, "create-instance")) {
      printf("RESULT fail stage=create-instance result=%d chained=%d\n", result, chained);
      return 3;
   }
   g_driver_instance = g_instance;
   printf("INSTANCE ok chained=%d\n", chained);
   fflush(stdout);

   PFN_vkGetInstanceProcAddr active_gipa = chained ? layer_gipa : g_driver_gipa;
   if (chained) print_instance_extension_properties(active_gipa);

#define LOAD_INSTANCE(name)                                                                                   \
   vk##name = (PFN_vk##name)active_gipa(g_instance, "vk" #name);                                              \
   if (!vk##name) {                                                                                            \
      fail("load-instance", #name);                                                                            \
      printf("RESULT fail stage=load-instance name=%s\n", #name);                                             \
      return 4;                                                                                                \
   }
   INSTANCE_COMMANDS(LOAD_INSTANCE)
#undef LOAD_INSTANCE

   uint32_t physical_count = 0;
   if (!check(vkEnumeratePhysicalDevices(g_instance, &physical_count, NULL), "enumerate-pd")) {
      printf("RESULT fail stage=enumerate-pd\n");
      return 5;
   }
   if (physical_count == 0) {
      fail("enumerate-pd", "count=0");
      printf("RESULT fail stage=no-physical-device\n");
      return 5;
   }
   VkPhysicalDevice physicals[16];
   if (physical_count > 16) physical_count = 16;
   check(vkEnumeratePhysicalDevices(g_instance, &physical_count, physicals), "enumerate-pd-array");
   g_physical = physicals[0];

   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(g_physical, &properties);
   printf("PHYSICAL_DEVICE name=%s vendor=0x%x device=0x%x type=%d api=%u.%u.%u driver_version=0x%x\n",
          properties.deviceName, properties.vendorID, properties.deviceID, (int)properties.deviceType,
          VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion),
          VK_API_VERSION_PATCH(properties.apiVersion), properties.driverVersion);

   VkPhysicalDeviceFeatures available_features;
   vkGetPhysicalDeviceFeatures(g_physical, &available_features);
   VkPhysicalDeviceFeatures enabled_features = {0};
   enabled_features.shaderStorageImageExtendedFormats = available_features.shaderStorageImageExtendedFormats;
   enabled_features.shaderStorageImageReadWithoutFormat = available_features.shaderStorageImageReadWithoutFormat;
   enabled_features.shaderStorageImageWriteWithoutFormat = available_features.shaderStorageImageWriteWithoutFormat;
   printf("FEATURES storage_image_extended=%d read_without_format=%d write_without_format=%d\n",
          enabled_features.shaderStorageImageExtendedFormats,
          enabled_features.shaderStorageImageReadWithoutFormat,
          enabled_features.shaderStorageImageWriteWithoutFormat);
   vkGetPhysicalDeviceMemoryProperties(g_physical, &g_memory_properties);

   uint32_t family_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(g_physical, &family_count, NULL);
   if (family_count == 0 || family_count > 32) {
      fail("queue-families", "count=%u", family_count);
      printf("RESULT fail stage=queue-families\n");
      return 6;
   }
   VkQueueFamilyProperties families[32];
   vkGetPhysicalDeviceQueueFamilyProperties(g_physical, &family_count, families);
   uint32_t family = family_count;
   for (uint32_t i = 0; i < family_count; i++) {
      if ((families[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
          (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
         family = i;
         break;
      }
   }
   if (family == family_count) {
      fail("graphics-compute-queue", "none");
      printf("RESULT fail stage=graphics-compute-queue\n");
      return 6;
   }

   float priority = 1.0f;
   VkDeviceQueueCreateInfo queue_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                         .queueFamilyIndex = family,
                                         .queueCount = 1,
                                         .pQueuePriorities = &priority};

   /* Manual device chain: same link-info scheme with the driver's GIPA and the
    * device-proc shim that resolves through the driver's GIPA/GDPA. */
   VkLayerDeviceLink device_link = {
      .pNext = NULL,
      .pfnNextGetInstanceProcAddr = g_driver_gipa,
      .pfnNextGetDeviceProcAddr = next_device_proc,
   };
   VkLayerDeviceCreateInfo device_data_callback = {
      .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
      .pNext = NULL,
      .function = VK_LOADER_DATA_CALLBACK,
      .u.pfnSetDeviceLoaderData = set_device_loader_data,
   };
   VkLayerDeviceCreateInfo device_link_info = {
      .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
      .pNext = chained ? (const void *)&device_data_callback : NULL,
      .function = VK_LAYER_LINK_INFO,
      .u.pLayerInfo = &device_link,
   };
   VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                     .pNext = chained ? (const void *)&device_link_info : NULL,
                                     .queueCreateInfoCount = 1,
                                     .pQueueCreateInfos = &queue_info,
                                     .pEnabledFeatures = &enabled_features};
   result = vkCreateDevice(g_physical, &device_info, NULL, &g_device);
   if (!check(result, "create-device")) {
      printf("RESULT fail stage=create-device result=%d chained=%d\n", result, chained);
      return 7;
   }
   printf("DEVICE ok chained=%d\n", chained);
   fflush(stdout);

   PFN_vkGetDeviceProcAddr get_device_proc = (PFN_vkGetDeviceProcAddr)active_gipa(g_instance, "vkGetDeviceProcAddr");
   if (!get_device_proc) {
      fail("load-device-proc", "vkGetDeviceProcAddr");
      printf("RESULT fail stage=load-device-proc\n");
      return 8;
   }
#define LOAD_DEVICE(name)                                                                                     \
   vk##name = (PFN_vk##name)get_device_proc(g_device, "vk" #name);                                             \
   if (!vk##name) {                                                                                            \
      fail("load-device", #name);                                                                              \
      printf("RESULT fail stage=load-device name=%s\n", #name);                                                \
      return 8;                                                                                                \
   }
   DEVICE_COMMANDS(LOAD_DEVICE)
#undef LOAD_DEVICE

   vkGetDeviceQueue(g_device, family, 0, &g_queue);
   printf("QUEUE ok family=%u\n", family);
   fflush(stdout);

   bool ok = true;
   if (strcmp(g_mode, "vuid") == 0) {
      /* Positive control for the layer chain: intentionally leave a buffer +
       * VkDeviceMemory alive on the device and call vkDestroyDevice.  A live
       * validation layer must report VUID-vkDestroyDevice-device-05137. */
      VkDeviceMemory leaked_memory = VK_NULL_HANDLE;
      VkBuffer leaked_buffer = create_buffer(64, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, &leaked_memory,
                                             "positive-control-buffer");
      (void)leaked_buffer;
      (void)leaked_memory;
      printf("POSITIVE_CONTROL expected_vuid=VUID-vkDestroyDevice-device-05137 leaked_buffer=1\n");
      vkDestroyDevice(g_device, NULL);
      g_device = VK_NULL_HANDLE;
      vkDestroyInstance(g_instance, NULL);
      g_instance = VK_NULL_HANDLE;
      printf("RESULT ok mode=vuid (deliberate fixture misuse for layer liveness)\n");
      fflush(stdout);
      if (layer_handle) dlclose(layer_handle);
      dlclose(driver_handle);
      return 0;
   }
   if (strcmp(g_mode, "caps") != 0) {
      ok = run_workload();
   } else {
      printf("CAPS mode: instance/device creation only\n");
   }

   vkDeviceWaitIdle(g_device);
   vkDestroyDevice(g_device, NULL);
   g_device = VK_NULL_HANDLE;
   vkDestroyInstance(g_instance, NULL);
   printf("SUMMARY result=%s chained=%d mode=%s\n", ok ? "ok" : "fail", chained, g_mode);
   printf("RESULT %s\n", ok ? "ok" : "fail");
   fflush(stdout);
   if (layer_handle) dlclose(layer_handle);
   dlclose(driver_handle);
   return ok ? 0 : 1;
}
