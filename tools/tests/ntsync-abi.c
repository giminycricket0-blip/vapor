/* Native Android C-ABI checks for a separately built ntsync-android library.
 * Build with the pinned donor's include/ directory. No Wine or account required.
 * Usage: ntsync-abi /absolute/lib.so --shared-exec /absolute/child-tmp
 *        ntsync-abi /absolute/lib.so --expect-unavailable
 * Set TMPDIR and NTSYNC_SHM to dedicated, disposable test directories. */
#define _GNU_SOURCE
#include "ntsync_user.h"
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static __typeof__(&ntsync_init) init_fn;
static __typeof__(&ntsync_create_sem) create_fn;
static __typeof__(&ntsync_sem_release) release_fn;
static __typeof__(&ntsync_wait_any) wait_fn;
static __typeof__(&ntsync_close) close_fn;

static int load(const char *path) {
    void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 0; }
#define LOAD(pointer, name) do { pointer = (__typeof__(pointer))dlsym(lib, #name); \
    if (!pointer) { fprintf(stderr, "missing symbol: %s\n", #name); return 0; } } while (0)
    LOAD(init_fn, ntsync_init);
    LOAD(create_fn, ntsync_create_sem);
    LOAD(release_fn, ntsync_sem_release);
    LOAD(wait_fn, ntsync_wait_any);
    LOAD(close_fn, ntsync_close);
#undef LOAD
    return 1;
}

static void mapping(const char *label) {
    FILE *maps = fopen("/proc/self/maps", "r");
    char line[2048];
    if (!maps) return;
    while (fgets(line, sizeof(line), maps)) {
        if (strstr(line, ".shm") && strstr(line, "vapor-ntsync-check"))
            printf("%s_map=%s", label, line);
    }
    fclose(maps);
    printf("%s_tmp=%s\n", label, getenv("TMPDIR") ? getenv("TMPDIR") : "");
    fflush(stdout);
}

static int child_wait(uint32_t handle, int ready_fd) {
    if (init_fn(NULL) != 0) return 20;
    mapping("child");
    if (write(ready_fd, "R", 1) != 1) return 21;
    close(ready_fd);
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 22;
    struct ntsync_wait_args args = {0};
    args.timeout = (uint64_t)now.tv_sec * 1000000000 + now.tv_nsec + 2000000000;
    args.objs = (uintptr_t)&handle;
    args.count = 1;
    args.owner = (uint32_t)gettid();
    int result = wait_fn(&args);
    printf("child_wait=%d index=%u\n", result, args.index);
    return result == 0 && args.index == 0 ? 0 : 23;
}

int main(int argc, char **argv) {
    if (argc < 3 || !load(argv[1])) return 90;
    if (!strcmp(argv[2], "--child") && argc == 5) {
        return child_wait((uint32_t)strtoul(argv[3], NULL, 10), atoi(argv[4]));
    }
    if (!strcmp(argv[2], "--expect-unavailable") && argc == 3) {
        int first = init_fn(NULL), repeated = init_fn(NULL);
        uint32_t handle = 0xaaaaaaaa;
        const struct ntsync_sem_args sem = {0, 1};
        int created = create_fn(&handle, &sem);
        int ok = first == -ENOSYS && repeated == -ENOSYS && created == -ENOSYS && handle == 0xaaaaaaaa;
        printf("init=%d repeated=%d create_sem=%d handle_untouched=%s\n",
               first, repeated, created, handle == 0xaaaaaaaa ? "yes" : "no");
        printf("result=%s unsupported-backend-refused\n", ok ? "PASS" : "FAIL");
        return !ok;
    }
    if (strcmp(argv[2], "--shared-exec") || argc != 4) return 91;
    int initialized = init_fn(NULL);
    printf("init=%d\n", initialized);
    if (initialized != 0) return 1;
    uint32_t handle = 0;
    const struct ntsync_sem_args sem = {0, 1};
    if (create_fn(&handle, &sem) != 0 || !handle) return 2;
    mapping("parent");
    int pipe_fd[2];
    if (pipe(pipe_fd) != 0) { close_fn(handle); return 3; }
    char self[1024], value[32], ready_fd[32];
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0 || length >= (ssize_t)sizeof(self) - 1) return 4;
    self[length] = '\0';
    snprintf(value, sizeof(value), "%u", handle);
    snprintf(ready_fd, sizeof(ready_fd), "%d", pipe_fd[1]);
    pid_t child = fork();
    if (child < 0) return 5;
    if (!child) {
        close(pipe_fd[0]);
        if (setenv("TMPDIR", argv[3], 1) != 0) _exit(6);
        execl(self, self, argv[1], "--child", value, ready_fd, (char *)NULL);
        _exit(7);
    }
    close(pipe_fd[1]);
    struct pollfd ready = {.fd = pipe_fd[0], .events = POLLIN};
    char byte = 0;
    int ok = poll(&ready, 1, 3000) == 1 && read(pipe_fd[0], &byte, 1) == 1 && byte == 'R';
    close(pipe_fd[0]);
    uint32_t count = 1;
    if (ok) ok = release_fn(handle, &count) == 0 && count == 0;
    if (!ok) kill(child, SIGKILL);
    int status = 0;
    if (waitpid(child, &status, 0) != child) ok = 0;
    ok = ok && WIFEXITED(status) && WEXITSTATUS(status) == 0;
    if (close_fn(handle) != 0) ok = 0;
    printf("result=%s independent-exec-shared-region\n", ok ? "PASS" : "FAIL");
    return !ok;
}
