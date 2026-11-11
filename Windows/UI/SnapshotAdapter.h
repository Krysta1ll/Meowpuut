#pragma once
// Turns a session snapshot into the candidate window's view model.
#include "../Session.h"
#include "CandidateLayout.h"

#include <string>

namespace meowpuut::ui {

struct CandidateWindowState {
    std::size_t highlighted = 0;  // index on the current page
    bool expanded = false;
    bool zhuyin = false;          // Zhuyin chooses with Shift+digit
    bool simplified = false;
};

inline CandidateViewModel makeViewModel(const meowpuut::Snapshot &snapshot, const CandidateWindowState &state,
                                        GlassShape shape) {
    CandidateViewModel model;
    model.candidates = snapshot.candidates;
    model.japanese = snapshot.japaneseCandidates;
    model.japanese.resize(model.candidates.size(), false);
    model.highlighted = model.candidates.empty() ? 0 : std::min(state.highlighted, model.candidates.size() - 1);
    model.page = snapshot.page;
    model.lastPage = snapshot.lastPage;
    model.expanded = state.expanded;
    model.shiftNumbers = state.zhuyin;
    model.preedit = snapshot.preedit;
    model.modeLabel = std::string(state.zhuyin ? "\xE6\xB3\xA8\xE9\x9F\xB3" : "\xE6\x8B\xBC\xE9\x9F\xB3") + " \xC2\xB7 " +
                      (state.simplified ? "\xE7\xAE\x80" : "\xE7\xB9\x81"); // 注音／拼音 · 繁／简
    model.shape = shape;
    return model;
}

} // namespace meowpuut::ui
