// GPL-3.0. TEST ONLY: isolated fake public API DLL; never install over a game's
// Steam DLL. No Valve code, credentials, network, ownership or outgoing messages.
#include <windows.h>
#include <cstdio>
#include <cstring>
#include "../../app/src/main/cpp/wn-steam-launcher/steam_friends_receive.h"
static char mode[32], trace_path[MAX_PATH];
static int acquired, released, reads, sends, enabled, init_dispatch;
static bool delivered, held;
static wn_social::FriendMessageEvent event{123, 7};
static void trace(const char* label) {
    FILE* f = std::fopen(trace_path, "a");
    if (!f) return;
    std::fprintf(f, "%s enabled=%d acquired=%d released=%d reads=%d sends=%d manual=%d held=%d\n",
        label, enabled, acquired, released, reads, sends, init_dispatch, held);
    std::fclose(f);
}
extern "C" {
__declspec(dllexport) bool SteamAPI_Init() {
    GetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_CASE", mode, sizeof(mode));
    GetEnvironmentVariableA("VAPOR_SOCIAL_FIXTURE_TRACE", trace_path, sizeof(trace_path));
    trace("initialized");
    return true;
}
__declspec(dllexport) void SteamAPI_Shutdown() { trace("shutdown"); }
__declspec(dllexport) int SteamAPI_GetHSteamUser() { return 1; }
__declspec(dllexport) int SteamAPI_GetHSteamPipe() { return 2; }
__declspec(dllexport) void* SteamAPI_SteamFriends_v018() { return &event; }
__declspec(dllexport) int SteamAPI_ISteamFriends_GetFriendCount(void* self, int flags) {
    return self == &event && flags == 4 ? 1 : -1;
}
__declspec(dllexport) bool SteamAPI_ISteamFriends_SetListenForFriendsMessages(void* self, bool on) {
    if (self != &event) return false;
    enabled = on;
    trace(on ? "listen_on" : "listen_off");
    return !(on && std::strcmp(mode, "listen-reject") == 0) &&
           !(!on && std::strcmp(mode, "disable-fail") == 0);
}
__declspec(dllexport) int SteamAPI_ISteamFriends_GetFriendMessage(void* self, std::uint64_t sender,
        int id, void* data, int capacity, int* type) {
    ++reads;
    const char text[] = "fixture-private-text";
    if (self != &event || sender != 123 || id != 7 || !held || capacity < (int)sizeof(text)) return 0;
    std::memcpy(data, text, sizeof(text));
    *type = 1;
    return sizeof(text);
}
__declspec(dllexport) bool SteamAPI_ISteamFriends_ReplyToFriendMessage(void*, std::uint64_t, const char*) {
    ++sends; trace("unexpected_send"); return false;
}
__declspec(dllexport) void SteamAPI_ManualDispatch_Init() { ++init_dispatch; }
__declspec(dllexport) void SteamAPI_ManualDispatch_RunFrame(int) {
    if (std::strcmp(mode, "hang") == 0) Sleep(INFINITE);
}
__declspec(dllexport) bool SteamAPI_ManualDispatch_GetNextCallback(int pipe, wn_social::CallbackMessage* callback) {
    if (pipe != 2 || !enabled || delivered || held || (std::strcmp(mode, "quiet") == 0 || std::strcmp(mode, "cancel") == 0)) return false;
    delivered = held = true; ++acquired;
    *callback = {1, 343, reinterpret_cast<unsigned char*>(&event),
        std::strcmp(mode, "reject") == 0 ? 12 : (int)sizeof(event)};
    return true;
}
__declspec(dllexport) void SteamAPI_ManualDispatch_FreeLastCallback(int pipe) {
    if (pipe == 2 && held) { ++released; held = false; }
}
}
