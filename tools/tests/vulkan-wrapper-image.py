#!/usr/bin/env python3
"""Check wrapper CreateImage source; Vulkan allocation/format helpers are mocked.

Usage: vulkan-wrapper-image.py BEFORE AFTER WRAPPER_HEADERS MESA_SOURCE OUTPUT
Checks temporary allocations, format routing, pNext ownership and error cleanup.
The lower Vulkan driver, registry-size lookup and bookkeeping are mocked.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


SUPPORT = r'''
#include <assert.h>
#include <stdalign.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "vulkan/vulkan_core.h"
#include "util/list.h"
#include "util/macros.h"
enum { OVERRIDE_NONE, OVERRIDE_DISABLED, OVERRIDE_REDUCE };
struct wrapper_physical_device { bool needs_bc1_emulation, needs_bc4_emulation; };
struct wrapper_device { struct wrapper_physical_device *physical; int depth_override_mode;
   struct { int alloc; } vk; };
struct wrapper_image { VkFormat original_format, format; bool is_bcn_emulated, is_depth_stencil_reduced; };
struct temporary_objects { struct list_head objects; };
struct temp_object_node { void *ptr; struct wrapper_device *device; struct list_head link; };
static unsigned allocations, releases, creates, destroys, metadata_creates, mask_reads;
static bool allocation_failure;
static uint32_t disabled_mask;
static bool metadata_failure;
static VkResult create_result;
static VkImageCreateInfo submitted;
static struct wrapper_image image;
static const VkAllocationCallbacks *create_allocator, *destroy_allocator;
static VkStructureType chain_types[16];
static const void *chain_pointers[16];
static unsigned chain_count, view_count;
static VkFormat view_format;
static VkExternalMemoryHandleTypeFlags external_handles;
static void *vk_alloc(void *allocator, size_t size, size_t alignment, VkSystemAllocationScope scope) {
   (void)allocator; (void)alignment; (void)scope;
   allocations++; return allocation_failure ? NULL : calloc(1, size);
}
static void *vk_zalloc(void *allocator, size_t size, size_t alignment, VkSystemAllocationScope scope) {
   return vk_alloc(allocator, size, alignment, scope);
}
static void vk_free(void *allocator, void *ptr) {
   (void)allocator;
   if (ptr) { releases++; free(ptr); }
}
static void free_temp_objects(struct temporary_objects *temp) {
   while (!list_is_empty(&temp->objects)) {
      struct temp_object_node *node = list_first_entry(&temp->objects, struct temp_object_node, link);
      list_del(&node->link); free(node->ptr); free(node); releases += 2;
   }
}
static bool is_bc123_image_format(VkFormat f) { return f >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && f <= VK_FORMAT_BC3_SRGB_BLOCK; }
static bool is_bc4567_image_format(VkFormat f) { return f >= VK_FORMAT_BC4_UNORM_BLOCK && f <= VK_FORMAT_BC7_SRGB_BLOCK; }
static uint32_t get_disabled_bcn_masks(void) { mask_reads++; return disabled_mask; }
static VkFormat get_depth_stencil_vk_format(struct wrapper_device *d, VkFormat f) { (void)d; (void)f; return VK_FORMAT_D16_UNORM; }
static VkFormat unwrap_vk_format(struct wrapper_device *d, VkFormat f) { (void)d; (void)f; return VK_FORMAT_R8G8B8A8_UNORM; }
static VkResult create_image(VkDevice d, const VkImageCreateInfo *ci, const VkAllocationCallbacks *a, VkImage *out) {
   (void)d; creates++; submitted = *ci; create_allocator = a;
   for (const VkBaseInStructure *node = ci->pNext; node; node = node->pNext) {
      assert(chain_count < 16);
      chain_types[chain_count] = node->sType; chain_pointers[chain_count++] = node;
      if (node->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
         const VkImageFormatListCreateInfo *list = (const void *)node;
         view_count = list->viewFormatCount;
         if (view_count) view_format = list->pViewFormats[0];
      } else if (node->sType == VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO) {
         external_handles = ((const VkExternalMemoryImageCreateInfo *)node)->handleTypes;
      }
   }
   if (create_result == VK_SUCCESS) *out = (VkImage)(uintptr_t)42;
   return create_result;
}
static void destroy_image(VkDevice d, VkImage handle, const VkAllocationCallbacks *a) {
   (void)d; assert(handle == (VkImage)(uintptr_t)42); destroys++; destroy_allocator = a;
}
static const struct { VkResult (*CreateImage)(VkDevice, const VkImageCreateInfo *, const VkAllocationCallbacks *, VkImage *);
   void (*DestroyImage)(VkDevice, VkImage, const VkAllocationCallbacks *); } wrapper_device_trampolines = {create_image, destroy_image};
static struct wrapper_image *wrapper_image_create(struct wrapper_device *d, const VkImageCreateInfo *ci, VkImage handle) {
   (void)d; (void)ci; (void)handle; metadata_creates++; return metadata_failure ? NULL : &image;
}
#define VK_FROM_HANDLE(type, name, handle) struct type *name = (struct type *)(handle)
#define WLOGD(...) ((void)0)
#define WLOGE(...) ((void)0)
#define vk_error(device, result) (result)
#define WRAPPER_CreateImage(...) VkResult tested_CreateImage(__VA_ARGS__)
#define vk_foreach_struct_const(node, start) for (const VkBaseInStructure *node = (const VkBaseInStructure *)(start); node; node = node->pNext)
static const VkImageFormatListCreateInfo *find_format_list(const void *start) {
   vk_foreach_struct_const(node, start) {
      if (node->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) return (const void *)node;
   }
   return NULL;
}
#define vk_find_struct_const(start, type) find_format_list(start)
static size_t vk_structure_type_size(const VkBaseInStructure *node) {
   switch (node->sType) {
   case VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO: return sizeof(VkExternalMemoryImageCreateInfo);
   case VK_STRUCTURE_TYPE_IMAGE_STENCIL_USAGE_CREATE_INFO: return sizeof(VkImageStencilUsageCreateInfo);
   default: return 0;
   }
}
#include "wrapper_checks.h"
'''

CASES = r'''
static void reset(void) {
   allocations = releases = creates = destroys = metadata_creates = mask_reads = 0;
   disabled_mask = 0; metadata_failure = allocation_failure = false; create_result = VK_SUCCESS;
   chain_count = view_count = 0; view_format = VK_FORMAT_UNDEFINED; external_handles = 0;
   create_allocator = destroy_allocator = NULL;
   memset(&submitted, 0, sizeof(submitted)); memset(&image, 0, sizeof(image));
}
int main(int argc, char **argv) {
   struct wrapper_physical_device physical = {false, false};
   struct wrapper_device device = {.physical = &physical, .depth_override_mode = OVERRIDE_NONE};
   VkDevice handle = (VkDevice)&device;
   VkImage out = VK_NULL_HANDLE;
   VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
      .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = {128, 64, 1}, .mipLevels = 1, .arrayLayers = 1,
      .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = VK_IMAGE_USAGE_SAMPLED_BIT};
   if (argc > 1 && !strcmp(argv[1], "readonly")) {
      size_t size = (size_t)sysconf(_SC_PAGESIZE);
      void *memory = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
      assert(memory != MAP_FAILED);
      VkImageFormatListCreateInfo *list = memory;
      VkFormat *format = (void *)(list + 1);
      *format = VK_FORMAT_BC1_RGB_UNORM_BLOCK;
      *list = (VkImageFormatListCreateInfo){.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO,
         .viewFormatCount = 1, .pViewFormats = format};
      assert(!mprotect(memory, size, PROT_READ));
      ci.format = *format; ci.pNext = list; physical.needs_bc1_emulation = true;
      assert(tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS);
      assert(view_count == 1 && view_format == VK_FORMAT_R8G8B8A8_UNORM && *format == ci.format);
      munmap(memory, size); puts("readonly-format-list=preserved"); return 0;
   }
   unsigned cases = 0, failures = 0;
#define EXPECT(name, condition) do { cases++; if (!(condition)) { failures++; printf("FAIL %s\n", name); } } while (0)
   reset(); EXPECT("ordinary-image", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && submitted.format == ci.format && !image.is_bcn_emulated && creates == 1);
   EXPECT("temporary-allocations", allocations == (CANDIDATE ? 0u : 2u) && releases == allocations);
   EXPECT("skip-unneeded-mask", mask_reads == (CANDIDATE ? 0u : 1u));
   printf("METRIC ordinary temporary_allocations=%u mask_reads=%u\n", allocations, mask_reads);
   ci.format = VK_FORMAT_BC1_RGB_UNORM_BLOCK;
   reset(); EXPECT("hardware-bc", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && submitted.format == ci.format && !image.is_bcn_emulated);
   physical.needs_bc1_emulation = true;
   reset(); EXPECT("emulated-bc", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && image.is_bcn_emulated && submitted.format == VK_FORMAT_R8G8B8A8_UNORM && ci.format == VK_FORMAT_BC1_RGB_UNORM_BLOCK);
   EXPECT("emulated-image-flags", (submitted.flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT) && (submitted.usage & VK_IMAGE_USAGE_STORAGE_BIT));
   reset(); disabled_mask = 1u;
   EXPECT("disabled-emulation", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && !image.is_bcn_emulated && submitted.format == ci.format);
   ci.format = VK_FORMAT_BC7_SRGB_BLOCK; physical.needs_bc4_emulation = true;
   reset(); disabled_mask = 1u << (VK_FORMAT_BC7_SRGB_BLOCK - VK_FORMAT_BC1_RGB_UNORM_BLOCK);
   EXPECT("last-bc-mask-bit", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && !image.is_bcn_emulated && submitted.format == ci.format);
   ci.format = VK_FORMAT_D32_SFLOAT; ci.usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
   device.depth_override_mode = OVERRIDE_DISABLED;
   reset(); EXPECT("disabled-depth", tested_CreateImage(handle, &ci, NULL, &out) == VK_ERROR_FORMAT_NOT_SUPPORTED && !creates && allocations == releases);
   device.depth_override_mode = OVERRIDE_REDUCE;
   reset(); EXPECT("reduced-depth", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && submitted.format == VK_FORMAT_D16_UNORM && image.is_depth_stencil_reduced && ci.format == VK_FORMAT_D32_SFLOAT);
   reset(); create_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
   EXPECT("backend-failure", tested_CreateImage(handle, &ci, NULL, &out) == VK_ERROR_OUT_OF_DEVICE_MEMORY && !metadata_creates && !destroys && allocations == releases);
   device.depth_override_mode = OVERRIDE_NONE;
   reset(); metadata_failure = true;
   VkAllocationCallbacks allocator = {0};
   EXPECT("metadata-failure-cleanup", tested_CreateImage(handle, &ci, &allocator, &out) == VK_ERROR_OUT_OF_HOST_MEMORY && destroys == (CANDIDATE ? 1u : 0u) && allocations == releases);
   EXPECT("original-destroy-allocator", create_allocator == &allocator && (!CANDIDATE || destroy_allocator == &allocator));
   ci.format = VK_FORMAT_BC1_RGB_UNORM_BLOCK; ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
   VkFormat formats[] = {VK_FORMAT_BC1_RGB_UNORM_BLOCK, VK_FORMAT_BC1_RGB_SRGB_BLOCK};
   VkImageFormatListCreateInfo list = {.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO,
      .viewFormatCount = 2, .pViewFormats = formats};
   ci.pNext = &list;
   reset(); EXPECT("format-list-rewritten", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && view_count == 1 && view_format == VK_FORMAT_R8G8B8A8_UNORM);
   EXPECT("caller-format-list-preserved", list.viewFormatCount == 2 && formats[0] == ci.format && formats[1] == VK_FORMAT_BC1_RGB_SRGB_BLOCK && ci.pNext == &list && !list.pNext);
   list.viewFormatCount = 2; formats[0] = ci.format;
   VkBaseInStructure opaque = {.sType = (VkStructureType)0x7ffffffe};
   list.pNext = &opaque;
   VkExternalMemoryImageCreateInfo external = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
      .pNext = &list, .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
   VkImageStencilUsageCreateInfo stencil = {.sType = VK_STRUCTURE_TYPE_IMAGE_STENCIL_USAGE_CREATE_INFO,
      .pNext = &external, .stencilUsage = VK_IMAGE_USAGE_SAMPLED_BIT};
   ci.pNext = &stencil;
   reset(); EXPECT("middle-format-list-rewritten", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && view_count == 1 && view_format == VK_FORMAT_R8G8B8A8_UNORM);
   EXPECT("extension-order-and-tail", chain_count == 4 && chain_types[0] == stencil.sType && chain_types[1] == external.sType && chain_types[2] == list.sType && chain_pointers[3] == &opaque && external_handles == external.handleTypes);
   EXPECT("caller-chain-preserved", stencil.pNext == &external && external.pNext == &list && list.pNext == &opaque && list.viewFormatCount == 2 && formats[0] == ci.format);
   EXPECT("chain-scratch-released", allocations == releases);
   if (CANDIDATE) {
      reset(); allocation_failure = true;
      EXPECT("chain-allocation-failure", tested_CreateImage(handle, &ci, NULL, &out) == VK_ERROR_OUT_OF_HOST_MEMORY && !creates && !releases);
      reset(); create_result = VK_ERROR_OUT_OF_DEVICE_MEMORY;
      EXPECT("chain-backend-failure", tested_CreateImage(handle, &ci, NULL, &out) == VK_ERROR_OUT_OF_DEVICE_MEMORY && allocations == releases && !destroys);
      reset(); metadata_failure = true;
      EXPECT("chain-metadata-failure", tested_CreateImage(handle, &ci, &allocator, &out) == VK_ERROR_OUT_OF_HOST_MEMORY && destroys == 1 && destroy_allocator == &allocator && allocations == releases);
      opaque.pNext = (const void *)&list; list.pNext = NULL; ci.pNext = &opaque;
      reset(); EXPECT("unknown-prefix-safe-failure", tested_CreateImage(handle, &ci, NULL, &out) == VK_ERROR_FORMAT_NOT_SUPPORTED && !creates && !allocations && formats[0] == ci.format);
      opaque.pNext = NULL;
   }
   list.viewFormatCount = 0; list.pNext = NULL; formats[0] = ci.format; ci.pNext = &list;
   reset(); EXPECT("empty-format-list-preserved", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && view_count == 0 && formats[0] == ci.format);
   ci.pNext = &opaque;
   reset(); EXPECT("unmodified-opaque-chain", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && chain_count == 1 && chain_pointers[0] == &opaque && allocations == releases);
   ci.pNext = &list; list.viewFormatCount = 2; formats[0] = ci.format; physical.needs_bc1_emulation = false;
   reset(); EXPECT("hardware-format-list-passthrough", tested_CreateImage(handle, &ci, NULL, &out) == VK_SUCCESS && chain_pointers[0] == &list && view_count == 2 && formats[0] == ci.format);
   printf("cases=%u failures=%u\n", cases, failures);
   return failures ? 1 : 0;
}
'''


def main():
    before, after, headers, mesa, output = map(Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for label, path in (("before", before), ("after", after)):
        text = path.read_text()
        match = re.search(r"^WRAPPER_CreateImage\(.*?^}", text, re.M | re.S)
        if not match:
            raise ValueError("Missing wrapper CreateImage source")
        generated = output / (label + ".c")
        generated.write_text(SUPPORT + match.group(0) + CASES)
        binary = output / label
        command = shlex.split(os.environ.get("CC", "clang")) + ["-std=gnu17", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-function", "-Wno-sign-compare", "-Wno-unused-parameter", "-Wno-missing-field-initializers",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-DCANDIDATE=" + ("1" if label == "after" else "0"),
            "-I" + str(mesa / "src"), "-I" + str(mesa / "include"), "-I" + str(headers), str(generated), "-o", str(binary)]
        subprocess.run(command, check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=20)
        (output / (label + ".log")).write_text(result.stdout + result.stderr)
        readonly = subprocess.run([str(binary), "readonly"], capture_output=True, text=True, timeout=20)
        (output / (label + "-readonly.log")).write_text(readonly.stdout + readonly.stderr)
        row = {"phase": label, "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "exit_code": result.returncode, "output": result.stdout, "sanitizer_output": result.stderr,
            "readonly_exit_code": readonly.returncode, "readonly_output": readonly.stdout,
            "readonly_detected_invalid_write": "AddressSanitizer" in readonly.stderr and "WRITE" in readonly.stderr}
        results.append(row)
        print(json.dumps(row, indent=2))
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    assert results[0]["exit_code"] == 1
    assert "FAIL caller-format-list-preserved" in results[0]["output"]
    assert "FAIL caller-chain-preserved" in results[0]["output"]
    assert results[0]["readonly_exit_code"] != 0 and results[0]["readonly_detected_invalid_write"]
    assert "runtime error: shift exponent" in results[0]["sanitizer_output"]
    assert results[1]["exit_code"] == results[1]["readonly_exit_code"] == 0
    assert not results[1]["sanitizer_output"]


if __name__ == "__main__":
    main()
