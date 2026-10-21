#!/usr/bin/env python3
"""Compile pinned wrapper generators with the actual upstream log macros.

Usage: vulkan-wrapper-trace.py BEFORE_GENERATOR AFTER_GENERATOR MESA_SOURCE OUTPUT
Vulkan execution and structure printers are stubs; no GPU benchmark is performed.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys


SUPPORT = r'''
#include <assert.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "vulkan/vulkan_core.h"
static int __wrapper_commands;
static unsigned getter_calls, printer_calls, driver_calls;
static int log_level;
static bool trace_enabled;
static FILE *log_file;
static VkResult driver_result;
static int should_log(void) { return 0; }
static int should_log_cmd(void) { return log_level; }
static FILE *get_wrapper_cmd_log_fd(void) { getter_calls++; return log_file; }
static bool use_wrapper_trace(void) { return trace_enabled; }
static void wlog(const char *fmt, ...) { (void)fmt; assert(false); }
static int __android_log_print(int p, const char *tag, const char *fmt, ...) {
   (void)p; (void)tag; (void)fmt; assert(false); return 0;
}
#define LOG(...) __android_log_print(6, "VkWrapper", __VA_ARGS__)
'''

STUBS = r'''
#define VK_PRINT_VkImageCreateInfo(prefix, info) ((void)(prefix), (void)(info), printer_calls++)
#define VK_PRINT_VkAllocationCallbacks(prefix, info) ((void)(prefix), (void)(info), printer_calls++)
#define VK_PRINT_VkSubmitInfo(prefix, info) ((void)(prefix), (void)(info), printer_calls++)
static VkResult __wrapper_CreateImage(VkDevice device, const VkImageCreateInfo *ci,
   const VkAllocationCallbacks *alloc, VkImage *out) {
   (void)device; (void)ci; (void)alloc; driver_calls++;
   if (driver_result == VK_SUCCESS) *out = (VkImage)(uintptr_t)42;
   return driver_result;
}
static VkResult __wrapper_QueueSubmit(VkQueue queue, uint32_t count,
   const VkSubmitInfo *submits, VkFence fence) {
   (void)queue; (void)count; (void)submits; (void)fence; driver_calls++; return driver_result;
}
static void __wrapper_CmdBindVertexBuffers(VkCommandBuffer cmd, uint32_t first,
   uint32_t count, const VkBuffer *buffers, const VkDeviceSize *offsets) {
   (void)cmd; (void)first; (void)count; (void)buffers; (void)offsets; driver_calls++;
}
'''

CASES = r'''
static unsigned run_case(const char *name, int level, bool file, bool trace,
                         VkResult result, bool expect_details) {
   log_level = level; trace_enabled = trace; driver_result = result;
   log_file = file ? tmpfile() : NULL;
   assert(!file || log_file);
   getter_calls = printer_calls = driver_calls = 0;
   VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
   VkImage image = VK_NULL_HANDLE;
   assert(wrapper_CreateImage(VK_NULL_HANDLE, &ci, NULL, &image) == result);
   assert(image == (result == VK_SUCCESS ? (VkImage)(uintptr_t)42 : VK_NULL_HANDLE));
   VkBuffer buffers[64] = {0};
   VkDeviceSize offsets[64] = {0};
   wrapper_CmdBindVertexBuffers(VK_NULL_HANDLE, 0, 64, buffers, offsets);
   VkSubmitInfo submits[32] = {0};
   assert(wrapper_QueueSubmit(VK_NULL_HANDLE, 32, submits, VK_NULL_HANDLE) == result);
   assert(driver_calls == 3);
   long bytes = log_file ? ftell(log_file) : 0;
   bool actual_details = printer_calls != 0;
   printf("METRIC %s getters=%u printers=%u log_bytes=%ld driver_calls=%u\n",
          name, getter_calls, printer_calls, bytes, driver_calls);
   if (log_file) fclose(log_file);
   return actual_details != expect_details || (file && !expect_details && bytes != 0);
}
int main(void) {
   unsigned failures = 0;
   failures += run_case("off", 0, false, false, VK_SUCCESS, false);
   failures += run_case("names-only", 1, true, false, VK_SUCCESS, false);
   failures += run_case("trace-disabled", 2, true, false, VK_SUCCESS, false);
   failures += run_case("trace-enabled", 2, true, true, VK_SUCCESS, true);
   failures += run_case("missing-output", 2, false, true, VK_SUCCESS, false);
   failures += run_case("driver-error-traced", 2, true, true, VK_ERROR_OUT_OF_DEVICE_MEMORY, true);
   failures += run_case("driver-error-off", 0, false, false, VK_ERROR_OUT_OF_DEVICE_MEMORY, false);
   printf("cases=7 failures=%u\n", failures);
   return failures ? 1 : 0;
}
'''


def main():
    before, after, mesa, output = (Path(p).resolve() for p in sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(mesa / "src/vulkan/wrapper"))
    log_header = mesa / "src/vulkan/wrapper/wrapper_log.h"
    header = log_header.read_text()
    macros = header[header.index("#define LOG_FD"):header.index("void get_current_time_string")]
    rows = []
    for label, generator in (("before", before), ("after", after)):
        spec = importlib.util.spec_from_file_location("trace_" + label, generator)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        entries = module.get_entrypoints_from_xml([str(mesa / "src/vulkan/registry/vk.xml")], "false")
        selected = [e.wrap() for e in entries if e.name in ("CreateImage", "QueueSubmit", "CmdBindVertexBuffers") and not e.alias]
        assert len(selected) == 3
        source = output / (label + ".c")
        source.write_text(SUPPORT + macros + STUBS + "\n".join(selected) + CASES)
        binary = output / label
        command = shlex.split(os.environ.get("CC", "clang")) + [
            "-std=gnu17", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-function", "-Wno-unused-variable", "-Wno-pointer-bool-conversion", "-Wno-error=format",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
            "-I" + str(mesa / "include"), str(source), "-o", str(binary)]
        compiled = subprocess.run(command, capture_output=True, text=True)
        (output / (label + "-compile.log")).write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError(compiled.stderr)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=20)
        (output / (label + ".log")).write_text(result.stdout + result.stderr)
        row = {"phase": label, "generator_sha256": hashlib.sha256(generator.read_bytes()).hexdigest(),
               "log_macros_sha256": hashlib.sha256(macros.encode()).hexdigest(),
               "generated_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
               "exit_code": result.returncode, "output": result.stdout, "sanitizer_output": result.stderr}
        rows.append(row)
        print(json.dumps(row, indent=2))
    (output / "results.json").write_text(json.dumps(rows, indent=2) + "\n")
    assert rows[0]["exit_code"] == 1 and "cases=7 failures=5" in rows[0]["output"]
    assert rows[1]["exit_code"] == 0 and "cases=7 failures=0" in rows[1]["output"]
    assert not rows[1]["sanitizer_output"]


if __name__ == "__main__":
    main()
