// GPL-3.0. Exercise the actual diagnostic parent function without invoking the
// genuine launcher entry point. Test-only inclusion avoids a second process-runner
// implementation or exposing an internal function as a new production API.
#define main unused_genuine_launcher_entry
#include "../../app/src/main/cpp/wn-steam-launcher/src/main.cpp"
#undef main

static unsigned runner_gets, runner_frees;
static bool runner_held, runner_bad;
static bool runner_callback(int pipe, void* data) {
    if (pipe != 7 || runner_held) runner_bad = true;
    runner_held = true;
    ++runner_gets;
    memset(data, 0, 16);
    return true; // deliberately never idle: production must cap each drain
}
static void runner_release(int pipe) {
    if (pipe != 7 || !runner_held) runner_bad = true;
    runner_held = false; ++runner_frees;
}
static std::string runner_text(const std::string& path) {
    FILE* file = fopen(path.c_str(), "rb"); if (!file) return {};
    std::string text; char block[1024]; size_t size;
    while ((size = fread(block, 1, sizeof(block), file))) text.append(block, size);
    fclose(file); return text;
}
static bool runner_write(const std::string& path, const char* data) {
    FILE* file = fopen(path.c_str(), "wb"); if (!file) return false;
    bool ok = fputs(data, file) >= 0; return fclose(file) == 0 && ok;
}
static bool runner_child_alive(const std::string& probe) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return true; // cannot establish cleanup
    PROCESSENTRY32 entry{}; entry.dwSize = sizeof(entry);
    bool found = false;
    if (Process32First(snapshot, &entry)) do {
        if (_stricmp(entry.szExeFile, "probe.exe") != 0) continue;
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, entry.th32ProcessID);
        if (!process) { found = true; continue; }
        char image[2048]; DWORD length = sizeof(image), code = 0;
        if (!QueryFullProcessImageNameA(process, 0, image, &length) || !GetExitCodeProcess(process, &code)) found = true;
        else if (code == STILL_ACTIVE && _stricmp(image, probe.c_str()) == 0) found = true;
        CloseHandle(process);
    } while (Process32Next(snapshot, &entry));
    CloseHandle(snapshot); return found;
}
static char runner_summary[512];
static LRESULT CALLBACK runner_window(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_PAINT) {
        PAINTSTRUCT paint; HDC dc = BeginPaint(window, &paint); RECT rect;
        GetClientRect(window, &rect); InflateRect(&rect, -24, -24);
        DrawTextA(dc, runner_summary, -1, &rect, DT_WORDBREAK); EndPaint(window, &paint); return 0;
    }
    if (message == WM_TIMER || message == WM_CLOSE) { DestroyWindow(window); return 0; }
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(window, message, w, l);
}
int main() {
    char module[MAX_PATH]; DWORD length = GetModuleFileNameA(nullptr, module, sizeof(module));
    if (!length || length >= sizeof(module)) return 70;
    const std::string result = std::string(module) + ".result";
    FILE* report = fopen(result.c_str(), "w"); if (!report) return 71;
    char* slash = strrchr(module, '\\'); if (!slash) return 72; *slash = 0;
    const std::string root = module, probe = root + "\\probe.exe";
    unsigned passed = 0;
    const char* cases[] = {"normal-log", "missing-exe", "invalid-exe", "blocked-log", "long-command", "hot-callbacks", "parent-timeout"};
    for (const char* name : cases) {
        const std::string host_log = root + "\\parent-" + name + ".log";
        const std::string child_log = root + "\\child-" + name + ".log";
        const std::string trace = root + "\\parent-" + name + ".trace";
        DeleteFileA(trace.c_str()); DeleteFileA(child_log.c_str());
        SetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_CASE", strcmp(name,"parent-timeout")==0 ? "hang" : "receive");
        SetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_TRACE", trace.c_str());
        bool hot = strcmp(name,"hot-callbacks")==0, timeout = strcmp(name,"parent-timeout")==0;
        std::string executable = probe, directory = root, log = child_log;
        bool setup = true;
        if (strcmp(name,"missing-exe")==0) executable = root + "\\missing.exe";
        if (strcmp(name,"invalid-exe")==0) { executable = root + "\\invalid.exe"; setup = runner_write(executable,"not a PE executable"); }
        if (strcmp(name,"blocked-log")==0) { log = root + "\\blocked-log"; CreateDirectoryA(log.c_str(), nullptr); }
        if (strcmp(name,"long-command")==0) directory = std::string(2400,'x');
        if (strcmp(name,"normal-log")==0 || strcmp(name,"invalid-exe")==0) setup = setup && runner_write(child_log,"STALE_RESULT_MUST_NOT_BE_IMPORTED\n");
        g_logFile = fopen(host_log.c_str(),"w");
        if (!g_logFile) { fclose(report); return 73; }
        setvbuf(g_logFile,nullptr,_IONBF,0);
        runner_gets=runner_frees=0; runner_held=runner_bad=false;
        ULONGLONG start=GetTickCount64();
        const bool attempted = setup && run_probe_exe(executable.c_str(),log.c_str(),directory.c_str(),1,
            hot || timeout ? "--social-listen" : "--social",
            hot || timeout ? runner_callback : nullptr, hot || timeout ? runner_release : nullptr,7);
        const ULONGLONG elapsed=GetTickCount64()-start;
        fclose(g_logFile); g_logFile=nullptr;
        const std::string host=runner_text(host_log), audit=runner_text(trace);
        bool ok=setup && host.find("STALE_RESULT_MUST_NOT_BE_IMPORTED")==std::string::npos && !runner_child_alive(probe);
        if (strcmp(name,"normal-log")==0) ok=ok && attempted && host.find("exited rc=0")!=std::string::npos && host.find("exports_present_delivery_unverified")!=std::string::npos && audit.find("shutdown enabled=0")!=std::string::npos;
        if (strcmp(name,"missing-exe")==0) ok=ok && !attempted && host.find("not present")!=std::string::npos && audit.empty();
        if (strcmp(name,"invalid-exe")==0) ok=ok && !attempted && host.find("CreateProcess failed")!=std::string::npos && audit.empty() && GetFileAttributesA(child_log.c_str())==INVALID_FILE_ATTRIBUTES;
        if (strcmp(name,"blocked-log")==0) ok=ok && !attempted && host.find("cannot remove previous log")!=std::string::npos && audit.empty();
        if (strcmp(name,"long-command")==0) ok=ok && !attempted && host.find("command exceeds path limit")!=std::string::npos && audit.empty();
        if (hot) ok=ok && attempted && elapsed<25000 && host.find("receive_observed")!=std::string::npos && host.find("exited rc=0")!=std::string::npos && audit.find("shutdown enabled=0 acquired=1 released=1 reads=1 sends=0")!=std::string::npos;
        if (timeout) ok=ok && attempted && elapsed>=25000 && elapsed<35000 && host.find("forced diagnostic termination; cleanup unconfirmed")!=std::string::npos && host.find("process exit NOT confirmed")==std::string::npos && host.find("termination failed")==std::string::npos && audit.find("listen_on")!=std::string::npos && audit.find("shutdown")==std::string::npos;
        if (hot || timeout) ok=ok && runner_gets>0 && runner_gets==runner_frees && runner_gets%64==0 && !runner_held && !runner_bad;
        fprintf(report,"case=%s result=%s attempted=%d elapsed_ms=%llu callbacks=%u releases=%u\n",name,ok?"PASS":"FAIL",attempted,(unsigned long long)elapsed,runner_gets,runner_frees);
        fflush(report); if(ok) ++passed;
    }
    snprintf(runner_summary,sizeof(runner_summary),"Vapor %u-bit production parent runner\n\n%u / 7 cases passed.\n\nExact run_probe_exe function from the launcher source.\nReal 25-second timeout; callback pump and stale-log checks.\n\nControlled DLL only. No genuine Steam session or messages.",(unsigned)(sizeof(void*)*8),passed);
    WNDCLASSA cls{};cls.lpfnWndProc=runner_window;cls.hInstance=GetModuleHandleA(nullptr);cls.lpszClassName="VaporParentRunnerFixture";cls.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);
    bool visible=RegisterClassA(&cls)!=0;
    HWND window=visible?CreateWindowA(cls.lpszClassName,"Vapor parent-runner execution",WS_OVERLAPPEDWINDOW|WS_VISIBLE,40,40,900,450,nullptr,nullptr,cls.hInstance,nullptr):nullptr;
    visible=window && SetTimer(window,1,12000,nullptr);
    fprintf(report,"cases_passed=%u summary_ready=%d\n",passed,visible);fflush(report);
    if(visible){ MSG msg{};int rc;while((rc=GetMessageA(&msg,nullptr,0,0))>0){TranslateMessage(&msg);DispatchMessageA(&msg);}visible=rc==0; }
    else if(window) DestroyWindow(window);
    fprintf(report,"result=%s\n",passed==7 && visible?"PASS":"FAIL");fclose(report);return passed==7 && visible?0:1;
}
