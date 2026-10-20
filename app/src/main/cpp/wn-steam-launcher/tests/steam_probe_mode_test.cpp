#include "../steam_probe_mode.h"
#include <cassert>
#include <cstdio>
#include <cstring>
int main() {
    struct Case { const char* text; const char* expected; };
    const Case cases[] = {
        {"", ""}, {" \r\n\t", ""}, {"social", "--social"},
        {"social-listen", "--social-listen"},
        {" \tsocial\r\n", "--social"},
        {"social-listen\n", "--social-listen"},
        {nullptr, nullptr}, {"--social", nullptr}, {"SOCIAL", nullptr},
        {"social-listen extra", nullptr}, {"social\nlisten", nullptr},
        {"social-listen\" --send", nullptr}, {"legacy", nullptr},
    };
    for (const auto& test : cases) {
        const auto result = wn_social::probe_mode_argument(test.text);
        if (test.expected) assert(result && std::strcmp(result, test.expected) == 0);
        else assert(result == nullptr);
    }
    std::puts("Steam probe mode: 13 scenarios passed");
}
