// Account-free Windows process test. Link the production clean_shutdown.cpp.
// It pauses cleanup before logoff, then sends the real Android sentinel. No
// Steam library or cloud transfer is simulated, and each case has a fresh process.
#include "../clean_shutdown.h"
#include <windows.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

static HANDLE entered = nullptr;
static HANDLE releaseOwner = nullptr;
static std::string tracePath;
static constexpr const char* sentinel = "C:\\wn-launcher.shutdown";

static void trace(const char* text) {
    HANDLE file = CreateFileA(tracePath.c_str(), FILE_APPEND_DATA,
        FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
        FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) ExitProcess(90);
    const std::string line = std::string(text) + "\n";
    DWORD written = 0;
    const BOOL ok = WriteFile(file, line.data(), (DWORD)line.size(), &written, nullptr);
    CloseHandle(file);
    if (!ok || written != line.size()) ExitProcess(91);
}

static void logSink(const char* line) {
    trace(line);
    if (std::strstr(line, "clean-shutdown teardown begin")) {
        SetEvent(entered);
        if (WaitForSingleObject(releaseOwner, 5000) != WAIT_OBJECT_0) ExitProcess(92);
        trace("TEST owner released");
    }
}

static void submitSentinel() {
    HANDLE file = CreateFileA(sentinel, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) ExitProcess(93);
    CloseHandle(file);
    trace("TEST sentinel submitted");
}

static int child(const char* mode, const char* path) {
    tracePath = path;
    if (GetModuleHandleA("steamclient64.dll") || GetModuleHandleA("steamclient.dll")) return 94;
    entered = CreateEventA(nullptr, TRUE, FALSE, nullptr);
    releaseOwner = CreateEventA(nullptr, TRUE, FALSE, nullptr);
    if (!entered || !releaseOwner) return 95;
    wn_launcher_set_log_sink(logSink);
    wn_launcher_arm_clean_shutdown(nullptr, 0, 0, nullptr);
    std::thread owner;
    if (std::strcmp(mode, "sentinel-first") == 0) {
        submitSentinel();
    } else if (std::strcmp(mode, "natural") == 0) {
        SetEvent(releaseOwner);
        wn_launcher_clean_shutdown_now("game-exit");
        return 0;
    } else {
        owner = std::thread([] { wn_launcher_clean_shutdown_now("game-exit"); });
    }
    if (WaitForSingleObject(entered, 5000) != WAIT_OBJECT_0) ExitProcess(96);
    if (std::strcmp(mode, "game-first") == 0) submitSentinel();
    if (std::strcmp(mode, "concurrent") == 0 || std::strcmp(mode, "sentinel-first") == 0) {
        std::vector<std::thread> duplicates;
        for (int i = 0; i < 4; ++i) {
            duplicates.emplace_back([] { wn_launcher_clean_shutdown_now("duplicate"); });
        }
        for (auto& t : duplicates) t.join();
        trace("TEST duplicate calls returned");
    }
    // The real watcher polls every 150 ms. Keep the owner blocked for several
    // polls. On the original code, game-first exits here with no completion.
    Sleep(750);
    SetEvent(releaseOwner);
    wn_launcher_wait_clean_shutdown(3000);
    if (owner.joinable()) owner.join();
    Sleep(250); // Let a sentinel-owned watcher perform its normal ExitProcess.
    return 0;
}

static std::string readFile(const std::string& path) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return {};
    std::string data;
    char buf[1024];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), file)) != 0) data.append(buf, n);
    std::fclose(file);
    return data;
}

static int occurrences(const std::string& text, const char* marker) {
    int count = 0;
    for (size_t at = 0; (at = text.find(marker, at)) != std::string::npos; at += std::strlen(marker)) ++count;
    return count;
}

int main(int argc, char** argv) {
    if (argc == 4 && std::strcmp(argv[1], "--child") == 0) {
        std::string arg = argv[2];
        return child(arg.c_str(), argv[3]);
    }
    char exe[MAX_PATH];
    if (!GetModuleFileNameA(nullptr, exe, sizeof(exe))) return 97;
    FILE* result = std::fopen((std::string(exe) + ".result").c_str(), "w");
    if (!result) return 98;
    std::fprintf(result, "Vapor shutdown overlap probe guest_bits=%zu no_steam_modules=true\n", sizeof(void*) * 8);
    int failures = 0, checks = 0;
    for (const char* mode : {"natural", "game-first", "sentinel-first", "concurrent"}) {
        for (int cycle = 0; cycle < 2; ++cycle) {
            const std::string path = std::string(exe) + "." + mode + "-" + std::to_string(cycle) + ".trace";
            DeleteFileA(path.c_str());
            std::string command = std::string("\"") + exe + "\" --child " + mode + " \"" + path + "\"";
            STARTUPINFOA startup = {};
            startup.cb = sizeof(startup);
            PROCESS_INFORMATION process = {};
            const BOOL started = CreateProcessA(nullptr, &command[0], nullptr, nullptr, FALSE,
                CREATE_NO_WINDOW, nullptr, nullptr, &startup, &process);
            DWORD exitCode = 99;
            if (started) {
                if (WaitForSingleObject(process.hProcess, 8000) != WAIT_OBJECT_0) {
                    TerminateProcess(process.hProcess, 100);
                    WaitForSingleObject(process.hProcess, 1000);
                }
                GetExitCodeProcess(process.hProcess, &exitCode);
                CloseHandle(process.hThread);
                CloseHandle(process.hProcess);
            }
            const std::string events = readFile(path);
            const int begins = occurrences(events, "clean-shutdown teardown begin");
            const int completions = occurrences(events, "clean logoff complete");
            const bool released = events.find("TEST owner released") != std::string::npos;
            const bool ok = started && exitCode == 0 && begins == 1 && completions == 1 && released;
            ++checks;
            if (!ok) ++failures;
            std::fprintf(result, "case=%s cycle=%d result=%s exit=%lu begins=%d completions=%d owner_released=%d\n",
                mode, cycle, ok ? "PASS" : "FAIL", (unsigned long)exitCode, begins, completions, released ? 1 : 0);
            std::fflush(result);
            DeleteFileA(sentinel);
        }
    }
    std::fprintf(result, "result=%s checks=%d failures=%d\n", failures ? "FAIL" : "PASS", checks, failures);
    std::fclose(result);
    return failures ? 1 : 0;
}
