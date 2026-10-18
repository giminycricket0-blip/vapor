/* Account-free Wine X11 input check. Build as i686 and x86_64 with -mwindows -luser32.
 * The GUI subsystem avoids a console window taking focus from the pointer fixture.
 * An external pointer must send five complete clicks in each named phase.
 * Writes <exe>.result. Missing clicks fail; a displayed window alone is not a pass.
 * Run only in an isolated container: this fixture temporarily clips the cursor. */
#include <windows.h>
#include <stdio.h>
#include <string.h>

static unsigned downs, ups;

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_LBUTTONDOWN) downs++;
    if (message == WM_LBUTTONUP) ups++;
    if (message == WM_CLOSE) { PostQuitMessage(1); return 0; }
    return DefWindowProcA(window, message, wparam, lparam);
}

int main(void) {
    char path[MAX_PATH + 16];
    DWORD length = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (!length || length >= MAX_PATH) return 70;
    strcat(path, ".result");
    FILE *out = fopen(path, "w");
    if (!out) return 71;
    fprintf(out, "Vapor Wine X11 pointer smoke: %u-bit Windows guest\n", (unsigned)(sizeof(void *) * 8));
    fflush(out);
    WNDCLASSA cls = {0};
    cls.lpfnWndProc = window_proc;
    cls.hInstance = GetModuleHandleA(NULL);
    cls.lpszClassName = "VaporWineInputSmoke";
    cls.hCursor = LoadCursorA(NULL, IDC_ARROW);
    cls.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassA(&cls)) { fclose(out); return 72; }
    int width = GetSystemMetrics(SM_CXSCREEN), height = GetSystemMetrics(SM_CYSCREEN);
    HWND window = CreateWindowA(cls.lpszClassName, "Vapor Wine pointer check", WS_POPUP | WS_VISIBLE,
        0, 0, width, height, NULL, NULL, cls.hInstance, NULL);
    if (!window) {
        fprintf(out, "result=FAIL window error=%lu\n", GetLastError()); fclose(out); return 73;
    }
    ShowWindow(window, SW_SHOW);
    SetForegroundWindow(window);
    UpdateWindow(window);
    RECT clip = {width / 4, height / 4, width * 3 / 4, height * 3 / 4};
    const char *names[] = {"unclipped", "clip-once", "clip-repeated", "released"};
    unsigned failures = 0;
    for (unsigned phase = 0; phase < 4; phase++) {
        downs = ups = 0;
        int clipped = phase == 1 || phase == 2;
        unsigned api_failures = !ClipCursor(clipped ? &clip : NULL);
        api_failures += !SetCursorPos(width / 2, height / 2);
        RECT actual = {0};
        if (clipped) api_failures += !GetClipCursor(&actual) || !EqualRect(&actual, &clip);
        fprintf(out, "phase=%s ready clipped=%d foreground=%d center=%d,%d\n",
                names[phase], clipped, GetForegroundWindow() == window, width / 2, height / 2);
        fflush(out);
        ULONGLONG end = GetTickCount64() + 8000, next_clip = 0;
        int closed = 0;
        while (GetTickCount64() < end && (downs < 5 || ups < 5)) {
            MSG message;
            while (PeekMessageA(&message, NULL, 0, 0, PM_REMOVE)) {
                if (message.message == WM_QUIT) { closed = 1; break; }
                TranslateMessage(&message);
                DispatchMessageA(&message);
            }
            if (closed) break;
            ULONGLONG now = GetTickCount64();
            if (phase == 2 && now >= next_clip) {
                api_failures += !ClipCursor(&clip);
                next_clip = now + 16;
            }
            Sleep(5);
        }
        int ok = !closed && !api_failures && downs >= 5 && ups >= 5;
        fprintf(out, "phase=%s %s downs=%u ups=%u api_failures=%u\n",
                names[phase], ok ? "PASS" : "FAIL", downs, ups, api_failures);
        fflush(out);
        failures += !ok;
        if (closed) break;
    }
    ClipCursor(NULL);
    DestroyWindow(window);
    UnregisterClassA(cls.lpszClassName, cls.hInstance);
    fprintf(out, "result=%s failures=%u\n", failures ? "FAIL" : "PASS", failures);
    fclose(out);
    return failures ? 1 : 0;
}
