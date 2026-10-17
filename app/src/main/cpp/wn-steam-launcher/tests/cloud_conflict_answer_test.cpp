#include <cassert>
#include <cstdio>
#include <initializer_list>
#ifndef _WIN32
#include <strings.h>
#define _stricmp strcasecmp
#endif
#include "../clean_shutdown.h"

int main() {
    assert(!wn_cloud_exit_upload_allowed(false, false));
    assert(!wn_cloud_exit_upload_allowed(false, true));
    assert(!wn_cloud_exit_upload_allowed(true, true));
    assert(wn_cloud_exit_upload_allowed(true, false));
    bool keepLocal = false;
    assert(wn_parse_cloud_conflict_answer("local", &keepLocal) && keepLocal);
    assert(wn_parse_cloud_conflict_answer("cloud", &keepLocal) && !keepLocal);
    const char* rejected[] = {nullptr, "", "cancel", "l", "clou", "unknown", "LOCAL", " cloud"};
    for (const char* answer : rejected) {
        // An unanswered/partial/cancelled handoff must not change either starting value.
        for (bool initial : {false, true}) {
            keepLocal = initial;
            assert(!wn_parse_cloud_conflict_answer(answer, &keepLocal));
            assert(keepLocal == initial);
        }
    }
    assert(!wn_parse_cloud_conflict_answer("cloud", nullptr));
    std::puts("Cloud-conflict answer checks passed: explicit local/cloud only; rejected answers preserve the output.");
}
