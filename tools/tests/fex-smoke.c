/* Local Wine/FEX execution check. No Steam, network, game files or graphics.
 * Build this same source as i686 and x86_64; see docs/BUILDING.md.
 * Writes <executable>.result beside itself, then exits with the failure count.
 * --sync also checks Windows synchronization and process/thread cleanup.
 * A pass checks this workload only, not game compatibility or performance. */
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static volatile LONG total;
static HANDLE start_event;
static volatile uint64_t seed = UINT64_C(0x123456789abcdef0);

static int mutex_child(int argc, char **argv) {
    if (argc != 4) return 70;
    HANDLE mutex = OpenMutexA(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, argv[2]);
    HANDLE ready = OpenEventA(EVENT_MODIFY_STATE, FALSE, argv[3]);
    if (!mutex || !ready || WaitForSingleObject(mutex, 3000) != WAIT_OBJECT_0) return 71;
    if (!SetEvent(ready)) return 72;
    if (!strcmp(argv[1], "--hold-owner")) Sleep(INFINITE);
    /* Exit with the mutex still owned, exercising Windows abandonment. */
    return 0;
}

static int abandoned_mutex(int force) {
    char exe[MAX_PATH], command[MAX_PATH + 256], name[80], event_name[80];
    DWORD length = GetModuleFileNameA(NULL, exe, sizeof(exe));
    if (!length || length >= sizeof(exe)) return 0;
    snprintf(name, sizeof(name), "Local\\VaporMutex-%lu-%d", GetCurrentProcessId(), force);
    snprintf(event_name, sizeof(event_name), "Local\\VaporReady-%lu-%d", GetCurrentProcessId(), force);
    HANDLE mutex = CreateMutexA(NULL, FALSE, name);
    HANDLE ready = CreateEventA(NULL, TRUE, FALSE, event_name);
    PROCESS_INFORMATION process = {0};
    STARTUPINFOA startup = {0};
    startup.cb = sizeof(startup);
    snprintf(command, sizeof(command), "\"%s\" %s \"%s\" \"%s\"", exe,
             force ? "--hold-owner" : "--mutex-owner", name, event_name);
    int ok = mutex && ready && CreateProcessA(exe, command, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &process);
    if (ok) {
        ok = WaitForSingleObject(ready, 5000) == WAIT_OBJECT_0;
        if (force && ok) ok = TerminateProcess(process.hProcess, 17);
        if (!ok) TerminateProcess(process.hProcess, 18);
        if (WaitForSingleObject(process.hProcess, 5000) != WAIT_OBJECT_0) {
            TerminateProcess(process.hProcess, 19);
            WaitForSingleObject(process.hProcess, 2000);
            ok = 0;
        }
        DWORD exit_code = STILL_ACTIVE;
        ok &= GetExitCodeProcess(process.hProcess, &exit_code) && exit_code == (force ? 17u : 0u);
        DWORD wait = WaitForSingleObject(mutex, 1000);
        ok &= wait == WAIT_ABANDONED;
        if (wait == WAIT_ABANDONED || wait == WAIT_OBJECT_0) ReleaseMutex(mutex);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
    }
    if (mutex) CloseHandle(mutex);
    if (ready) CloseHandle(ready);
    return ok;
}

struct SuspendCheck { HANDLE ready, gate; };
static DWORD WINAPI suspended_worker(void *data) {
    struct SuspendCheck *check = data;
    SetEvent(check->ready);
    return WaitForSingleObject(check->gate, 5000) == WAIT_OBJECT_0 ? 0 : 1;
}

static int check_suspend(void) {
    struct SuspendCheck check = {CreateEventA(NULL, TRUE, FALSE, NULL), CreateEventA(NULL, TRUE, FALSE, NULL)};
    HANDLE thread = check.ready && check.gate ? CreateThread(NULL, 0, suspended_worker, &check, 0, NULL) : NULL;
    int suspended = 0;
    int ok = thread && WaitForSingleObject(check.ready, 2000) == WAIT_OBJECT_0;
    if (ok) { suspended = SuspendThread(thread) != (DWORD)-1; ok = suspended; }
    if (check.gate) SetEvent(check.gate);
    if (ok) ok = WaitForSingleObject(thread, 100) == WAIT_TIMEOUT;
    if (suspended) ok &= ResumeThread(thread) == 1;
    DWORD status = 1;
    int joined = thread && WaitForSingleObject(thread, 5000) == WAIT_OBJECT_0;
    ok &= joined && GetExitCodeThread(thread, &status) && status == 0;
    /* A timed-out worker still uses stack data: terminate the fixture immediately. */
    if (thread && !joined) ExitProcess(73);
    if (thread) CloseHandle(thread);
    if (check.ready) CloseHandle(check.ready);
    if (check.gate) CloseHandle(check.gate);
    return ok;
}

