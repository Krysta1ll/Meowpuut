#include "../PendingDelivery.h"
#include <cassert>
int main() {
    PendingDelivery oldHost, newHost;
    oldHost.append(L"你好");
    assert(*oldHost.begin() == L"你好");
    // Synchronous denial: retain, never pass the already-handled key through.
    oldHost.finish(false);
    assert(*oldHost.begin() == L"你好");
    // An async request waits for the callback; later text must remain ordered.
    oldHost.append(L"。");
    assert(oldHost.begin() == nullptr && oldHost.inFlight());
    oldHost.finish(false); // the async callback may fail, too
    assert(*oldHost.begin() == L"你好");
    oldHost.finish(true);
    assert(*oldHost.begin() == L"。");
    oldHost.finish(true);
    assert(oldHost.empty());
    // Two contexts never exchange buffered content across focus changes.
    oldHost.append(L"old host");
    newHost.append(L"new host");
    assert(*newHost.begin() == L"new host");
    newHost.finish(true);
    assert(*oldHost.begin() == L"old host");
    oldHost.finish(true);
    // An accepted async request can outlive focus. DoEditSession checks this
    // same predicate before touching the document, then reports non-insertion.
    oldHost.append(L"selected prefix");
    assert(oldHost.begin());
    oldHost.suspend();
    assert(!oldHost.canDeliver());
    oldHost.finish(false);
    assert(oldHost.begin() == nullptr && !oldHost.empty());
    newHost.append(L"different field");
    assert(*newHost.begin() == L"different field");
    newHost.finish(true);
    oldHost.resume();
    assert(oldHost.canDeliver() && *oldHost.begin() == L"selected prefix");
    oldHost.append(L" ");
    oldHost.append(L"2");
    oldHost.finish(true);
    assert(*oldHost.begin() == L" "); oldHost.finish(true);
    assert(*oldHost.begin() == L"2"); oldHost.finish(true);
    assert(oldHost.empty());
    // Closing a host invalidates even an already accepted async edit. A late
    // completion is harmless and must not deliver queued follow-up text.
    oldHost.append(L"old pending");
    oldHost.append(L"old follow-up");
    assert(oldHost.begin() && oldHost.inFlight());
    oldHost.close();
    assert(oldHost.closed() && oldHost.empty() && !oldHost.inFlight());
    assert(oldHost.begin() == nullptr);
    oldHost.finish(true);
    oldHost.finish(false);
    oldHost.append(L"late append");
    assert(oldHost.empty() && oldHost.begin() == nullptr);
    oldHost.close(); // repeated teardown is safe
    oldHost.resume();
    assert(!oldHost.canDeliver()); // focus cannot reopen a retired document
    newHost.append(L"still live");
    assert(!newHost.closed() && *newHost.begin() == L"still live");
    newHost.finish(true);
    // Closing before a request starts also prevents its delivery.
    newHost.append(L"not requested");
    newHost.close();
    assert(newHost.begin() == nullptr && newHost.empty());
}
