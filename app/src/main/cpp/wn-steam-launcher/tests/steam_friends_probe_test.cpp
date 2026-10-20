#include "../steam_friends_probe.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>

using wn_social::Symbol;
static int fixture_interface;
static int count_value = 5;
static int getter_calls = 0, old_getter_calls = 0, count_calls = 0, mutating_calls = 0;
static bool interface_ready = true;
static void* getter() { ++getter_calls; return interface_ready ? &fixture_interface : nullptr; }
static void* old_getter() { ++old_getter_calls; return &fixture_interface; }
static int count(void* self, int flags) {
    assert(self == &fixture_interface && flags == 0x04);
    ++count_calls;
    return count_value;
}
static void forbidden() { ++mutating_calls; }

static std::map<std::string, Symbol> exports() {
    getter_calls = old_getter_calls = count_calls = mutating_calls = 0;
    count_value = 5;
    interface_ready = true;
    return {
        {"SteamAPI_SteamFriends_v018", reinterpret_cast<Symbol>(getter)},
        {"SteamAPI_SteamFriends_v017", reinterpret_cast<Symbol>(old_getter)},
        {"SteamAPI_ISteamFriends_GetFriendCount", reinterpret_cast<Symbol>(count)},
        {"SteamAPI_ISteamFriends_SetListenForFriendsMessages", forbidden},
        {"SteamAPI_ISteamFriends_GetFriendMessage", forbidden},
        {"SteamAPI_ISteamFriends_ReplyToFriendMessage", forbidden},
        {"SteamAPI_GetHSteamPipe", forbidden},
        {"SteamAPI_ManualDispatch_Init", forbidden},
        {"SteamAPI_ManualDispatch_RunFrame", forbidden},
        {"SteamAPI_ManualDispatch_GetNextCallback", forbidden},
        {"SteamAPI_ManualDispatch_FreeLastCallback", forbidden},
    };
}
static wn_social::ProbeResult inspect(const std::map<std::string, Symbol>& symbols) {
    auto result = wn_social::probe_friends([&](const char* name) -> Symbol {
        auto found = symbols.find(name);
        return found == symbols.end() ? nullptr : found->second;
    });
    assert(mutating_calls == 0); // No listener, callback pump, message read or send is invoked.
    return result;
}

int main() {
    auto symbols = exports();
    auto result = inspect(symbols);
    assert(result.exit_code == 0 && result.friend_count == 5);
    assert(std::strcmp(result.status, "exports_present_delivery_unverified") == 0);
    assert(getter_calls == 1 && old_getter_calls == 0 && count_calls == 1);

    symbols = exports();
    symbols.erase("SteamAPI_SteamFriends_v018");
    result = inspect(symbols);
    assert(result.exit_code == 0 && std::strcmp(result.accessor, "SteamFriends017") == 0);
    assert(getter_calls == 0 && old_getter_calls == 1);

    symbols = exports();
    symbols.erase("SteamAPI_SteamFriends_v018");
    symbols.erase("SteamAPI_SteamFriends_v017");
    result = inspect(symbols);
    assert(result.exit_code == 6 && count_calls == 0);
    assert(std::strcmp(result.status, "missing_friends_accessor") == 0);

    symbols = exports();
    interface_ready = false;
    result = inspect(symbols);
    assert(result.exit_code == 6 && count_calls == 0 && old_getter_calls == 0);
    assert(std::strcmp(result.status, "friends_interface_unavailable") == 0);

    symbols = exports();
    symbols.erase("SteamAPI_ISteamFriends_GetFriendCount");
    result = inspect(symbols);
    assert(result.exit_code == 6 && count_calls == 0);
    assert(std::strcmp(result.status, "missing_friend_count_export") == 0);

    symbols = exports();
    count_value = -1;
    result = inspect(symbols);
    assert(result.exit_code == 6 && std::strcmp(result.status, "friends_not_connected") == 0);

    symbols = exports();
    count_value = 0;
    result = inspect(symbols);
    assert(result.exit_code == 0 && result.friend_count == 0); // Empty is not an error.

    const char* chat[] = {"SteamAPI_ISteamFriends_SetListenForFriendsMessages",
        "SteamAPI_ISteamFriends_GetFriendMessage", "SteamAPI_ISteamFriends_ReplyToFriendMessage"};
    for (const char* name : chat) {
        symbols = exports(); symbols.erase(name); result = inspect(symbols);
        assert(result.exit_code == 7 && std::strcmp(result.status, "missing_chat_exports") == 0);
    }
    const char* callbacks[] = {"SteamAPI_GetHSteamPipe", "SteamAPI_ManualDispatch_Init",
        "SteamAPI_ManualDispatch_RunFrame", "SteamAPI_ManualDispatch_GetNextCallback",
        "SteamAPI_ManualDispatch_FreeLastCallback"};
    for (const char* name : callbacks) {
        symbols = exports(); symbols.erase(name); result = inspect(symbols);
        assert(result.exit_code == 7 && std::strcmp(result.status, "missing_manual_callback_exports") == 0);
    }
    std::puts("Steam Friends probe: 15 scenarios passed; mutating calls=0");
}
