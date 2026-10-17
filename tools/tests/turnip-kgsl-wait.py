#!/usr/bin/env python3
"""Check Mesa's actual KGSL wait functions with a mocked clock/ioctl boundary.

Usage: python3 tools/tests/turnip-kgsl-wait.py <before.cc> <after.cc> <output-dir>
The old source must fail and the fixed source must pass. This is not a GPU test.
Inputs: Mesa e984ef294eaf5729d8434694f27de32a8922205c^ and that commit.
No network access, kernel waits, or source rewriting occurs in this test.
"""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


HARNESS = r'''
#include <stdint.h>
#include <limits.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
typedef int VkResult;
enum { VK_SUCCESS = 0, VK_TIMEOUT = 2, VK_ERROR_UNKNOWN = -13, VK_ERROR_DEVICE_LOST = -4 };
enum { IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID = 1,
       IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID = 2, KGSL_TIMESTAMP_RETIRED = 2 };
struct tu_device { int fd, vk; };
struct kgsl_device_waittimestamp_ctxtid { unsigned context_id, timestamp, timeout; };
struct kgsl_cmdstream_readtimestamp_ctxtid { unsigned context_id, type, timestamp; };
static uint64_t now_ns;
static unsigned retired, waits, polls, zero_waits, last_timeout;
static int wait_error, read_error, retry_error;
static uint64_t retry_advance;
static uint64_t os_time_get_nano() { return now_ns; }
static VkResult vk_device_set_lost(int *, const char *, ...) { return VK_ERROR_DEVICE_LOST; }
static VkResult vk_errorf(tu_device *, VkResult error, const char *, ...) { return error; }
static int test_ioctl(int fd, unsigned long request, void *arg) {
    if (fd != 7) { errno = EBADF; return -1; }
    if (request == IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID) {
        polls++;
        auto *read = static_cast<kgsl_cmdstream_readtimestamp_ctxtid *>(arg);
        if (read->context_id != 9 || read->type != KGSL_TIMESTAMP_RETIRED) { errno = EINVAL; return -1; }
        if (read_error) { errno = read_error; return -1; }
        read->timestamp = retired;
        return 0;
    }
    if (request != IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID) { errno = EINVAL; return -1; }
    waits++;
    auto *wait = static_cast<kgsl_device_waittimestamp_ctxtid *>(arg);
    last_timeout = wait->timeout;
    // Never perform the real kernel's indefinite zero-timeout wait.
    if (!wait->timeout) { zero_waits++; errno = EBUSY; return -1; }
    if (retry_error) { errno = retry_error; retry_error = 0; now_ns += retry_advance; return -1; }
    if (wait_error) { errno = wait_error; return -1; }
    return 0;
}
static int safe_ioctl(int fd, unsigned long request, void *arg) {
    int result;
    do { result = test_ioctl(fd, request, arg); } while (result < 0 && (errno == EINTR || errno == EAGAIN));
    return result;
}
#define ioctl test_ioctl
'''

