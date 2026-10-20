#include "../steam_friends_receive.h"
#include <cassert>
#include <cstdio>
#include <map>
#include <string>
using namespace wn_social;
static int frames, frees, reads, waits, enables, disables, kind, bytes, events;
static bool enabled_ok, disabled_ok, outstanding, interface_ok, terminate_text;
static int selected_pipe, cancel_after;
static FriendMessageEvent event;
static CallbackMessage callback;
static void* getter() { return interface_ok ? reinterpret_cast<void*>(1) : nullptr; }
static int pipe_get() { return selected_pipe; }
static void init() {}
static bool listen(void*, bool enabled) { if (enabled) { ++enables; return enabled_ok; } ++disables; return disabled_ok; }
static void frame(int pipe) { assert(pipe == 2 && !outstanding); ++frames; }
static bool next(int pipe, CallbackMessage* out) {
    assert(pipe == 2 && !outstanding);
    if (!events) return false;
    --events; *out = callback; outstanding = true; return true;
}
static void release(int pipe) { assert(pipe == 2 && outstanding); ++frees; outstanding = false; }
static int read_message(void*, std::uint64_t sender, int id, void* out, int capacity, int* type) {
    assert(outstanding && sender == event.sender && id == event.message_id && capacity == 4096);
    ++reads; *type = kind; std::memset(out, 'x', capacity);
    if (terminate_text && bytes > 0 && bytes < capacity) static_cast<char*>(out)[bytes - 1] = '\0';
    return bytes;
}
static std::map<std::string, Symbol> exports;
static void reset() {
    frames = frees = reads = waits = enables = disables = 0; events = 1;
    interface_ok = terminate_text = true; selected_pipe = 2; cancel_after = 101;
    kind = 1; bytes = 6; enabled_ok = disabled_ok = true; outstanding = false;
    event = {123, 7}; callback = {1, 343, reinterpret_cast<unsigned char*>(&event), sizeof(event)};
    exports = {
        {"SteamAPI_SteamFriends_v018", reinterpret_cast<Symbol>(getter)},
        {"SteamAPI_ISteamFriends_SetListenForFriendsMessages", reinterpret_cast<Symbol>(listen)},
        {"SteamAPI_ISteamFriends_GetFriendMessage", reinterpret_cast<Symbol>(read_message)},
        {"SteamAPI_GetHSteamPipe", reinterpret_cast<Symbol>(pipe_get)},
        {"SteamAPI_ManualDispatch_Init", reinterpret_cast<Symbol>(init)},
        {"SteamAPI_ManualDispatch_RunFrame", reinterpret_cast<Symbol>(frame)},
        {"SteamAPI_ManualDispatch_GetNextCallback", reinterpret_cast<Symbol>(next)},
        {"SteamAPI_ManualDispatch_FreeLastCallback", reinterpret_cast<Symbol>(release)},
    };
}
static ReceiveResult run(int user = 1) {
    return receive_friends([](const char* name) -> Symbol {
        // The receiver must not even resolve any sending function.
        assert(std::strstr(name, "Reply") == nullptr && std::strstr(name, "Send") == nullptr);
        auto found = exports.find(name); return found == exports.end() ? nullptr : found->second;
    }, user, [] { return ++waits < cancel_after; });
}
int main() {
    int cases = 0;
    reset(); auto result = run(); assert(result.received == 1 && result.exit_code == 0);
    assert(frames == 100 && waits == 100 && frees == 1 && reads == 1 && disables == 1); ++cases;
    const auto complete = exports;
    for (const auto& entry : complete) {
        reset(); exports.erase(entry.first); result = run();
        assert(result.exit_code == 8 && frames == 0 && enables == 0); ++cases;
    }
    reset(); result = run(0); assert(result.exit_code == 8 && enables == 0); ++cases;
    reset(); interface_ok = false; result = run(); assert(result.exit_code == 8 && enables == 0); ++cases;
    reset(); selected_pipe = 0; result = run(); assert(result.exit_code == 8 && enables == 0); ++cases;
    reset(); cancel_after = 1; result = run();
    assert(result.exit_code == 11 && frames == 1 && frees == 1 && disables == 1); ++cases;
    reset(); cancel_after = 1; disabled_ok = false; result = run();
    assert(result.exit_code == 8 && disables == 1); ++cases;
    reset(); terminate_text = false; bytes = 1; result = run(); assert(result.received == 1); ++cases;
    reset(); callback.size = -1; result = run(); assert(result.exit_code == 10 && reads == 0 && frees == 1); ++cases;
    reset(); enabled_ok = false; result = run(); assert(frames == 0 && disables == 1); ++cases;
    reset(); disabled_ok = false; result = run(); assert(result.exit_code == 8 && disables == 1); ++cases;
    reset(); events = 0; result = run(); assert(result.exit_code == 9 && frees == 0 && disables == 1); ++cases;
    reset(); callback.id = 42; result = run(); assert(result.ignored == 1 && reads == 0 && frees == 1); ++cases;
    for (int mode = 0; mode < 5; ++mode) {
        reset();
        if (mode == 0) callback.user = 2;
        if (mode == 1) callback.data = nullptr;
        if (mode == 2) callback.size = 12;
        if (mode == 3) event.sender = 0;
        if (mode == 4) event.message_id = -1;
        result = run(); assert(result.rejected == 1 && reads == 0 && frees == 1 && disables == 1); ++cases;
    }
    for (int bad_bytes : {-1, 0, 4096, 4097}) {
        reset(); bytes = bad_bytes; result = run(); assert(result.rejected == 1 && result.exit_code == 10 && frees == 1); ++cases;
    }
    reset(); bytes = 1; result = run(); assert(result.rejected == 1); ++cases;
    reset(); kind = 2; result = run(); assert(result.rejected == 1 && result.exit_code == 10 && frees == 1); ++cases;
    reset(); events = 7000; result = run(); assert(frees == 6400 && frames == 100 && disables == 1); ++cases;
    reset(); exports["SteamAPI_SteamFriends_v017"] = exports["SteamAPI_SteamFriends_v018"];
    exports.erase("SteamAPI_SteamFriends_v018"); result = run(); assert(result.received == 1); ++cases;
    std::printf("Steam Friends receive: %d scenarios passed; outgoing API resolutions=0\n", cases);
    return 0;
}
