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
    void append(std::wstring text) { if (!closed_ && !text.empty()) waiting_.push_back(std::move(text)); }
    // A removed context or deactivated service must never receive a late edit.
    // Closing is terminal; a newly focused context gets its own fresh queue.
    void close() { closed_ = true; inFlight_ = false; waiting_.clear(); }
    bool closed() const { return closed_; }
    void suspend() { suspended_ = true; }
    void resume() { if (!closed_) suspended_ = false; }
    bool canDeliver() const { return !closed_ && !suspended_; }
    const std::wstring* begin() {
        if (!canDeliver() || inFlight_ || waiting_.empty()) return nullptr;
        inFlight_ = true;
        return &waiting_.front();
    }
    void finish(bool inserted) {
        if (closed_) return; // TSF may release an accepted asynchronous edit later.
        assert(inFlight_ && !waiting_.empty());
        inFlight_ = false;
        if (inserted) waiting_.pop_front();
    }
    bool empty() const { return waiting_.empty(); }
    bool inFlight() const { return inFlight_; }
private:
    std::deque<std::wstring> waiting_;
    bool inFlight_ = false, closed_ = false, suspended_ = false;
};
