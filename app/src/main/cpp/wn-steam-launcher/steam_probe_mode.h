// GPL-3.0: mode selection at the existing diagnostic marker-file boundary.
#pragma once
#include <cstring>
namespace wn_social {
// Empty marker preserves legacy behavior. Unknown content must never silently
// enable the legacy diagnostic (which includes auth-ticket inspection).
inline const char* probe_mode_argument(const char* text) {
    if (!text) return nullptr;
    while (*text == ' ' || *text == '\t' || *text == '\r' || *text == '\n') ++text;
    auto size = std::strlen(text);
    while (size && (text[size-1] == ' ' || text[size-1] == '\t' || text[size-1] == '\r' || text[size-1] == '\n')) --size;
    if (!size) return "";
    if (size == 6 && std::memcmp(text, "social", size) == 0) return "--social";
    if (size == 13 && std::memcmp(text, "social-listen", size) == 0) return "--social-listen";
    return nullptr;
}
}
