#!/usr/bin/env python3
"""Compile the compositor's actual fence functions against a controlled OS/API boundary.

Usage: python3 tools/tests/wayland-fences.py SOURCE_DIR OUTPUT_DIR
SOURCE_DIR contains ahb_swapchain.c, sc_layer.c and vk_present.c. No GPU, game,
Wayland server or SurfaceFlinger is simulated as a successful integration test.
CC selects the compiler; RUNNER optionally prefixes execution (e.g. an ADB runner).
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys


PREAMBLE = r'''
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>

typedef struct { int unused; } AHardwareBuffer;
struct surface { int unused; };
struct wl_client { int unused; };
struct wl_resource { int unused; };
struct wl_event_source { int unused; };
struct wl_event_loop { int unused; };
struct wl_list { struct wl_list *prev, *next; };
struct wl_listener { struct wl_list link; };
struct wnc_color { int unused; };
struct dmabuf_buffer { int fd; void *ahb_state; };
struct vkp_image { int unused; };
enum { WL_EVENT_READABLE = 1, WL_EVENT_HANGUP = 4, WL_EVENT_ERROR = 8 };
enum { VK_SUCCESS = 0, VK_NULL_HANDLE = 0, VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR = 1,
       VK_SEMAPHORE_IMPORT_TEMPORARY_BIT = 1, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT = 1 };
typedef struct { int sType, semaphore, flags, handleType, fd; } VkImportSemaphoreFdInfoKHR;
struct wnc_dma_buf_sync_file { uint32_t flags; int32_t fd; };
#define WNC_DMA_BUF_SYNC_READ 1u
#define WNC_DMA_BUF_IOCTL_EXPORT_SYNC_FILE _IOWR('b', 2, struct wnc_dma_buf_sync_file)
#define WNC_DMA_BUF_IOCTL_IMPORT_SYNC_FILE _IOW('b', 3, struct wnc_dma_buf_sync_file)
#define POOL_MAX 5

static struct wl_event_loop *g_loop;
static struct surface *g_announced;
static unsigned g_stat_zero_copy;
static int64_t g_last_zero_copy_ns;
static int g_export_failed_logged, g_import_failed_logged;
static struct { unsigned gpu_release_waits; } g_perf;
static int g_dev, g_wait_sem = 1;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static struct wl_event_source event_source;
static struct ahb_buf *test_ab;
static int polls, poll_timeout, poll_result, poll_error, poll_events, interrupt_once;
static int closes, closed_fd, presents, present_result, acquire_seen;
static int export_ok, import_ok, exports, imports, gpu_capable, gpu_import_ok;
static int watches, watch_fails, removes, releases, frees, fatal_errors, memory_errors;
static int alloc_fails, allocations, destroyed_images, released_ahbs;
static int duplicates, duplicate_fails, imported_fd_seen;

static int test_poll(struct pollfd *p, nfds_t n, int timeout) {
    if (n != 1) abort();
    polls++; poll_timeout = timeout;
    if (interrupt_once) { interrupt_once = 0; errno = EINTR; return -1; }
    p->revents = (short)poll_events;
    errno = poll_error;
    return poll_result;
}
static int test_close(int fd) { closes++; closed_fd = fd; return 0; }
static int test_fcntl(int fd, int cmd, int minimum) {
    (void)fd;
    if (cmd != F_DUPFD_CLOEXEC || minimum != 0) abort();
    duplicates++; if (duplicate_fails) { errno = EMFILE; return -1; } return 81;
}
static int test_ioctl(int fd, unsigned long request, struct wnc_dma_buf_sync_file *arg) {
    (void)fd;
    if (request == WNC_DMA_BUF_IOCTL_EXPORT_SYNC_FILE) {
        exports++; if (export_ok) { arg->fd = 71; return 0; }
    } else if (request == WNC_DMA_BUF_IOCTL_IMPORT_SYNC_FILE) {
        imports++; if (import_ok) return 0;
    } else abort();
    errno = ENOTTY; return -1;
}
static int64_t now_ns(void) { return 1234000000; }
static void wnc_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; }
static int wnc_dmabuf_fd(const struct dmabuf_buffer *b) { return b->fd; }
static void **wnc_dmabuf_ahb_slot(struct dmabuf_buffer *b) { return &b->ahb_state; }
static const struct wnc_color *wnc_surface_color(const struct surface *s) { (void)s; return NULL; }
static void wnc_surface_describe(const struct surface *s, char *out, size_t n) { (void)s; snprintf(out,n,"test"); }
static void wnc_release_buffer(struct surface *s, struct wl_resource *r, int p, int64_t t) {
    (void)s; (void)r; (void)p; (void)t; releases++;
}
static struct wl_event_source *wl_event_loop_add_fd(struct wl_event_loop *loop, int fd, uint32_t mask,
                 int (*callback)(int, uint32_t, void *), void *data) {
    (void)loop; (void)fd; (void)mask; (void)callback; (void)data;
    watches++; return watch_fails ? NULL : &event_source;
}
static int wl_event_source_remove(struct wl_event_source *s) { (void)s; removes++; return 0; }
static void wl_client_post_no_memory(struct wl_client *c) { (void)c; memory_errors++; }
static void wl_client_post_implementation_error(struct wl_client *c, const char *fmt, ...) {
    (void)c; (void)fmt; fatal_errors++;
}
static void vkp_image_destroy(struct vkp_image *img) { (void)img; destroyed_images++; }
static void AHardwareBuffer_release(AHardwareBuffer *ahb) { (void)ahb; released_ahbs++; }
static int vkp_can_wait_sync_fd(void) { return gpu_capable; }
static int test_gpu_import(int device, const VkImportSemaphoreFdInfoKHR *info) {
    (void)device; imported_fd_seen = info->fd; return gpu_import_ok ? VK_SUCCESS : -1;
}
static int (*g_import_sem_fd)(int, const VkImportSemaphoreFdInfoKHR *) = test_gpu_import;
static int sc_layer_present_ahb(AHardwareBuffer *a, int w, int h, int acquire, void *token,
          int sw, int sh, const struct wnc_color *color, uint32_t fmt) {
    (void)a; (void)w; (void)h; (void)token; (void)sw; (void)sh; (void)color; (void)fmt;
    presents++; acquire_seen = acquire;
    if (acquire >= 0) test_close(acquire); /* SurfaceControl boundary consumes it, also on failure. */
    return present_result;
}
#define poll test_poll
#define close test_close
#define ioctl test_ioctl
#define fcntl test_fcntl
'''

BETWEEN = r'''
struct layer { int pool_n, cur_slot; struct slot slots[POOL_MAX]; };
static struct ahb_buf *find_buf(uint64_t id) { return test_ab && test_ab->id == id ? test_ab : NULL; }
static void buf_free(struct ahb_buf *ab) { (void)ab; frees++; }
static int alloc_slot(struct layer *l, struct slot *s, int w, int h, uint32_t fmt) {
    (void)l; allocations++;
    if (alloc_fails) return -1;
    s->ahb = (AHardwareBuffer *)(uintptr_t)1; s->img = (struct vkp_image *)(uintptr_t)1;
    s->w = w; s->h = h; s->fmt = fmt; return 0;
}
'''

CASES = r'''
static struct ahb_buf ab;
static struct dmabuf_buffer buffer;
static struct surface surface;
static struct wl_resource resource;
static struct wl_client client;
static unsigned cases, failures;
static void reset(void) {
    polls = closes = presents = exports = imports = watches = removes = releases = frees = 0;
    fatal_errors = memory_errors = allocations = destroyed_images = released_ahbs = 0;
    poll_timeout = -1; poll_result = poll_error = poll_events = interrupt_once = 0;
    present_result = export_ok = import_ok = gpu_capable = gpu_import_ok = watch_fails = alloc_fails = 0;
    acquire_seen = closed_fd = -1;
    duplicates = duplicate_fails = 0;
    imported_fd_seen = -1;
    g_export_failed_logged = g_import_failed_logged = 0;
    g_stat_zero_copy = 0; g_last_zero_copy_ns = 0; g_announced = NULL;
    g_perf.gpu_release_waits = 0;
    memset(&ab, 0, sizeof(ab));
    ab.id = 3; ab.b = &buffer; ab.ahb = (AHardwareBuffer *)(uintptr_t)1;
    ab.resource = &resource; ab.client = &client; ab.fence_fd = -1;
    ab.w = 1280; ab.h = 720; buffer.fd = 42; buffer.ahb_state = &ab; test_ab = &ab;
}
#define EXPECT(name, condition) do { cases++; if (!(condition)) { failures++; \
    printf("FAIL %s polls=%d closes=%d presents=%d releases=%d watches=%d fatal=%d oom=%d\n", \
           name, polls, closes, presents, releases, watches, fatal_errors, memory_errors); } } while (0)
static void ready(void) { poll_result = 1; poll_events = POLLIN; }
static int call_wait(int *fd) {
    return take_wait_fd(*fd);
}
int main(void) {
    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) return 2;
    EXPECT("os-poll-empty-not-ready", !system_fence_signalled(pipe_fds[0]));
    char byte = 1;
    if (write(pipe_fds[1],&byte,1) != 1) return 2;
    EXPECT("os-poll-readable", system_fence_signalled(pipe_fds[0]));
    system_close(pipe_fds[1]);
    EXPECT("os-poll-hangup-not-ready", !system_fence_signalled(pipe_fds[0]));
    system_close(pipe_fds[0]);
    EXPECT("os-poll-invalid-not-ready", !system_fence_signalled(pipe_fds[0]));
    reset(); export_ok = 1;
    EXPECT("acquire-export-no-cpu-wait", ahb_swapchain_present(&buffer,&surface,1280,720) == 0 &&
           presents == 1 && acquire_seen == 71 && polls == 0 && ab.on_layer);
    reset(); ready();
    EXPECT("acquire-ready-fallback", ahb_swapchain_present(&buffer,&surface,1280,720) == 0 && presents == 1);
    reset();
    EXPECT("acquire-timeout-not-presented", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 && presents == 0);
    reset(); poll_result = -1; poll_error = EIO;
    EXPECT("acquire-poll-error-not-presented", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 && presents == 0);
    const int bad[] = {POLLERR, POLLHUP, POLLNVAL, POLLIN|POLLERR};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);i++) {
        reset(); poll_result = 1; poll_events = bad[i];
        EXPECT("acquire-bad-event-not-presented", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 && presents == 0);
    }
    reset(); buffer.fd = -1;
    EXPECT("acquire-no-dmabuf-not-presented", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 && presents == 0);
    reset(); ready(); interrupt_once = 1;
    EXPECT("acquire-interrupt-retried", ahb_swapchain_present(&buffer,&surface,1280,720) == 0 && polls == 2);
    reset(); export_ok = 1; present_result = -1;
    EXPECT("acquire-layer-refusal-owns-no-buffer", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 &&
           !ab.on_layer && closes == 1);
    reset(); export_ok = 1; ab.fence_src = &event_source; ab.fence_fd = 80;
    EXPECT("acquire-held-release-not-represented", ahb_swapchain_present(&buffer,&surface,1280,720) == -1 && presents == 0);

    reset(); ab.on_layer = ab.release_pending = 1; import_ok = 1;
    handle_released(ab.id,80);
    EXPECT("release-import-no-cpu-wait", releases == 1 && closes == 1 && closed_fd == 80 && polls == 0);
    reset(); ab.on_layer = ab.release_pending = 1; ready();
    handle_released(ab.id,80);
    EXPECT("release-ready-fallback", releases == 1 && closes == 1 && watches == 0);
    reset(); ab.on_layer = ab.release_pending = 1;
    handle_released(ab.id,80);
    EXPECT("release-pending-held", releases == 0 && ab.fence_src && ab.fence_fd == 80 && closes == 0);
    ready(); on_fence_readable(80,WL_EVENT_READABLE,&ab);
    EXPECT("release-only-after-readable", releases == 1 && !ab.fence_src && ab.fence_fd == -1 && closes == 1);
    reset(); ab.on_layer = ab.release_pending = 1; watch_fails = 1;
    handle_released(ab.id,80);
    EXPECT("release-watch-allocation-fails-closed", releases == 0 && memory_errors == 1 && closes == 1);
    reset(); ab.on_layer = ab.release_pending = 1; poll_result = -1; poll_error = EBADF;
    handle_released(ab.id,80);
    EXPECT("release-poll-error-fails-closed", releases == 0 && fatal_errors == 1 && closes == 1);
    reset(); ab.release_pending = 1; ab.fence_src = &event_source; ab.fence_fd = 80;
    poll_result = 1; poll_events = POLLERR;
    on_fence_readable(80,WL_EVENT_ERROR,&ab);
    EXPECT("release-error-callback-fails-closed", releases == 0 && fatal_errors == 1 && closes == 1 && removes == 1);
    reset(); ab.release_pending = 1; ab.fence_src = &event_source; ab.fence_fd = 80;
    on_fence_readable(80,WL_EVENT_READABLE,&ab);
    EXPECT("release-spurious-callback-held", releases == 0 && removes == 0 && closes == 0);
    reset(); ab.fence_src = &event_source; ab.fence_fd = 80;
    EXPECT("release-defers-while-fence-pending", ahb_swapchain_defer_release(&buffer,&resource,&surface,1) == 1 && ab.release_pending);
    reset(); ab.resource = NULL; ab.fence_src = &event_source; ab.fence_fd = 80;
    maybe_free(&ab);
    EXPECT("destroyed-resource-held-until-fence", frees == 0);
    ready(); on_fence_readable(80,WL_EVENT_READABLE,&ab);
    EXPECT("destroyed-resource-freed-after-fence", frees == 1 && releases == 0 && closes == 1);
    reset(); ab.resource = NULL; ab.fence_src = &event_source; ab.fence_fd = 80;
    poll_result = 1; poll_events = POLLERR;
    on_fence_readable(80,WL_EVENT_ERROR,&ab);
    EXPECT("destroyed-client-not-used-on-fence-error", frees == 1 && fatal_errors == 0 && releases == 0);
    reset(); handle_released(999,80);
    EXPECT("unknown-release-closes-fence", closes == 1 && releases == 0);

    reset(); ready(); EXPECT("pool-signalled", fence_signalled(80));
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);i++) {
        reset(); poll_result = 1; poll_events = bad[i];
        EXPECT("pool-bad-event-not-signalled", !fence_signalled(80));
    }
    reset();
    struct layer layer = {.pool_n=1,.cur_slot=-1};
    layer.slots[0] = (struct slot){.ahb=(AHardwareBuffer *)(uintptr_t)1,
                       .img=(struct vkp_image *)(uintptr_t)1,.w=1280,.h=720,.fmt=1,.release_fd=80};
    int fd = -1;
    EXPECT("pool-timeout-retains-fence", take_free_slot(&layer,1280,720,1,&fd) == -1 &&
           layer.slots[0].release_fd == 80 && fd == -1 && closes == 0);
    reset(); layer.slots[0].release_fd = 80; poll_result = -1; poll_error = EIO;
    EXPECT("pool-error-retains-fence", take_free_slot(&layer,1280,720,1,&fd) == -1 &&
           layer.slots[0].release_fd == 80 && fd == -1 && closes == 0);
    reset(); layer.slots[0].release_fd = 80; ready();
    EXPECT("pool-ready-consumes-fence", take_free_slot(&layer,1280,720,1,&fd) == 0 &&
           layer.slots[0].release_fd == -1 && fd == -1 && closes == 1);
    reset(); layer.slots[0].release_fd = 80; gpu_capable = 1;
    EXPECT("pool-gpu-wait-passes-fence", take_free_slot(&layer,1280,720,1,&fd) == 0 && fd == 80 && closes == 0);
    reset(); layer.slots[0].release_fd = -1; layer.slots[0].busy = 1;
    EXPECT("pool-busy-not-reused", take_free_slot(&layer,1280,720,1,&fd) == -1 && fd == -1);
    layer.slots[0].busy = 0;

    reset(); fd = -1;
    EXPECT("copy-no-fence", call_wait(&fd) == 0 && closes == 0);
    reset(); fd = 80; gpu_capable = gpu_import_ok = 1;
    EXPECT("copy-gpu-import-borrows-fence", call_wait(&fd) == 1 && closes == 0 &&
           g_perf.gpu_release_waits == 1 && duplicates == 1 && imported_fd_seen == 81);
    reset(); fd = 80; ready(); gpu_capable = 1;
    EXPECT("copy-import-failure-ready-fallback", call_wait(&fd) == 0 && closes == 1 && closed_fd == 81);
    reset(); fd = 80;
    EXPECT("copy-timeout-retains-fence", call_wait(&fd) == -1 && fd == 80 && closes == 0);
    reset(); fd = 80; poll_result = -1; poll_error = EIO;
    EXPECT("copy-error-retains-fence", call_wait(&fd) == -1 && fd == 80 && closes == 0);
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);i++) {
        reset(); fd = 80; poll_result = 1; poll_events = bad[i];
        EXPECT("copy-bad-event-retains-fence", call_wait(&fd) == -1 && fd == 80 && closes == 0);
    }
#if BORROWED_WAIT_FD
    reset(); fd = 80;
    layer.slots[0].release_fd = -1;
    EXPECT("copy-pending-not-writable", call_wait(&fd) == -1);
    finish_copy_wait(&layer.slots[0],fd,0);
    EXPECT("copy-failure-returned-to-slot", layer.slots[0].release_fd == 80 && closes == 0);
    ready(); fd = -1;
    EXPECT("copy-retry-rechecks-same-fence", take_free_slot(&layer,1280,720,1,&fd) == 0 && closes == 1);
    reset(); fd = 80; gpu_capable = gpu_import_ok = 1;
    call_wait(&fd); finish_copy_wait(&layer.slots[0],fd,0);
    EXPECT("copy-failed-submit-retains-original", layer.slots[0].release_fd == 80 && closes == 0 && duplicates == 1);
    reset(); fd = 80; gpu_capable = gpu_import_ok = 1;
    layer.slots[0].release_fd = -1;
    call_wait(&fd); finish_copy_wait(&layer.slots[0],fd,1);
    EXPECT("copy-success-closes-original", closes == 1 && closed_fd == 80 && layer.slots[0].release_fd == -1);
    reset(); fd = 80; gpu_capable = gpu_import_ok = duplicate_fails = 1;
    EXPECT("copy-duplicate-failure-pending", call_wait(&fd) == -1 && closes == 0);
    reset(); fd = 80; gpu_capable = gpu_import_ok = duplicate_fails = 1; ready();
    EXPECT("copy-duplicate-failure-ready", call_wait(&fd) == 0 && closes == 0);
#endif
    printf("cases=%u failures=%u\n", cases, failures);
    return failures ? 1 : 0;
}
'''


def function(source, name):
    match = re.search(r"^(?:static )?[\w *]+\b" + name +
                      r"\([^;]*?\)\s*\{.*?^}", source, re.MULTILINE | re.DOTALL)
    if not match:
        raise ValueError("Missing actual source function: " + name)
    return match.group(0)


def structure(source, name):
    return re.search(r"^struct " + name + r" \{.*?^};", source,
                     re.MULTILINE | re.DOTALL).group(0)


def main():
    source_dir, output = map(Path, sys.argv[1:])
    output.mkdir(parents=True, exist_ok=True)
    sources = {n: (source_dir / n).read_text() for n in
               ("ahb_swapchain.c", "sc_layer.c", "vk_present.c")}
    ahb, layer, vk = (sources[n] for n in sources)
    borrowed = "finish_copy_wait" in layer
    # A second copy calls the real OS poll boundary for pipe readiness/error checks.
    # It does not stand in for a dma-buf or sync_file rendering test.
    preamble, mock_macros = PREAMBLE.split("#define poll test_poll", 1)
    real_poll = ("\nstatic int system_close(int fd) { return close(fd); }\n"
                 "#define fence_signalled system_fence_signalled\n" +
                 function(layer, "fence_signalled") + "\n#undef fence_signalled\n")
    generated = (preamble + real_poll + "#define poll test_poll" + mock_macros +
                 structure(ahb, "ahb_buf") + structure(layer, "slot") + BETWEEN)
    for name in ("maybe_free", "send_deferred_release", "on_fence_readable", "handle_released",
                 "ahb_swapchain_present", "ahb_swapchain_defer_release"):
        generated += "\n" + function(ahb, name)
    for name in ("fence_signalled", "take_free_slot"):
        generated += "\n" + function(layer, name)
    if borrowed:
        generated += "\n" + function(layer, "finish_copy_wait")
    generated += "\n" + function(vk, "take_wait_fd") + CASES
    path = output / "wayland-fences.c"
    path.write_text(generated)
    binary = output / "wayland-fences"
    command = shlex.split(os.environ.get("CC", "cc")) + [
        "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function",
        "-Wno-unused-parameter", "-DBORROWED_WAIT_FD=" + str(int(borrowed)),
        str(path), "-pthread", "-o", str(binary)]
    subprocess.run(command, check=True)
    result = subprocess.run(shlex.split(os.environ.get("RUNNER", "")) + [str(binary)],
                            capture_output=True, text=True, timeout=30)
    record = {"source_directory": str(source_dir.resolve()), "compiler_command": command,
              "source_hashes": {n: hashlib.sha256(s.encode()).hexdigest() for n,s in sources.items()},
              "generated_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "exit_code": result.returncode, "output": result.stdout, "stderr": result.stderr,
              "boundary": "Actual extracted functions; mocked poll/ioctl/Vulkan/Wayland/SurfaceControl boundaries. No rendering."}
    (output / "results.json").write_text(json.dumps(record, indent=2) + "\n")
    (output / "results.log").write_text(result.stdout + result.stderr)
    print(json.dumps(record, indent=2))
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