CASES = r'''
static void reset() {
    now_ns = 1000000000; retired = 10; waits = polls = zero_waits = last_timeout = 0;
    wait_error = read_error = retry_error = 0; retry_advance = 0;
}
int main() {
    tu_device device = {7, 0}; unsigned cases = 0, failures = 0;
#define EXPECT(name, condition) do { cases++; if (!(condition)) { failures++; \
    printf("FAIL %s waits=%u polls=%u forbidden_zero_waits=%u\n", name, waits, polls, zero_waits); } } while (0)
    reset();
    EXPECT("zero-pending-is-poll", wait_timestamp_safe(&device, 9, 11, 0) == VK_TIMEOUT && waits == 0 && polls == 1);
    reset();
    EXPECT("zero-complete-is-poll", wait_timestamp_safe(&device, 9, 10, 0) == VK_SUCCESS && waits == 0 && polls == 1);
    reset(); retired = 3;
    EXPECT("timestamp-wrap-complete", wait_timestamp_safe(&device, 9, 0xfffffff0u, 0) == VK_SUCCESS && waits == 0);
    reset(); retired = 0xfffffff0u;
    EXPECT("timestamp-wrap-pending", wait_timestamp_safe(&device, 9, 3, 0) == VK_TIMEOUT && waits == 0);
    reset();
    EXPECT("expired-deadline-is-poll", wait_timestamp_safe(&device, 9, 11, now_ns - 1) == VK_TIMEOUT && waits == 0);
    reset();
    EXPECT("finite-wait", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_SUCCESS && waits == 1 && last_timeout == 2);
    reset(); retry_error = EINTR; retry_advance = 3000000;
    EXPECT("interrupted-deadline-pending", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_TIMEOUT && waits == 1 && polls == 1);
    reset(); retry_error = EAGAIN; retry_advance = 3000000;
    EXPECT("retry-deadline-complete", wait_timestamp_safe(&device, 9, 10, now_ns + 2000000) == VK_SUCCESS && waits == 1 && polls == 1);
    reset(); retry_error = EINTR; retry_advance = 1000000;
    EXPECT("retry-adjusts-timeout", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_SUCCESS && waits == 2 && last_timeout == 1);
    reset(); wait_error = ETIMEDOUT;
    EXPECT("kernel-timeout", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_TIMEOUT);
    reset(); wait_error = EPROTO;
    EXPECT("device-fault-propagated", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_ERROR_DEVICE_LOST);
    reset(); wait_error = ENOENT;
    EXPECT("detached-context-propagated", wait_timestamp_safe(&device, 9, 11, now_ns + 2000000) == VK_ERROR_DEVICE_LOST);
    reset(); read_error = EIO;
    EXPECT("poll-error-not-success", wait_timestamp_safe(&device, 9, 11, 0) == VK_ERROR_UNKNOWN && waits == 0 && polls == 1);
    reset();
    EXPECT("infinite-wait-kept", wait_timestamp_safe(&device, 9, 11, UINT64_MAX) == VK_SUCCESS && waits == 1 && last_timeout == UINT32_MAX);
    printf("cases=%u failures=%u\n", cases, failures);
    return failures ? 1 : 0;
}
'''


def functions(source):
    parts = []
    for name in ("timestamp_cmp", "get_relative_ms", "kgsl_timestamp_error", "kgsl_poll_timestamp", "wait_timestamp_safe"):
        match = re.search(r"^static(?: inline)?\s+[\w *]+\s+" + name + r"\([^)]*\)\s*\{.*?^}", source, re.MULTILINE | re.DOTALL)
        if match is None:
            if name == "kgsl_poll_timestamp":  # Absent in the pre-fix source.
                continue
            raise ValueError("Missing source function: " + name)
        parts.append(match.group(0))
    return "\n\n".join(parts)


def main():
    before, after, directory = map(Path, sys.argv[1:])
    directory.mkdir(parents=True, exist_ok=True)
    results = []
    for label, path in (("before", before), ("after", after)):
        source = path.read_text()
        generated = directory / (label + "-wait.cc")
        # Preserve the upstream copyright/license header alongside extracted functions.
        header = source[:source.index("#include")]
        generated.write_text(header + HARNESS + functions(source) + CASES)
        binary = directory / (label + "-wait")
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + ["-std=c++17", "-Wall", "-Wextra", "-Werror",
                       "-Wno-unused-function", "-Wno-c++11-narrowing", str(generated), "-o", str(binary)], check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
        (directory / (label + ".log")).write_text(result.stdout + result.stderr)
        results.append({"source": str(path), "phase": label, "exit_code": result.returncode, "output": result.stdout})
    (directory / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))
    assert results[0]["exit_code"] == 1 and "FAIL zero-pending-is-poll" in results[0]["output"], "Old source did not reproduce the regression"
    assert results[1]["exit_code"] == 0 and "cases=14 failures=0" in results[1]["output"], "Fixed source did not pass"


if __name__ == "__main__":
    main()
