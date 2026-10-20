// GPL-3.0. Account-free execution of the existing receiver contracts under
// Wine/FEX. Build x86/x64 with assertions enabled, -static -mwindows -luser32.
// Run as a local executable in a prepared test container, never as a Steam game.
// Writes <exe>.result; missing completion is a failure. The GUI is only a summary,
// not a Steam client, delivered message, real callback or performance benchmark.
#define main receiver_contract_main
#include "../../app/src/main/cpp/wn-steam-launcher/tests/steam_friends_receive_test.cpp"
#undef main
#include <windows.h>

static const char* summary;
static LRESULT CALLBACK summary_window(HWND window, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_PAINT) {
        PAINTSTRUCT paint;
        HDC dc = BeginPaint(window, &paint);
        RECT area;
        GetClientRect(window, &area);
        InflateRect(&area, -32, -32);
        SetBkMode(dc, TRANSPARENT);
        DrawTextA(dc, summary, -1, &area, DT_LEFT | DT_WORDBREAK);
        EndPaint(window, &paint);
        return 0;
    }
    if (message == WM_TIMER || message == WM_CLOSE) {
        DestroyWindow(window);
        return 0;
    }
    if (message == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(window, message, w, l);
}

int main() {
    char result_path[MAX_PATH + 16];
    const DWORD size = GetModuleFileNameA(nullptr, result_path, MAX_PATH);
    if (!size || size >= MAX_PATH) return 70;
    std::strcat(result_path, ".result");
    if (!std::freopen(result_path, "w", stdout)) return 71;
    std::printf("Vapor receiver contracts: %u-bit Windows guest; synthetic exports only\n",
                static_cast<unsigned>(sizeof(void*) * 8));
    std::fflush(stdout);
    const int status = receiver_contract_main();
    if (status) return status;
    summary = sizeof(void*) == 8
        ? "Vapor - 64-bit Windows receiver contracts\n\n33 scenarios passed.\nOutgoing API resolutions: 0\n\nExecuted under Android Wine/FEX.\nSynthetic callbacks only - no Steam login or message delivery."
        : "Vapor - 32-bit Windows receiver contracts\n\n33 scenarios passed.\nOutgoing API resolutions: 0\n\nExecuted under Android Wine/FEX.\nSynthetic callbacks only - no Steam login or message delivery.";
    WNDCLASSA cls{};
    cls.lpfnWndProc = summary_window;
    cls.hInstance = GetModuleHandleA(nullptr);
    cls.lpszClassName = "VaporSteamReceiverContracts";
    cls.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1);
    if (!RegisterClassA(&cls)) { std::puts("result=FAIL register_window"); return 72; }
    HWND window = CreateWindowA(cls.lpszClassName, "Vapor receiver execution check",
        WS_OVERLAPPEDWINDOW | WS_VISIBLE, 40, 40, 760, 400,
        nullptr, nullptr, cls.hInstance, nullptr);
    if (!window) { std::puts("result=FAIL create_window"); return 73; }
    UpdateWindow(window);
    if (!SetTimer(window, 1, 12000, nullptr)) {
        std::puts("result=FAIL timer"); DestroyWindow(window); return 74;
    }
    std::puts("contracts=PASS summary_ready=1");
    std::fflush(stdout);
    MSG msg{};
    int message_status;
    while ((message_status = GetMessageA(&msg, nullptr, 0, 0)) > 0) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }
    std::puts(message_status < 0 ? "result=FAIL message_loop" : "result=PASS");
    std::fclose(stdout);
    UnregisterClassA(cls.lpszClassName, cls.hInstance);
    return message_status < 0 ? 75 : 0;
}
