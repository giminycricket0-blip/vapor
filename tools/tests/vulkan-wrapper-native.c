/* Native, offscreen Vulkan probe. No Steam, Wine, window or game is started. */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define INSTANCE_COMMANDS(X) \
   X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties) \
   X(GetPhysicalDeviceFeatures) X(GetPhysicalDeviceQueueFamilyProperties) \
   X(GetPhysicalDeviceMemoryProperties) X(CreateDevice) X(GetDeviceProcAddr)
#define DEVICE_COMMANDS(X) \
   X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(CreateBuffer) X(DestroyBuffer) \
   X(GetBufferMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindBufferMemory) \
   X(MapMemory) X(UnmapMemory) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) \
   X(BindImageMemory) X(CreateCommandPool) X(DestroyCommandPool) X(AllocateCommandBuffers) \
   X(FreeCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) \
   X(CmdCopyBufferToImage) X(CmdCopyImageToBuffer) X(QueueSubmit) X(CreateFence) \
   X(WaitForFences) X(DestroyFence)
#define DECLARE(name) static PFN_vk##name vk##name;
INSTANCE_COMMANDS(DECLARE)
DEVICE_COMMANDS(DECLARE)
static PFN_vkGetInstanceProcAddr get_proc;
static VkInstance instance;
static VkDevice device;
static VkPhysicalDeviceMemoryProperties memory_properties;

static void check(VkResult result, const char *stage) {
   if (result != VK_SUCCESS) {
      fprintf(stderr, "FAIL stage=%s result=%d\n", stage, result);
      exit(1);
   }
}
#define CHECK(call) check((call), #call)

static uint32_t memory_type(uint32_t bits, VkMemoryPropertyFlags flags) {
   for (uint32_t i = 0; i < memory_properties.memoryTypeCount; i++)
      if ((bits & (1u << i)) && (memory_properties.memoryTypes[i].propertyFlags & flags) == flags)
         return i;
   fprintf(stderr, "FAIL stage=memory-type bits=%x flags=%x\n", bits, flags);
   exit(1);
}

static VkDeviceMemory allocate(VkMemoryRequirements requirements, VkMemoryPropertyFlags flags) {
   VkMemoryAllocateInfo info = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = requirements.size, .memoryTypeIndex = memory_type(requirements.memoryTypeBits, flags)};
   VkDeviceMemory memory;
   CHECK(vkAllocateMemory(device, &info, NULL, &memory));
   return memory;
}

static VkBuffer buffer(VkDeviceSize size, VkDeviceMemory *memory) {
   VkBufferCreateInfo info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size,
      .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
   VkBuffer result;
   CHECK(vkCreateBuffer(device, &info, NULL, &result));
   VkMemoryRequirements requirements;
   vkGetBufferMemoryRequirements(device, result, &requirements);
   *memory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
   CHECK(vkBindBufferMemory(device, result, *memory, 0));
   return result;
}

static void barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                    VkImageLayout new_layout, VkAccessFlags src, VkAccessFlags dst,
                    VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage) {
   VkImageMemoryBarrier info = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
      .srcAccessMask = src, .dstAccessMask = dst, .oldLayout = old_layout, .newLayout = new_layout,
      .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
      .image = image, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
   vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &info);
}

int main(int argc, char **argv) {
   setvbuf(stdout, NULL, _IOLBF, 0);
   if (argc < 3 || argc > 5) {
      fprintf(stderr, "Usage: %s LIBRARY rgba|bc1|bc3|readonly [CYCLES] [EDGE]\n", argv[0]); return 2;
   }
   bool readonly = !strcmp(argv[2], "readonly");
   bool bc1 = !strcmp(argv[2], "bc1") || readonly;
   bool bc3 = !strcmp(argv[2], "bc3");
   if (!bc1 && !bc3 && strcmp(argv[2], "rgba")) return 2;
   unsigned cycles = argc >= 4 ? (unsigned)strtoul(argv[3], NULL, 10) : 1;
   unsigned edge = argc == 5 ? (unsigned)strtoul(argv[4], NULL, 10) : 4;
   if (!cycles || cycles > 256) return 2;
   if (!edge || edge > 256) return 2;
   size_t pixel_count = edge * edge, buffer_size = pixel_count * 4;
   printf("PROBE pid=%d mode=%s cycles=%u edge=%u\n", (int)getpid(), argv[2], cycles, edge);
   void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
   if (!library) { fprintf(stderr, "FAIL stage=dlopen error=%s\n", dlerror()); return 1; }
   puts("PASS dlopen");
   get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vk_icdGetInstanceProcAddr");
   if (!get_proc) get_proc = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
   if (!get_proc) { fprintf(stderr, "FAIL stage=GetInstanceProcAddr\n"); return 1; }
   PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)get_proc(VK_NULL_HANDLE, "vkCreateInstance");
   if (!create_instance) return 1;
   VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
      .pApplicationName = "Vapor offscreen wrapper probe", .apiVersion = VK_API_VERSION_1_2};
   VkInstanceCreateInfo ii = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
   CHECK(create_instance(&ii, NULL, &instance));
   puts("PASS instance");
