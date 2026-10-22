/*
 * Concurrency probe for the Vulkan wrapper's BCn emulation (docs/QUALIFICATION.md).
 *
 * Reuses the reference fixture's loader, device and helper code, then runs several threads
 * against one device. Each thread owns its command pool, buffers and BCn image and, per
 * iteration: maps its source memory, uploads a BCn texture (the wrapper decodes it), reads
 * the emulated image back, and compares the bytes with a golden single-threaded run.
 * Alternate iterations keep the source memory mapped across the submit, so the host-CPU
 * decode path sees both "application already mapped it" and "wrapper must map it". Queue
 * submission is serialised, as Vulkan requires; everything else runs concurrently.
 *
 * Usage: concurrency LIBRARY FORMAT INPUT WIDTH HEIGHT THREADS ITERATIONS
 * CONC_CORRUPT_THREAD=n (negative control) flips one input byte for thread n only, which
 * must then report every iteration as a mismatch.
 * Output: GOLDEN_BYTES=n, THREAD id=n iterations=n mismatches=n errors=n,
 *         TOTAL iterations=n mismatches=n errors=n, RESULT=ok|fail.
 */
#define main vapor_reference_main
#include "vulkan-wrapper-reference.c"
#undef main
#include <pthread.h>

static pthread_mutex_t queue_lock = PTHREAD_MUTEX_INITIALIZER;
static VkQueue shared_queue;

typedef struct {
   int id;
   const ProbeFormat *format;
   const void *input;
   size_t in_size, out_size;
   uint32_t width, height;
   int iterations;
   const unsigned char *golden; /* NULL while producing the golden output */
   unsigned char *output;       /* last readback */
   int done, mismatches, errors;
} Worker;

#define CHECK(call) do { VkResult r_ = (call); if (r_ != VK_SUCCESS) { \
   fprintf(stderr, "THREAD %d %s -> %d\n", w->id, #call, (int)r_); w->errors++; goto out; } } while (0)

static void *run_worker(void *arg) {
   Worker *w = arg;
   VkDeviceMemory src_memory = VK_NULL_HANDLE, dst_memory = VK_NULL_HANDLE, image_memory = VK_NULL_HANDLE;
   VkBuffer src = VK_NULL_HANDLE, dst = VK_NULL_HANDLE;
   VkImage image = VK_NULL_HANDLE;
   VkCommandPool pool = VK_NULL_HANDLE;

   VkBufferCreateInfo binfo = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
   VkMemoryRequirements req;
   VkMemoryAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
   const VkMemoryPropertyFlags host = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

   binfo.size = w->in_size;
   binfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
   CHECK(vkCreateBuffer(device, &binfo, NULL, &src));
   vkGetBufferMemoryRequirements(device, src, &req);
   alloc.allocationSize = req.size; alloc.memoryTypeIndex = memory_type_for(req, host);
   CHECK(vkAllocateMemory(device, &alloc, NULL, &src_memory));
   CHECK(vkBindBufferMemory(device, src, src_memory, 0));

   binfo.size = w->out_size;
   binfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
   CHECK(vkCreateBuffer(device, &binfo, NULL, &dst));
   vkGetBufferMemoryRequirements(device, dst, &req);
   alloc.allocationSize = req.size; alloc.memoryTypeIndex = memory_type_for(req, host);
   CHECK(vkAllocateMemory(device, &alloc, NULL, &dst_memory));
   CHECK(vkBindBufferMemory(device, dst, dst_memory, 0));

   VkImageCreateInfo iinfo = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
      .imageType = VK_IMAGE_TYPE_2D, .format = w->format->format,
      .extent = {w->width, w->height, 1}, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
      .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
      .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
   CHECK(vkCreateImage(device, &iinfo, NULL, &image));
   vkGetImageMemoryRequirements(device, image, &req);
   alloc.allocationSize = req.size; alloc.memoryTypeIndex = memory_type_for(req, 0);
   CHECK(vkAllocateMemory(device, &alloc, NULL, &image_memory));
   CHECK(vkBindImageMemory(device, image, image_memory, 0));

   VkCommandPoolCreateInfo pinfo = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = 0};
   CHECK(vkCreateCommandPool(device, &pinfo, NULL, &pool));

   for (int i = 0; i < w->iterations; i++) {
      void *mapped = NULL;
      bool keep_mapped = (i % 2) == 1;
      CHECK(vkMapMemory(device, src_memory, 0, VK_WHOLE_SIZE, 0, &mapped));
      memcpy(mapped, w->input, w->in_size);
      if (!keep_mapped) vkUnmapMemory(device, src_memory);

      VkCommandBufferAllocateInfo cinfo = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
         .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
      VkCommandBuffer cmd;
      CHECK(vkAllocateCommandBuffers(device, &cinfo, &cmd));
      VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
         .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
      CHECK(vkBeginCommandBuffer(cmd, &begin));
      image_barrier(cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
         0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
         VK_PIPELINE_STAGE_TRANSFER_BIT, w->format->aspect, 1, 1);
      record_upload(cmd, src, image, w->format, w->width, w->height, 0, 0, 0, 0, 0);
      image_barrier(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
         VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
         VK_PIPELINE_STAGE_TRANSFER_BIT, w->format->aspect, 1, 1);
      record_readback(cmd, image, dst, w->format, w->width, w->height, 0, 0, 0);
      host_buffer_barrier(cmd, dst);
      CHECK(vkEndCommandBuffer(cmd));

      VkFenceCreateInfo finfo = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
      VkFence fence;
      CHECK(vkCreateFence(device, &finfo, NULL, &fence));
      VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
      pthread_mutex_lock(&queue_lock);
      VkResult submitted = vkQueueSubmit(shared_queue, 1, &submit, fence);
      pthread_mutex_unlock(&queue_lock);
      if (submitted != VK_SUCCESS) { fprintf(stderr, "THREAD %d submit -> %d\n", w->id, (int)submitted); w->errors++; goto out; }
      CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, 60000000000ULL));
      vkDestroyFence(device, fence, NULL);
      vkFreeCommandBuffers(device, pool, 1, &cmd);
      if (keep_mapped) vkUnmapMemory(device, src_memory);

      void *readback;
      CHECK(vkMapMemory(device, dst_memory, 0, VK_WHOLE_SIZE, 0, &readback));
      memcpy(w->output, readback, w->out_size);
      vkUnmapMemory(device, dst_memory);
      if (w->golden && memcmp(w->output, w->golden, w->out_size) != 0) w->mismatches++;
      w->done++;
   }