static int check_sync(FILE *result) {
    int failures = 0;
#define RECORD(name, expression) do { int passed = !!(expression); \
    fprintf(result, "%s=%s\n", name, passed ? "PASS" : "FAIL"); fflush(result); failures += !passed; } while (0)
    HANDLE event = CreateEventA(NULL, FALSE, TRUE, NULL);
    RECORD("auto_reset", event && WaitForSingleObject(event, 0) == WAIT_OBJECT_0 &&
           WaitForSingleObject(event, 0) == WAIT_TIMEOUT);
    if (event) CloseHandle(event);
    event = CreateEventA(NULL, TRUE, TRUE, NULL);
    RECORD("manual_reset", event && WaitForSingleObject(event, 0) == WAIT_OBJECT_0 &&
           WaitForSingleObject(event, 0) == WAIT_OBJECT_0 && ResetEvent(event) &&
           WaitForSingleObject(event, 0) == WAIT_TIMEOUT);
    if (event) CloseHandle(event);
    HANDLE pair[2] = {CreateEventA(NULL, FALSE, FALSE, NULL), CreateEventA(NULL, FALSE, TRUE, NULL)};
    RECORD("wait_any", pair[0] && pair[1] &&
           WaitForMultipleObjects(2, pair, FALSE, 0) == WAIT_OBJECT_0 + 1 &&
           WaitForSingleObject(pair[1], 0) == WAIT_TIMEOUT);
    if (pair[0]) CloseHandle(pair[0]);
    if (pair[1]) CloseHandle(pair[1]);
    pair[0] = CreateSemaphoreA(NULL, 1, 1, NULL);
    pair[1] = CreateEventA(NULL, FALSE, FALSE, NULL);
    RECORD("wait_all_atomic", pair[0] && pair[1] &&
           WaitForMultipleObjects(2, pair, TRUE, 0) == WAIT_TIMEOUT &&
           WaitForSingleObject(pair[0], 0) == WAIT_OBJECT_0 &&
           ReleaseSemaphore(pair[0], 1, NULL) && SetEvent(pair[1]) &&
           WaitForMultipleObjects(2, pair, TRUE, 1000) == WAIT_OBJECT_0 &&
           WaitForSingleObject(pair[0], 0) == WAIT_TIMEOUT &&
           WaitForSingleObject(pair[1], 0) == WAIT_TIMEOUT);
    if (pair[0]) CloseHandle(pair[0]);
    if (pair[1]) CloseHandle(pair[1]);
    HANDLE mutex = CreateMutexA(NULL, TRUE, NULL);
    RECORD("mutex_recursion", mutex && WaitForSingleObject(mutex, 0) == WAIT_OBJECT_0 &&
           ReleaseMutex(mutex) && ReleaseMutex(mutex) &&
           WaitForSingleObject(mutex, 0) == WAIT_OBJECT_0 && ReleaseMutex(mutex));
    if (mutex) CloseHandle(mutex);
    RECORD("normal_process_abandon", abandoned_mutex(0));
    RECORD("forced_process_abandon", abandoned_mutex(1));
    RECORD("suspend_resume", check_suspend());
#undef RECORD
    return failures;
}

static DWORD WINAPI worker(void *unused) {
    (void)unused;
    if (WaitForSingleObject(start_event, 5000) != WAIT_OBJECT_0) return 1;
    for (int i = 0; i < 10000; ++i) InterlockedIncrement(&total);
    return 0;
}

int main(int argc, char **argv) {
    if (argc > 1 && (!strcmp(argv[1], "--mutex-owner") || !strcmp(argv[1], "--hold-owner")))
        return mutex_child(argc, argv);
    int sync = argc == 2 && !strcmp(argv[1], "--sync");
    if (argc > 1 && !sync) return 74;
    char path[MAX_PATH + 16];
    DWORD length = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (!length || length >= MAX_PATH) return 90;
    strcat(path, ".result");
    FILE *result = fopen(path, "w");
    if (!result) return 91;
    fprintf(result, "Vapor FEX smoke: %u-bit Windows guest\n", (unsigned)(sizeof(void *) * 8));
    fflush(result);

    int failures = 0;
    uint64_t value = seed;
    for (int i = 0; i < 1000; ++i) value = value * 6364136223846793005ULL + 1;
    int arithmetic_ok = value == UINT64_C(0x1973681da6a03468);
    fprintf(result, "integer=%s value=%016llx\n", arithmetic_ok ? "PASS" : "FAIL", (unsigned long long)value);
    failures += !arithmetic_ok;

    unsigned char source[8192], copied[8192];
    for (size_t i = 0; i < sizeof(source); ++i) source[i] = (unsigned char)(i * 37 + 11);
    memcpy(copied, source, sizeof(source));
    memmove(copied + 17, copied, 4096);
    int memory_ok = memcmp(copied + 17, source, 4096) == 0;
    fprintf(result, "memory=%s\n", memory_ok ? "PASS" : "FAIL");
    failures += !memory_ok;

    start_event = CreateEventA(NULL, TRUE, FALSE, NULL);
    HANDLE threads[2] = {NULL, NULL};
    int sync_ok = start_event != NULL;
    if (sync_ok) {
        for (int i = 0; i < 2; ++i) threads[i] = CreateThread(NULL, 0, worker, NULL, 0, NULL);
        SetEvent(start_event);
        sync_ok = threads[0] && threads[1] &&
            WaitForMultipleObjects(2, threads, TRUE, 10000) == WAIT_OBJECT_0;
        if (sync_ok) {
            for (int i = 0; i < 2; ++i) {
                DWORD status = 1;
                sync_ok &= GetExitCodeThread(threads[i], &status) && status == 0;
            }
            sync_ok &= total == 20000;
        }
    }
    fprintf(result, "threads=%s total=%ld\n", sync_ok ? "PASS" : "FAIL", total);
    failures += !sync_ok;
    /* Process exit terminates any timed-out worker; avoid closing its event early. */
    if (sync_ok) {
        for (int i = 0; i < 2; ++i) CloseHandle(threads[i]);
        CloseHandle(start_event);
    }
    if (sync) failures += check_sync(result);
    fprintf(result, "result=%s failures=%d\n", failures ? "FAIL" : "PASS", failures);
    fclose(result);
    return failures;
}
