#pragma once
#include <cassert>
#include <deque>
#include <string>
#include <utility>

// One queue per retained ITfContext. Never move queued text to another host.
// The adapter marks an item in flight before calling RequestEditSession because
// DoEditSession may run synchronously. An accepted async request stays in flight
// until DoEditSession reports its actual insertion result.
class PendingDelivery {
public:
    void append(std::wstring text) { if (!text.empty()) waiting_.push_back(std::move(text)); }
    const std::wstring* begin() {
        if (inFlight_ || waiting_.empty()) return nullptr;
        inFlight_ = true;
        return &waiting_.front();
    }
    void finish(bool inserted) {
        assert(inFlight_ && !waiting_.empty());
        inFlight_ = false;
        if (inserted) waiting_.pop_front();
    }
    bool empty() const { return waiting_.empty(); }
    bool inFlight() const { return inFlight_; }
private:
    std::deque<std::wstring> waiting_;
    bool inFlight_ = false;
};