out:
   if (pool) vkDestroyCommandPool(device, pool, NULL);
   if (image) vkDestroyImage(device, image, NULL);
   if (image_memory) vkFreeMemory(device, image_memory, NULL);
   if (dst) vkDestroyBuffer(device, dst, NULL);
   if (dst_memory) vkFreeMemory(device, dst_memory, NULL);
   if (src) vkDestroyBuffer(device, src, NULL);
   if (src_memory) vkFreeMemory(device, src_memory, NULL);
   return NULL;
}

int main(int argc, char **argv) {
   if (argc != 9 || strcmp(argv[1], "concurrency") != 0) {
      fprintf(stderr, "Usage: %s concurrency LIBRARY FORMAT INPUT WIDTH HEIGHT THREADS ITERATIONS\n", argv[0]);
      return 2;
   }
   const ProbeFormat *format = find_format(argv[3]);
   if (!format || !format->block_bytes) { fprintf(stderr, "FAIL stage=format (BC formats only)\n"); return 2; }
   uint32_t width = (uint32_t)strtoul(argv[5], NULL, 10), height = (uint32_t)strtoul(argv[6], NULL, 10);
   int threads = atoi(argv[7]), iterations = atoi(argv[8]);
   if (threads < 1 || threads > 32 || iterations < 1) { fprintf(stderr, "FAIL stage=arguments\n"); return 2; }

   void *input = NULL;
   size_t actual = 0;
   if (load_files(argv[4], &input, &actual)) return 1;
   size_t in_size = src_size(format, width, height), out_size = dst_allocation(format, width, height);
   if (actual != in_size) { fprintf(stderr, "FAIL stage=input-size expected=%zu actual=%zu\n", in_size, actual); return 1; }
   if (init_instance(argv[2]) || init_device()) return 1;
   vkGetDeviceQueue(device, 0, 0, &shared_queue);
   printf("THREADS=%d\nITERATIONS=%d\n", threads, iterations);

   /* Golden: one single-threaded iteration with the same code path. */
   unsigned char *golden = calloc(1, out_size);
   Worker g = {.id = -1, .format = format, .input = input, .in_size = in_size, .out_size = out_size,
      .width = width, .height = height, .iterations = 1, .output = golden};
   run_worker(&g);
   if (g.errors || g.done != 1) { printf("RESULT=fail\nFAIL_STAGE=golden\n"); return 1; }
   printf("GOLDEN_BYTES=%zu\n", out_size);
   fflush(stdout);

   Worker *workers = calloc((size_t)threads, sizeof(Worker));
   pthread_t *ids = calloc((size_t)threads, sizeof(pthread_t));
   const char *corrupt_env = getenv("CONC_CORRUPT_THREAD");
   int corrupt = corrupt_env ? atoi(corrupt_env) : -1;
   unsigned char *corrupted = NULL;
   if (corrupt >= 0) {
      corrupted = malloc(in_size);
      memcpy(corrupted, input, in_size);
      corrupted[in_size / 2] ^= 0xff;
      printf("NEGATIVE_CONTROL thread=%d\n", corrupt);
   }
   for (int t = 0; t < threads; t++) {
      workers[t] = (Worker){.id = t, .format = format, .input = (t == corrupt) ? corrupted : input, .in_size = in_size, .out_size = out_size,
         .width = width, .height = height, .iterations = iterations, .golden = golden,
         .output = calloc(1, out_size)};
      pthread_create(&ids[t], NULL, run_worker, &workers[t]);
   }
   int total = 0, mismatches = 0, errors = 0;
   for (int t = 0; t < threads; t++) {
      pthread_join(ids[t], NULL);
      printf("THREAD id=%d iterations=%d mismatches=%d errors=%d\n", t, workers[t].done,
         workers[t].mismatches, workers[t].errors);
      total += workers[t].done; mismatches += workers[t].mismatches; errors += workers[t].errors;
   }
   printf("TOTAL iterations=%d mismatches=%d errors=%d\n", total, mismatches, errors);
   vkDeviceWaitIdle(device);
   vkDestroyDevice(device, NULL);
   bool ok = errors == 0 && mismatches == 0 && total == threads * iterations;
   printf("RESULT=%s\n", ok ? "ok" : "fail");
   fflush(stdout);
   return ok ? 0 : 1;
}