#define LOAD_INSTANCE(name) vk##name = (PFN_vk##name)get_proc(instance, "vk" #name); if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   INSTANCE_COMMANDS(LOAD_INSTANCE)
   uint32_t count = 0;
   CHECK(vkEnumeratePhysicalDevices(instance, &count, NULL));
   if (!count) { fprintf(stderr, "FAIL stage=no-physical-device\n"); return 1; }
   VkPhysicalDevice *physical_devices = calloc(count, sizeof(*physical_devices));
   if (!physical_devices) return 1;
   CHECK(vkEnumeratePhysicalDevices(instance, &count, physical_devices));
   VkPhysicalDevice physical = physical_devices[0]; free(physical_devices);
   VkPhysicalDeviceProperties properties;
   vkGetPhysicalDeviceProperties(physical, &properties);
   printf("DEVICE name=%s vendor=%x device=%x api=%u.%u.%u page_size=%ld\n", properties.deviceName,
      properties.vendorID, properties.deviceID, VK_VERSION_MAJOR(properties.apiVersion),
      VK_VERSION_MINOR(properties.apiVersion), VK_VERSION_PATCH(properties.apiVersion), sysconf(_SC_PAGESIZE));
   vkGetPhysicalDeviceMemoryProperties(physical, &memory_properties);
   uint32_t family_count = 0;
   vkGetPhysicalDeviceQueueFamilyProperties(physical, &family_count, NULL);
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
   VkDeviceQueueCreateInfo qi = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
      .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
   VkPhysicalDeviceFeatures available, enabled = {0};
   vkGetPhysicalDeviceFeatures(physical, &available);
   enabled.shaderStorageImageExtendedFormats = available.shaderStorageImageExtendedFormats;
   enabled.shaderStorageImageReadWithoutFormat = available.shaderStorageImageReadWithoutFormat;
   enabled.shaderStorageImageWriteWithoutFormat = available.shaderStorageImageWriteWithoutFormat;
   VkDeviceCreateInfo di = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
      .queueCreateInfoCount = 1, .pQueueCreateInfos = &qi, .pEnabledFeatures = &enabled};
   CHECK(vkCreateDevice(physical, &di, NULL, &device));
   puts("PASS device");
