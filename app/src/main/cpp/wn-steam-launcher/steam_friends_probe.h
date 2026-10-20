// GPL-3.0: part of Vapor's existing WinNative-derived Steam launcher tools.
#pragma once

namespace wn_social {
using Symbol = void (*)();
using FriendsGetter = void* (*)();
using FriendCount = int (*)(void*, int);

struct ProbeResult {
    const char* accessor = "none";
    const char* status = "missing_friends_accessor";
    int friend_count = -1;
    bool listen_export = false;
    bool message_export = false;
    bool reply_export = false;
    bool callback_exports = false;
    int exit_code = 6;
};

// Bind the game's public flat API exports, not client-build-specific vtable slots.
// Resolver injection keeps this OS/DLL boundary testable without a Steam login.
// Only the interface getter and read-only count query are called. In particular,
// export presence is NOT proof of listening, delivery or server acknowledgement.
template <typename Resolver>
ProbeResult probe_friends(Resolver resolve) {
    ProbeResult result;
    result.listen_export = resolve("SteamAPI_ISteamFriends_SetListenForFriendsMessages") != nullptr;
    result.message_export = resolve("SteamAPI_ISteamFriends_GetFriendMessage") != nullptr;
    result.reply_export = resolve("SteamAPI_ISteamFriends_ReplyToFriendMessage") != nullptr;
    result.callback_exports =
        resolve("SteamAPI_GetHSteamPipe") &&
        resolve("SteamAPI_ManualDispatch_Init") &&
        resolve("SteamAPI_ManualDispatch_RunFrame") &&
        resolve("SteamAPI_ManualDispatch_GetNextCallback") &&
        resolve("SteamAPI_ManualDispatch_FreeLastCallback");

    Symbol getter = resolve("SteamAPI_SteamFriends_v018");
    if (getter) result.accessor = "SteamFriends018";
    else {
        getter = resolve("SteamAPI_SteamFriends_v017");
        if (getter) result.accessor = "SteamFriends017";
    }
    if (!getter) return result;
    void* friends = reinterpret_cast<FriendsGetter>(getter)();
    if (!friends) {
        result.status = "friends_interface_unavailable";
        return result;
    }
    auto count = reinterpret_cast<FriendCount>(resolve("SteamAPI_ISteamFriends_GetFriendCount"));
    if (!count) {
        result.status = "missing_friend_count_export";
        return result;
    }
    // k_EFriendFlagImmediate: ordinary friends, rather than blocked/pending users.
    result.friend_count = count(friends, 0x04);
    if (result.friend_count < 0) {
        result.status = "friends_not_connected";
        return result;
    }
    result.exit_code = 7;
    if (!result.listen_export || !result.message_export || !result.reply_export) {
        result.status = "missing_chat_exports";
    } else if (!result.callback_exports) {
        result.status = "missing_manual_callback_exports";
    } else {
        result.status = "exports_present_delivery_unverified";
        result.exit_code = 0;
    }
    return result;
}
} // namespace wn_social
