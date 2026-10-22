// libvapor_exec.so - LD_PRELOAD exec shim for target SDK > 28 (docs/TARGET-SDK.md).
//
// From target SDK 29 Android denies execve() of files in app-writable storage (W^X).
// Loading code from there is still allowed, so a denied program can still run as
//   /system/bin/linker64 <program> args...   (dynamic bionic executables), or
//   <interpreter> [arg] <script> args...     (#! scripts).
// This shim tries the real exec first and reroutes only when it fails with EACCES, so
// behaviour is unchanged wherever direct exec works (target 28 today). Static
// executables cannot use the linker route and keep the original error.
//
// A rerouted program's /proc/self/exe is the linker, and programs such as wineserver use it
// to find their data files. The shim passes the real path to the child in VAPOR_EXEC_SELF and,
// inside that child, answers readlink/realpath("/proc/self/exe") with it. The variable is only
// adopted when the real executable is the system linker, and is removed so grandchildren that
// run directly do not inherit it.
#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#define VAPOR_EXPORT __attribute__((visibility("default")))
#define SYSTEM_LINKER "/system/bin/linker64"
#define MAX_DEPTH 4

extern char **environ;

static int raw_execve(const char *path, char *const argv[], char *const envp[]) {
    return (int) syscall(SYS_execve, path, argv, envp);
}

enum route { ROUTE_NONE, ROUTE_LINKER, ROUTE_SCRIPT };

// Decides how a denied program could still run. For scripts, fills interp/arg.
static enum route classify(const char *path, char *interp, size_t interp_len, char *arg, size_t arg_len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return ROUTE_NONE;
    unsigned char head[256];
    ssize_t n = pread(fd, head, sizeof(head) - 1, 0);
    enum route result = ROUTE_NONE;
    if (n >= 4 && head[0] == '#' && head[1] == '!') {
        head[n] = 0;
        char *line = (char *) head + 2, *end = strchr(line, '\n');
        if (end) *end = 0;
        while (*line == ' ' || *line == '\t') line++;
        char *space = strpbrk(line, " \t");
        if (space) {
            *space++ = 0;
            while (*space == ' ' || *space == '\t') space++;
            size_t len = strlen(space);
            while (len && (space[len - 1] == ' ' || space[len - 1] == '\t' || space[len - 1] == '\r')) space[--len] = 0;
        }
        if (*line && strlen(line) < interp_len) {
            strcpy(interp, line);
            arg[0] = 0;
            if (space && *space && strlen(space) < arg_len) strcpy(arg, space);
            result = ROUTE_SCRIPT;
        }
    } else if (n >= (ssize_t) sizeof(Elf64_Ehdr) && memcmp(head, ELFMAG, SELFMAG) == 0 && head[EI_CLASS] == ELFCLASS64) {
        Elf64_Ehdr eh;
        memcpy(&eh, head, sizeof(eh));
        if (eh.e_type == ET_DYN || eh.e_type == ET_EXEC) {
            for (int i = 0; i < eh.e_phnum && i < 64; i++) {
                Elf64_Phdr ph;
                if (pread(fd, &ph, sizeof(ph), (off_t) (eh.e_phoff + (Elf64_Off) i * eh.e_phentsize)) != (ssize_t) sizeof(ph)) break;
                if (ph.p_type != PT_INTERP || ph.p_filesz == 0 || ph.p_filesz >= PATH_MAX) continue;
                char want[PATH_MAX];
                if (pread(fd, want, ph.p_filesz, (off_t) ph.p_offset) == (ssize_t) ph.p_filesz) {
                    want[ph.p_filesz - 1] = 0;
                    if (strcmp(want, SYSTEM_LINKER) == 0) result = ROUTE_LINKER;
                }
                break;
            }
        }
    }
    close(fd);
    return result;
}

static size_t count(char *const argv[]) { size_t n = 0; while (argv && argv[n]) n++; return n; }

// linker64 only accepts an absolute program path. Relative paths are joined to the
// working directory without resolving symlinks, so the program keeps its own layout.
static const char *absolute(const char *path, char *buf, size_t len) {
    if (path[0] == '/') return path;
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) return path;
    const char *rest = (path[0] == '.' && path[1] == '/') ? path + 2 : path;
    if ((size_t) snprintf(buf, len, "%s/%s", cwd, rest) >= len) return path;
    return buf;
}

