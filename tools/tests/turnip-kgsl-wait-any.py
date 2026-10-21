#!/usr/bin/env python3
"""Exercise actual Mesa KGSL wait-any source with mocked kernel/FD boundaries.

Usage: turnip-kgsl-wait-any.py BEFORE AFTER MESA_SOURCE OUTPUT
Checks behavior, descriptor ownership, allocation counts and allocation failure.
This host-side regression does not measure GPU performance or Vulkan conformance.
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
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "util/stack_array.h"
typedef int VkResult;
enum { VK_SUCCESS = 0, VK_TIMEOUT = 2, VK_ERROR_UNKNOWN = -13,
       VK_ERROR_OUT_OF_HOST_MEMORY = -1 };
enum kgsl_syncobj_state { KGSL_SYNCOBJ_STATE_UNSIGNALED,
   KGSL_SYNCOBJ_STATE_SIGNALED, KGSL_SYNCOBJ_STATE_TS, KGSL_SYNCOBJ_STATE_FD };
struct tu_device { int fd; };
struct tu_queue { unsigned msm_queue_id; };
struct kgsl_syncobj { kgsl_syncobj_state state; tu_queue *queue;
   uint32_t timestamp; int fd; };
static unsigned allocations, releases, live_allocations, copied_bytes, fail_at;
static unsigned polls, conversions, closed, single_waits, timestamp_waits;
static unsigned seen_count, seen_queue, seen_timestamp, conversion_failure;
static bool released_fds[65];
static int poll_result, poll_errno, poll_revents, interrupt_once, last_timeout;
static uint64_t now_ns;
static void *allocate(size_t size) {
   allocations++;
   if (fail_at && allocations == fail_at) { errno = ENOMEM; return NULL; }
   void *result = malloc(size);
   if (result) live_allocations++;
   return result;
}
static void release(void *pointer) {
   if (pointer) { releases++; live_allocations--; free(pointer); }
}
static void *copy_memory(void *dst, const void *src, size_t size) {
   copied_bytes += size;
   return memcpy(dst, src, size);
}
static uint64_t os_time_get_nano() { return now_ns; }
static VkResult vk_error(tu_device *, VkResult result) { return result; }
static VkResult vk_errorf(tu_device *, VkResult result, const char *, ...) { return result; }
static VkResult kgsl_sync_error(tu_device *, const char *, int) { return VK_ERROR_UNKNOWN; }
static VkResult kgsl_syncobj_wait(tu_device *, kgsl_syncobj *, uint64_t) {
   single_waits++; return VK_TIMEOUT;
}
static VkResult wait_timestamp_safe(tu_device *, unsigned queue, unsigned ts, uint64_t) {
   timestamp_waits++; seen_queue = queue; seen_timestamp = ts; return VK_SUCCESS;
}
static int timestamp_to_fd(tu_queue *, unsigned) {
   conversions++;
   if (conversions == conversion_failure) { errno = EMFILE; return -1; }
   return 1000 + conversions;
}
static int close_fd(int fd) {
   assert(fd >= 1001 && fd <= 1000 + (int)conversions);
   assert(!released_fds[fd - 1000]);
   released_fds[fd - 1000] = true;
   closed++; return 0;
}
static int poll_boundary(struct pollfd *fds, nfds_t count, int timeout) {
   polls++; seen_count = count; last_timeout = timeout;
   if (interrupt_once) { interrupt_once = 0; now_ns += 2000000; errno = EINTR; return -1; }
   for (nfds_t i = 0; i < count; i++) {
      assert(fds[i].events == POLLIN);
      fds[i].revents = i == 0 ? poll_revents : 0;
   }
   errno = poll_errno;
   return poll_result;
}
#define MIN2(a, b) ((a) < (b) ? (a) : (b))
#define util_is_power_of_two_nonzero(x) ((x) && !((x) & ((x) - 1)))
#define align(x, a) (((x) + (a) - 1) & ~((a) - 1))
#define HAVE___BUILTIN_CLZ 1
#define malloc allocate
#define free release
#define memcpy copy_memory
#define close close_fd
#define poll poll_boundary
'''

CASES = r'''
static void reset() {
   assert(live_allocations == 0);
   allocations = releases = copied_bytes = fail_at = 0;
   polls = conversions = closed = single_waits = timestamp_waits = 0;
   seen_count = seen_queue = seen_timestamp = conversion_failure = 0;
   memset(released_fds, 0, sizeof(released_fds));
   poll_result = 1; poll_errno = 0; poll_revents = POLLIN; interrupt_once = 0;
   last_timeout = -2; now_ns = 1000000000;
}
int main(int argc, char **) {
   tu_device device = {7}; tu_queue a = {11}, b = {12};
   kgsl_syncobj items[64] = {}; kgsl_syncobj *ptrs[64];
   for (unsigned i = 0; i < 64; i++) {
      items[i] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, (int)i + 1}; ptrs[i] = &items[i];
   }
   unsigned cases = 0, failures = 0;
#define EXPECT(name, condition) do { cases++; if (!(condition)) { failures++; \
   printf("FAIL %s\n", name); } } while (0)
   if (argc > 1) {
      reset(); fail_at = 1;
      VkResult result = kgsl_syncobj_wait_any(&device, ptrs, 9, 0);
      printf("oom_result=%d live=%u\n", result, live_allocations);
      return result == VK_ERROR_OUT_OF_HOST_MEMORY && !live_allocations && !polls ? 0 : 1;
   }
   reset(); EXPECT("empty", kgsl_syncobj_wait_any(&device, ptrs, 0, 0) == VK_TIMEOUT && !allocations);
   reset(); EXPECT("single", kgsl_syncobj_wait_any(&device, ptrs, 1, 0) == VK_TIMEOUT && single_waits == 1 && !allocations);
   items[0].state = KGSL_SYNCOBJ_STATE_SIGNALED;
   reset(); EXPECT("already-signaled", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_SUCCESS && !allocations && !polls);
   items[0].state = items[1].state = KGSL_SYNCOBJ_STATE_UNSIGNALED;
   reset(); EXPECT("unresolved", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_TIMEOUT && !allocations);
   items[0] = {KGSL_SYNCOBJ_STATE_TS, &a, 20, -1};
   items[1] = {KGSL_SYNCOBJ_STATE_TS, &a, 10, -1};
   reset(); EXPECT("same-queue-earliest", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_SUCCESS && seen_timestamp == 10 && !allocations && !conversions);
   items[0].timestamp = 3; items[1].timestamp = 0xfffffff0;
   reset(); EXPECT("timestamp-wrap", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_SUCCESS && seen_timestamp == 0xfffffff0 && !allocations);
   for (unsigned i = 0; i < 64; i++) items[i] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, (int)i + 1};
   for (unsigned count : {2u, 8u, 9u, 32u, 64u}) {
      reset();
      EXPECT("fd-wait", kgsl_syncobj_wait_any(&device, ptrs, count, 0) == VK_SUCCESS && seen_count == count && !closed && !live_allocations);
      unsigned old_allocations = 1;
      for (unsigned capacity = 4; capacity < count; capacity *= 2) old_allocations++;
      EXPECT("allocation-bound", allocations == (CANDIDATE ? (count > STACK_ARRAY_SIZE ? 1u : 0u) : old_allocations));
      if (CANDIDATE) EXPECT("no-growth-copy", copied_bytes == 0);
      printf("METRIC fds=%u allocations=%u copied_bytes=%u\n", count, allocations, copied_bytes);
   }
   for (unsigned i = 0; i < 63; i++) items[i] = {KGSL_SYNCOBJ_STATE_TS, &a, i + 10, -1};
   reset();
   EXPECT("many-timestamps-one-fd", kgsl_syncobj_wait_any(&device, ptrs, 64, 0) == VK_SUCCESS && seen_count == 2 && closed == 1 && allocations == (CANDIDATE ? 0u : 1u));
   for (unsigned i = 0; i < 62; i++) items[i].state = KGSL_SYNCOBJ_STATE_UNSIGNALED;
   items[62] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, 63};
   reset();
   EXPECT("many-unresolved-two-fds", kgsl_syncobj_wait_any(&device, ptrs, 64, 0) == VK_SUCCESS && seen_count == 2 && !closed && allocations == (CANDIDATE ? 0u : 1u));
   for (unsigned i = 0; i < 64; i++) items[i] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, (int)i + 1};
   items[0] = {KGSL_SYNCOBJ_STATE_TS, &a, 20, -1};
   items[1] = {KGSL_SYNCOBJ_STATE_TS, &b, 10, -1};
   reset(); EXPECT("different-queues", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_SUCCESS && conversions == 2 && closed == 2 && seen_count == 3);
   reset(); poll_result = 0;
   EXPECT("owned-fence-timeout-cleanup", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_TIMEOUT && closed == 2 && !live_allocations);
   reset(); poll_result = -1; poll_errno = EBADF;
   EXPECT("owned-fence-error-cleanup", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_ERROR_UNKNOWN && closed == 2 && !live_allocations);
   reset(); conversion_failure = 1;
   EXPECT("first-conversion-failure", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_ERROR_UNKNOWN && !closed && !polls && !live_allocations);
   reset(); conversion_failure = 2;
   EXPECT("second-conversion-failure", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_ERROR_UNKNOWN && closed == 1 && !polls && !live_allocations);
   items[1].queue = &a;
   reset(); EXPECT("mixed-earliest", kgsl_syncobj_wait_any(&device, ptrs, 3, 0) == VK_SUCCESS && conversions == 1 && closed == 1 && seen_count == 2);
   items[0] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, 1};
   items[1] = {KGSL_SYNCOBJ_STATE_FD, NULL, 0, 2};
   reset(); poll_result = 0;
   EXPECT("poll-timeout", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_TIMEOUT && !closed && !live_allocations);
   reset(); poll_result = -1; poll_errno = EBADF;
   EXPECT("poll-error", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_ERROR_UNKNOWN && !closed && !live_allocations);
   reset(); poll_revents = POLLNVAL;
   EXPECT("invalid-fence", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_ERROR_UNKNOWN && !closed && !live_allocations);
   reset(); poll_revents = POLLERR;
   EXPECT("faulted-fence", kgsl_syncobj_wait_any(&device, ptrs, 2, 0) == VK_ERROR_UNKNOWN && !closed && !live_allocations);
   reset(); interrupt_once = 1;
   EXPECT("interrupted-deadline", kgsl_syncobj_wait_any(&device, ptrs, 2, now_ns + 1000000) == VK_SUCCESS && polls == 2 && last_timeout == 0);
   reset(); EXPECT("infinite-timeout", kgsl_syncobj_wait_any(&device, ptrs, 2, UINT64_MAX) == VK_SUCCESS && last_timeout == -1);
   if (CANDIDATE) {
      reset(); fail_at = 1;
      EXPECT("stack-does-not-allocate", kgsl_syncobj_wait_any(&device, ptrs, 8, 0) == VK_SUCCESS && !allocations);
      reset(); fail_at = 1;
      EXPECT("large-allocation-failure", kgsl_syncobj_wait_any(&device, ptrs, 9, 0) == VK_ERROR_OUT_OF_HOST_MEMORY && !polls && !closed && !live_allocations);
   }
   printf("cases=%u failures=%u\n", cases, failures);
   return failures ? 1 : 0;
}
'''


def function(text, name):
    match = re.search(r"^(?:static(?: inline)?\s+)?[\w *]+\s+" + name + r"\([^)]*\)\s*\{.*?^}", text, re.M | re.S)
    if not match:
        raise ValueError("Missing source function: " + name)
    return match.group(0)


def main():
    before, after, mesa, output = map(Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    vector = (mesa / "src/util/u_vector.h").read_text()
    utility = (mesa / "src/util/u_vector.c").read_text()
    math = (mesa / "src/util/u_math.h").read_text()
    struct = re.search(r"struct u_vector \{.*?^};", vector, re.M | re.S).group(0)
    common = struct + "\n" + function(math, "util_next_power_of_two") + "\n"
    common += "\n".join(function(utility, n) for n in ("u_vector_init_pow2", "u_vector_add")) + "\n"
    common += "\n".join(function(vector, n) for n in ("u_vector_init", "u_vector_length", "u_vector_finish"))
    results = []
    for label, path in (("before", before), ("after", after)):
        text = path.read_text()
        macro = re.search(r"^#define kgsl_syncobj_foreach_state.*?(?=\n\n)", text, re.M | re.S).group(0)
        header = text[:text.index("#include")]
        generated = output / (label + ".cpp")
        generated.write_text(header + SUPPORT + "\n" + common + "\n" + macro + "\n" +
            "\n".join(function(text, n) for n in ("get_relative_ms", "timestamp_cmp", "kgsl_syncobj_wait_any")) + CASES)
        binary = output / label
        command = shlex.split(os.environ.get("CXX", "clang++")) + ["-std=c++17", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
            "-Wno-unused-function", "-Wno-sign-compare", "-Wno-unused-parameter", "-Wno-missing-field-initializers",
            "-include", "initializer_list",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-DCANDIDATE=" + ("1" if label == "after" else "0"),
            "-I" + str(mesa / "src"), str(generated), "-o", str(binary)]
        subprocess.run(command, check=True)
        normal = subprocess.run([str(binary)], capture_output=True, text=True, timeout=20)
        oom = subprocess.run([str(binary), "oom"], capture_output=True, text=True, timeout=20)
        (output / (label + ".log")).write_text(normal.stdout + normal.stderr)
        (output / (label + "-oom.log")).write_text(oom.stdout + oom.stderr)
        result = {"phase": label, "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "exit_code": normal.returncode, "output": normal.stdout, "sanitizer_output": normal.stderr,
            "oom_exit_code": oom.returncode, "oom_output": oom.stdout,
            "oom_detected_invalid_access": "AddressSanitizer" in oom.stderr or "runtime error:" in oom.stderr}
        results.append(result)
        print(json.dumps(result, indent=2))
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    assert all(r["exit_code"] == 0 for r in results)
    assert "runtime error: division by zero" in results[0]["sanitizer_output"]
    assert not results[1]["sanitizer_output"]
    assert results[0]["oom_exit_code"] != 0 and results[0]["oom_detected_invalid_access"]
    assert results[1]["oom_exit_code"] == 0 and not results[1]["oom_detected_invalid_access"]


if __name__ == "__main__":
    main()
