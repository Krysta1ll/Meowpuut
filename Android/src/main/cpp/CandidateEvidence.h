#pragma once

#include "MeowpuutCore.h"

// Only dictionary entries spanning the entire reading count as exact Chinese;
// decoder-composed paths can be accidental English homophones.
inline bool pinyinHasExactChinese(const MPNSession *session) {
    const size_t full = mpn_sentence_count(session);
    for (size_t i = 0; i < full; ++i) {
        MPNCandidateInfo info{};
        if (mpn_candidate_info(session, i, &info, sizeof(info)) && info.kind == MPN_CANDIDATE_EXACT)
            return true;
    }
    return false;
}
