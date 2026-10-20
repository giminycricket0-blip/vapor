// GPL-3.0. Account-free DLL/process integration driver for the production probe.
// Keep exe, probe.exe and the test-only steam_api[64].dll in an isolated folder.
// No genuine Steam DLL or game directory should be used with this fixture.
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <string>
static std::string read_file(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb"); if (!f) return {};
    std::string text; char block[1024]; size_t size;
    while ((size = std::fread(block, 1, sizeof(block), f))) text.append(block, size);
    std::fclose(f); return text;
}
static char summary[512];
static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_PAINT) {
        PAINTSTRUCT paint; HDC dc = BeginPaint(window, &paint); RECT rect;
        GetClientRect(window, &rect); InflateRect(&rect, -24, -24);
        DrawTextA(dc, summary, -1, &rect, DT_WORDBREAK); EndPaint(window, &paint); return 0;
    }
    if (message == WM_TIMER || message == WM_CLOSE) { DestroyWindow(window); return 0; }
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(window, message, w, l);
}
int main(int argc, char** argv) {
    if (argc > 2) return 64;
    const char* filter = argc > 1 ? argv[1] : nullptr;
    const unsigned expected = filter ? 1 : 9;
    char module[MAX_PATH]; DWORD length = GetModuleFileNameA(nullptr, module, sizeof(module));
    if (!length || length >= sizeof(module)) return 70;
    const std::string result_path = std::string(module) + ".result";
    FILE* report = std::fopen(result_path.c_str(), "w"); if (!report) return 71;
    char* slash = std::strrchr(module, '\\'); if (!slash) return 72; *slash = '\0';
    const std::string root = module;
    CreateDirectoryA((root + "\\missing").c_str(), nullptr);
    struct Case { const char* name; const char* mode; DWORD code; const char* status; };
    const Case cases[] = {
        {"read-only", "--social", 0, "exports_present_delivery_unverified"},
        {"receive", "--social-listen", 0, "receive_observed"},
        {"quiet", "--social-listen", 9, "receive_window_no_message"},
        {"reject", "--social-listen", 10, "receive_callbacks_rejected"},
        {"listen-reject", "--social-listen", 8, "receive_listen_rejected"},
        {"disable-fail", "--social-listen", 8, "receive_disable_failed"},
        {"cancel", "--social-listen", 11, "receive_cancelled"},
        {"missing-dll", "--social-listen", 2, "not loadable"},
        {"hang", "--social-listen", 124, ""},
    };
    unsigned passed = 0;
    for (const auto& test : cases) {
        if (filter && std::strcmp(filter, test.name) != 0) continue;
        const std::string log = root + "\\" + test.name + ".log";
        const std::string trace = root + "\\" + test.name + ".trace";
        DeleteFileA(log.c_str()); DeleteFileA(trace.c_str());
        SetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_CASE", test.name);
        SetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_TRACE", trace.c_str());
        const std::string probe = root + "\\probe.exe";
        const std::string directory = root + (std::strcmp(test.name, "missing-dll") == 0 ? "\\missing" : "");
        std::string cmd = "\"" + probe + "\" \"" + directory + "\" 1 \"" + log + "\" " + test.mode;
        STARTUPINFOA si{}; si.cb = sizeof(si); PROCESS_INFORMATION pi{};
        bool ok = CreateProcessA(probe.c_str(), cmd.data(), nullptr, nullptr, FALSE,
                                0, nullptr, root.c_str(), &si, &pi);
        bool signalled = false, timed_out = false;
        DWORD code = 999, wait_result = WAIT_FAILED;
        if (ok) {
            ULONGLONG deadline = GetTickCount64() + (std::strcmp(test.name, "hang") == 0 ? 4000 : 20000);
            do {
                wait_result = WaitForSingleObject(pi.hProcess, 50);
                if (!signalled && std::strcmp(test.name, "cancel") == 0 &&
                    read_file(trace).find("listen_on") != std::string::npos) {
                    char cancel_name[96];
                    std::snprintf(cancel_name, sizeof(cancel_name), "Local\\VaporSocialProbeCancel-%lu",
                                  (unsigned long) pi.dwProcessId);
                    HANDLE event = OpenEventA(EVENT_MODIFY_STATE, FALSE, cancel_name);
                    if (event) {
                        signalled = SetEvent(event) != FALSE;
                        CloseHandle(event);
                    }
                }
            } while (wait_result == WAIT_TIMEOUT && GetTickCount64() < deadline);
            if (wait_result == WAIT_TIMEOUT) {
                timed_out = true;
                ok = TerminateProcess(pi.hProcess, 124) != FALSE;
                wait_result = WaitForSingleObject(pi.hProcess, 2000);
            }
            ok = ok && wait_result == WAIT_OBJECT_0 && GetExitCodeProcess(pi.hProcess, &code);
            CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
        }
        const std::string text = read_file(log), audit = read_file(trace);
        const bool hang = std::strcmp(test.name, "hang") == 0;
        const bool missing = std::strcmp(test.name, "missing-dll") == 0;
        const bool read_only = std::strcmp(test.name, "read-only") == 0;
        const bool cancel = std::strcmp(test.name, "cancel") == 0;
        ok = ok && code == test.code && timed_out == hang && (!cancel || signalled) &&
             text.find(test.status) != std::string::npos && text.find("fixture-private-text") == std::string::npos &&
             audit.find("unexpected_send") == std::string::npos;
        if (hang) ok = ok && audit.find("shutdown") == std::string::npos && audit.find("listen_on") != std::string::npos;
        else if (missing) ok = ok && audit.empty();
        else {
            const auto shutdown = audit.find("shutdown enabled=0");
            ok = ok && shutdown != std::string::npos;
            if (read_only) ok = ok && audit.find("listen_on") == std::string::npos;
            else ok = ok && audit.find("listen_off") < shutdown;
            const auto last = audit.substr(shutdown == std::string::npos ? 0 : shutdown);
            const bool event = std::strcmp(test.name, "receive") == 0 || std::strcmp(test.name, "reject") == 0 ||
                               std::strcmp(test.name, "disable-fail") == 0;
            ok = ok && last.find(event ? "acquired=1 released=1" : "acquired=0 released=0") != std::string::npos &&
                 last.find((std::strcmp(test.name, "receive") == 0 || std::strcmp(test.name, "disable-fail") == 0) ? "reads=1" : "reads=0") != std::string::npos &&
                 last.find("sends=0") != std::string::npos && last.find("held=0") != std::string::npos;
        }
        std::fprintf(report, "case=%s result=%s exit=%lu timeout=%d cancel_event_signal=%d\n",
            test.name, ok ? "PASS" : "FAIL", (unsigned long)code, timed_out, signalled);
        std::fflush(report); if (ok) ++passed;
    }
    std::snprintf(summary, sizeof(summary), "Vapor %u-bit production probe integration\n\n%u / %u cases passed.\n\nControlled test DLL only.\nNo Steam account, game DLL, network or message delivery.\n\nIncludes real process exit codes, shutdown audit and event cancellation.\nThe timeout case is enforced by this test driver, not the Steam host.",
        (unsigned)(sizeof(void*)*8), passed, expected);
    WNDCLASSA cls{}; cls.lpfnWndProc=window_proc; cls.hInstance=GetModuleHandleA(nullptr);
    cls.lpszClassName="VaporSocialProbeIntegration"; cls.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);
    bool window_ok = RegisterClassA(&cls) != 0;
    HWND window = window_ok ? CreateWindowA(cls.lpszClassName,"Vapor production probe integration",WS_OVERLAPPEDWINDOW|WS_VISIBLE,
        40,40,900,450,nullptr,nullptr,cls.hInstance,nullptr) : nullptr;
    window_ok = window && SetTimer(window,1,12000,nullptr);
    std::fprintf(report,"cases_passed=%u summary_ready=%d\n",passed,window_ok);std::fflush(report);
    if (window_ok) { MSG msg{}; int rc; while ((rc=GetMessageA(&msg,nullptr,0,0))>0) {TranslateMessage(&msg);DispatchMessageA(&msg);} window_ok=rc==0; }
    else if(window) DestroyWindow(window);
    std::fprintf(report,"result=%s\n",passed==expected && window_ok ? "PASS" : "FAIL");std::fclose(report);
    return passed==expected && window_ok ? 0 : 1;
}
