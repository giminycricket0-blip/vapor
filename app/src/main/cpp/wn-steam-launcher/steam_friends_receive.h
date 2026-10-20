// GPL-3.0: receive-only diagnostic for Vapor's existing Steam API probe.
#pragma once
#include "steam_friends_probe.h"
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace wn_social {
// Windows Steamworks SDK 1.64 uses pack(8). These are the public manual
// dispatch envelope and GameConnectedFriendChatMsg_t (callback 300 + 43).
#pragma pack(push, 8)
struct CallbackMessage {
    std::int32_t user;
    std::int32_t id;
    unsigned char* data;
    std::int32_t size;
};
struct FriendMessageEvent {
    alignas(8) std::uint64_t sender;
    std::int32_t message_id;
};
#pragma pack(pop)
static_assert(sizeof(FriendMessageEvent) == 16, "Windows Friends callback ABI");
static_assert(offsetof(CallbackMessage, data) == 8, "Windows dispatch ABI");
static_assert(sizeof(CallbackMessage) == (sizeof(void*) == 8 ? 24 : 16), "Windows dispatch size");

struct ReceiveResult {
    const char* status = "missing_receive_exports";
    int received = 0;
    int rejected = 0;
    int ignored = 0;
    int exit_code = 8;
};

// The caller owns SteamAPI_Init/Shutdown. This isolated process must never call
// SteamAPI_RunCallbacks or register ordinary SDK callbacks. The caller sleeps
// between 100 ticks (nominally ten seconds); each drains at most 64 callbacks.
// Wait returns false to cancel, still disabling interception before returning.
// Neither outgoing chat nor message contents/SteamIDs cross this diagnostic.
template <typename Resolver, typename Wait>
ReceiveResult receive_friends(Resolver resolve, int user, Wait wait) {
    ReceiveResult result;
    auto getter = reinterpret_cast<FriendsGetter>(resolve("SteamAPI_SteamFriends_v018"));
    if (!getter) getter = reinterpret_cast<FriendsGetter>(resolve("SteamAPI_SteamFriends_v017"));
    auto listen = reinterpret_cast<bool (*)(void*, bool)>(resolve("SteamAPI_ISteamFriends_SetListenForFriendsMessages"));
    auto read = reinterpret_cast<int (*)(void*, std::uint64_t, int, void*, int, int*)>(resolve("SteamAPI_ISteamFriends_GetFriendMessage"));
    auto pipe_get = reinterpret_cast<int (*)()>(resolve("SteamAPI_GetHSteamPipe"));
    auto init = reinterpret_cast<void (*)()>(resolve("SteamAPI_ManualDispatch_Init"));
    auto frame = reinterpret_cast<void (*)(int)>(resolve("SteamAPI_ManualDispatch_RunFrame"));
    auto next = reinterpret_cast<bool (*)(int, CallbackMessage*)>(resolve("SteamAPI_ManualDispatch_GetNextCallback"));
    auto release = reinterpret_cast<void (*)(int)>(resolve("SteamAPI_ManualDispatch_FreeLastCallback"));
    if (!getter || !listen || !read || !pipe_get || !init || !frame || !next || !release) return result;
    void* friends = getter();
    const int pipe = pipe_get();
    if (!friends || user <= 0 || pipe <= 0) {
        result.status = "receive_session_unavailable";
        return result;
    }
    init();
    if (!listen(friends, true)) {
        // Best-effort reset even if enabling was rejected.
        listen(friends, false);
        result.status = "receive_listen_rejected";
        return result;
    }
    bool cancelled = false;
    for (int tick = 0; tick < 100; ++tick) {
        frame(pipe);
        for (int n = 0; n < 64; ++n) {
            CallbackMessage callback{};
            if (!next(pipe, &callback)) break;
            if (callback.id != 343) {
                ++result.ignored;
            } else if (callback.user != user || !callback.data || callback.size != sizeof(FriendMessageEvent)) {
                ++result.rejected;
            } else {
                FriendMessageEvent event{};
                std::memcpy(&event, callback.data, sizeof(event));
                if (!event.sender || event.message_id < 0) {
                    ++result.rejected;
                } else {
                    char buffer[4096]{};
                    int type = 0;
                    const int bytes = read(friends, event.sender, event.message_id, buffer, sizeof(buffer), &type);
                    // Only ordinary chat entries (type 1). The API returns copied
                    // bytes, not a documented C-string length. Never parse or log
                    // text here; reject empty/full-buffer (possibly truncated) data.
                    if (type == 1 && bytes > 0 && bytes < static_cast<int>(sizeof(buffer)) && buffer[0] != '\0') ++result.received;
                    else ++result.rejected;
                    std::memset(buffer, 0, sizeof(buffer));
                }
            }
            release(pipe); // every acquired callback, including malformed ones
        }
        if (!wait()) {
            cancelled = true;
            break;
        }
    }
    if (!listen(friends, false)) {
        result.status = "receive_disable_failed";
        return result;
    }
    if (cancelled) {
        result.status = "receive_cancelled";
        result.exit_code = 11;
    } else if (result.received) {
        result.status = "receive_observed";
        result.exit_code = 0;
    } else if (result.rejected) {
        result.status = "receive_callbacks_rejected";
        result.exit_code = 10;
    } else {
        result.status = "receive_window_no_message";
        result.exit_code = 9;
    }
    return result;
}
} // namespace wn_social