// Builds the rerouted command for a denied program. Returns 0 and a malloc'd argv
// (caller frees) or -1 if the program has no route.
static int reroute(const char *path, char *const argv[], const char **target, char ***out,
                   char *interp, size_t interp_len, char *arg, size_t arg_len) {
    enum route r = classify(path, interp, interp_len, arg, arg_len);
    if (r == ROUTE_NONE) return -1;
    size_t argc = count(argv), extra = (r == ROUTE_SCRIPT && arg[0]) ? 3 : 2;
    size_t abs_len = strlen(path) + PATH_MAX + 2;
    // One allocation: the argv array followed by the absolute program path.
    char **next = calloc(1, (argc + extra + 1) * sizeof(char *) + abs_len);
    if (!next) return -1;
    char *abs_buf = (char *) (next + argc + extra + 1);
    path = absolute(path, abs_buf, abs_len);
    size_t k = 0;
    if (r == ROUTE_LINKER) {
        *target = SYSTEM_LINKER;
        next[k++] = (char *) SYSTEM_LINKER;
    } else {
        *target = interp;
        next[k++] = interp;
        if (arg[0]) next[k++] = arg;
    }
    next[k++] = (char *) path;
    for (size_t i = 1; i < argc; i++) next[k++] = argv[i];
    next[k] = NULL;
    *out = next;
    return 0;
}

#define SELF_VAR "VAPOR_EXEC_SELF="

// Copy of envp with VAPOR_EXEC_SELF set to the program's absolute path (malloc'd; caller frees).
static char **with_self(char *const envp[], const char *program) {
    size_t n = 0, keep = 0;
    while (envp && envp[n]) n++;
    char **next = calloc(n + 2, sizeof(char *));
    char *entry = malloc(strlen(SELF_VAR) + strlen(program) + 1);
    if (!next || !entry) { free(next); free(entry); return NULL; }
    strcpy(entry, SELF_VAR); strcat(entry, program);
    for (size_t i = 0; i < n; i++)
        if (strncmp(envp[i], SELF_VAR, strlen(SELF_VAR)) != 0) next[keep++] = envp[i];
    next[keep++] = entry;
    next[keep] = NULL;
    return next;
}

static int exec_depth(const char *path, char *const argv[], char *const envp[], int depth) {
    raw_execve(path, argv, envp);
    if (errno != EACCES || depth >= MAX_DEPTH) return -1;
    int saved = errno;
    char interp[PATH_MAX], arg[PATH_MAX];
    const char *target = NULL;
    char **next = NULL;
    if (reroute(path, argv, &target, &next, interp, sizeof(interp), arg, sizeof(arg)) != 0) { errno = saved; return -1; }
    // A script's interpreter may itself live in app storage, so it goes through the same logic.
    char **env = envp;
    char **owned = NULL;
    if (strcmp(target, SYSTEM_LINKER) == 0 && (owned = with_self(envp, next[1]))) env = owned;
    exec_depth(target, next, env, depth + 1);
    int err = errno;
    if (owned) { free(owned[count(owned) - 1]); free(owned); }
    free(next);
    errno = err;
    return -1;
}

VAPOR_EXPORT int execve(const char *path, char *const argv[], char *const envp[]) {
    return exec_depth(path, argv, envp, 0);
}

VAPOR_EXPORT int execv(const char *path, char *const argv[]) {
    return exec_depth(path, argv, environ, 0);
}

VAPOR_EXPORT int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return exec_depth(file, argv, envp, 0);
    const char *paths = getenv("PATH");
    if (!paths || !*paths) paths = "/system/bin:/system/xbin";
    int seen_eacces = 0;
    const char *p = paths;
    while (1) {
        const char *colon = strchr(p, ':');
        size_t len = colon ? (size_t) (colon - p) : strlen(p);
        char candidate[PATH_MAX];
        if (len + strlen(file) + 2 <= sizeof(candidate)) {
            if (len == 0) snprintf(candidate, sizeof(candidate), "%s", file);
            else snprintf(candidate, sizeof(candidate), "%.*s/%s", (int) len, p, file);
            exec_depth(candidate, argv, envp, 0);
            if (errno == EACCES) seen_eacces = 1;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!colon) break;
        p = colon + 1;
    }
    errno = seen_eacces ? EACCES : ENOENT;
    return -1;
}

VAPOR_EXPORT int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

// posix_spawn reports a failed exec through its return value; retry once with the reroute.
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
#include <dlfcn.h>

