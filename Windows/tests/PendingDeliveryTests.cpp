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
}