#define LOAD_DEVICE(name) vk##name = (PFN_vk##name)vkGetDeviceProcAddr(device, "vk" #name); if (!vk##name) { fprintf(stderr, "FAIL missing=vk%s\n", #name); return 1; }
   DEVICE_COMMANDS(LOAD_DEVICE)
   VkQueue queue;
   vkGetDeviceQueue(device, family, 0, &queue);
   VkFormat format = bc1 ? VK_FORMAT_BC1_RGBA_UNORM_BLOCK : bc3 ? VK_FORMAT_BC3_UNORM_BLOCK : VK_FORMAT_R8G8B8A8_UNORM;
   size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
   void *page = mmap(NULL, page_size, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
   if (page == MAP_FAILED) return 1;
   VkImageFormatListCreateInfo *formats = page;
   VkFormat *view_formats = (void *)(formats + 1);
   view_formats[0] = format;
   *formats = (VkImageFormatListCreateInfo){.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO,
      .viewFormatCount = 1, .pViewFormats = view_formats};
   if (readonly && mprotect(page, page_size, PROT_READ)) return 1;
   VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .pNext = readonly ? formats : NULL, .flags = readonly ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0,
      .imageType = VK_IMAGE_TYPE_2D, .format = format, .extent = {edge, edge, 1}, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
   VkImage image;
   CHECK(vkCreateImage(device, &ci, NULL, &image));
   if (readonly && (view_formats[0] != format || formats->viewFormatCount != 1)) return 1;
   puts("PASS image-and-caller-data");
   VkMemoryRequirements requirements;
   vkGetImageMemoryRequirements(device, image, &requirements);
   VkDeviceMemory image_memory = allocate(requirements, 0);
   CHECK(vkBindImageMemory(device, image, image_memory, 0));
   VkDeviceMemory src_memory, dst_memory;
   size_t block_count = ((edge + 3) / 4) * ((edge + 3) / 4);
   size_t compressed_size = block_count * (bc3 ? 16 : 8);
   VkBuffer src = buffer(buffer_size > compressed_size ? buffer_size : compressed_size, &src_memory);
   VkBuffer dst = buffer(buffer_size, &dst_memory);
   void *mapped;
   VkCommandPoolCreateInfo pi = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = family};
   VkCommandPool pool;
   CHECK(vkCreateCommandPool(device, &pi, NULL, &pool));
   for (unsigned cycle = 0; cycle < cycles; cycle++) {
      CHECK(vkMapMemory(device, src_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
      memset(mapped, 0, bc1 || bc3 ? compressed_size : buffer_size);
      if (bc1 || bc3) {
         for (size_t block = 0; block < block_count; block++) {
            unsigned char *data = (unsigned char *)mapped + block * (bc3 ? 16 : 8);
            unsigned char *color = data + (bc3 ? 8 : 0);
            color[0] = cycle & 1 ? 0xe0 : 0;
            color[1] = cycle & 1 ? 0x07 : 0xf8;
            if (bc3) data[0] = 255;
         }
      } else for (size_t i = 0; i < pixel_count; i++) {
         unsigned char *pixel = (unsigned char *)mapped + i * 4;
         pixel[cycle & 1 ? 1 : 0] = 255; pixel[3] = 255;
      }
      vkUnmapMemory(device, src_memory);
      CHECK(vkMapMemory(device, dst_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
      memset(mapped, 0xcd, buffer_size);
      vkUnmapMemory(device, dst_memory);
      VkCommandBufferAllocateInfo ai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
         .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
      VkCommandBuffer cmd;
      CHECK(vkAllocateCommandBuffers(device, &ai, &cmd));
      VkCommandBufferBeginInfo bi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
      CHECK(vkBeginCommandBuffer(cmd, &bi));
      barrier(cmd, image, cycle ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, cycle ? VK_ACCESS_TRANSFER_READ_BIT : 0,
         VK_ACCESS_TRANSFER_WRITE_BIT, cycle ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
         VK_PIPELINE_STAGE_TRANSFER_BIT);
      VkBufferImageCopy region = {.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, .imageExtent = {edge, edge, 1}};
      vkCmdCopyBufferToImage(cmd, src, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
      barrier(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
      vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, 1, &region);
      VkBufferMemoryBarrier host = {.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER,
         .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
         .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
         .buffer = dst, .offset = 0, .size = VK_WHOLE_SIZE};
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, NULL, 1, &host, 0, NULL);
      CHECK(vkEndCommandBuffer(cmd));
      VkFenceCreateInfo fi = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
      VkFence fence;
      CHECK(vkCreateFence(device, &fi, NULL, &fence));
      VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
      CHECK(vkQueueSubmit(queue, 1, &si, fence));
      CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ULL));
      CHECK(vkMapMemory(device, dst_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
      for (size_t i = 0; i < pixel_count; i++) {
         const unsigned char *pixel = (unsigned char *)mapped + i * 4;
         if (pixel[0] != (cycle & 1 ? 0 : 255) || pixel[1] != (cycle & 1 ? 255 : 0) || pixel[2] || pixel[3] != 255) {
            fprintf(stderr, "FAIL stage=pixels cycle=%u pixel=%zu rgba=%u,%u,%u,%u\n",
               cycle, i, pixel[0], pixel[1], pixel[2], pixel[3]); return 1;
         }
      }
      vkUnmapMemory(device, dst_memory);
      vkDestroyFence(device, fence, NULL);
      vkFreeCommandBuffers(device, pool, 1, &cmd);
      printf("PASS pixels cycle=%u count=%zu\n", cycle, pixel_count);
   }
   CHECK(vkDeviceWaitIdle(device));
   vkDestroyCommandPool(device, pool, NULL);
   vkDestroyBuffer(device, src, NULL); vkDestroyBuffer(device, dst, NULL);
   vkFreeMemory(device, src_memory, NULL); vkFreeMemory(device, dst_memory, NULL);
   vkDestroyImage(device, image, NULL); vkFreeMemory(device, image_memory, NULL);
   munmap(page, page_size);
   vkDestroyDevice(device, NULL); vkDestroyInstance(instance, NULL);
   puts("PASS clean-exit");
   dlclose(library);
   return 0;
}