static int spawn_with_reroute(const char *symbol, pid_t *pid, const char *path,
                              const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                              char *const argv[], char *const envp[]) {
    spawn_fn real = (spawn_fn) dlsym(RTLD_NEXT, symbol);
    if (!real) return ENOSYS;
    int rc = real(pid, path, actions, attr, argv, envp);
    if (rc != EACCES || !strchr(path, '/')) return rc;
    char interp[PATH_MAX], arg[PATH_MAX];
    const char *target = NULL;
    char **next = NULL;
    if (reroute(path, argv, &target, &next, interp, sizeof(interp), arg, sizeof(arg)) != 0) return rc;
    spawn_fn direct = (spawn_fn) dlsym(RTLD_NEXT, "posix_spawn");
    char **owned = strcmp(target, SYSTEM_LINKER) == 0 ? with_self(envp ? envp : environ, next[1]) : NULL;
    int retry = direct ? direct(pid, target, actions, attr, next, owned ? owned : envp) : rc;
    if (owned) { free(owned[count(owned) - 1]); free(owned); }
    free(next);
    return retry;
}

VAPOR_EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                             const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return spawn_with_reroute("posix_spawn", pid, path, actions, attr, argv, envp);
}

VAPOR_EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                              const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return spawn_with_reroute("posix_spawnp", pid, file, actions, attr, argv, envp);
}

// ---- /proc/self/exe for rerouted programs ----------------------------------------------

static char self_exe[PATH_MAX];

__attribute__((constructor)) static void adopt_self_exe(void) {
    const char *self = getenv("VAPOR_EXEC_SELF");
    if (!self || self[0] != '/') return;
    char real[PATH_MAX];
    ssize_t n = (ssize_t) syscall(SYS_readlinkat, AT_FDCWD, "/proc/self/exe", real, sizeof(real) - 1);
    if (n > 0) {
        real[n] = 0;
        // /system/bin/linker64 is a symlink into the runtime APEX, so the kernel reports the APEX path.
        int is_linker = strcmp(real, SYSTEM_LINKER) == 0 || strcmp(real, "/apex/com.android.runtime/bin/linker64") == 0;
        if (is_linker && strlen(self) < sizeof(self_exe)) strcpy(self_exe, self);
    }
    unsetenv("VAPOR_EXEC_SELF");
}

static int is_self_exe(const char *path) {
    return self_exe[0] && path && (strcmp(path, "/proc/self/exe") == 0 ||
        (strncmp(path, "/proc/", 6) == 0 && strcmp(strchr(path + 6, '/') ? strchr(path + 6, '/') : "", "/exe") == 0 &&
         atoi(path + 6) == getpid()));
}

static ssize_t copy_self(char *buf, size_t size) {
    size_t len = strlen(self_exe);
    if (len > size) len = size;
    memcpy(buf, self_exe, len);
    return (ssize_t) len;
}

typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef char *(*realpath_fn)(const char *, char *);

// Private helpers: exported wrappers (including the fortified _chk entry points) call these,
// never each other, so no wrapper can resolve back to itself.
static ssize_t do_readlink(const char *path, char *buf, size_t size) {
    if (is_self_exe(path)) return copy_self(buf, size);
    static readlink_fn real;
    if (!real) real = (readlink_fn) dlsym(RTLD_NEXT, "readlink");
    return real ? real(path, buf, size) : -1;
}

static ssize_t do_readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    if (is_self_exe(path)) return copy_self(buf, size);
    static readlinkat_fn real;
    if (!real) real = (readlinkat_fn) dlsym(RTLD_NEXT, "readlinkat");
    return real ? real(dirfd, path, buf, size) : -1;
}

static char *do_realpath(const char *path, char *resolved) {
    static realpath_fn real;
    if (!real) real = (realpath_fn) dlsym(RTLD_NEXT, "realpath");
    if (!real) return NULL;
    // Resolve the real program path instead, so symlinks in it are handled normally.
    return real(is_self_exe(path) ? self_exe : path, resolved);
}

VAPOR_EXPORT ssize_t readlink(const char *path, char *buf, size_t size) { return do_readlink(path, buf, size); }
VAPOR_EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) { return do_readlinkat(dirfd, path, buf, size); }
VAPOR_EXPORT char *realpath(const char *path, char *resolved) { return do_realpath(path, resolved); }

// Fortified builds call the _chk variants directly.
VAPOR_EXPORT ssize_t __readlink_chk(const char *path, char *buf, size_t size, size_t buf_size) {
    (void) buf_size;
    return do_readlink(path, buf, size);
}

VAPOR_EXPORT ssize_t __readlinkat_chk(int dirfd, const char *path, char *buf, size_t size, size_t buf_size) {
    (void) buf_size;
    return do_readlinkat(dirfd, path, buf, size);
}

VAPOR_EXPORT char *__realpath_chk(const char *path, char *resolved, size_t resolved_len) {
    (void) resolved_len;
    return do_realpath(path, resolved);
}
