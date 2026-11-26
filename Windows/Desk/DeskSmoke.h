#pragma once
// MeowpuutDesk.exe --smoke, and the host test: the whole desk loop on a
// synthetic desktop, headless, on a simulated clock that follows the core's
// schedule exactly as the shell's timers would.
//
//   0–10 s   both cats come out of the tray door and stand on a ground;
//            an unchanged world is handed to the core once.
//   ~10 s    the pointer rests on Momo's opaque pixels: after 250 ms she is
//            clickable; off her, click-through again.
//   12–15 s  pulses through a pulse-channel section (writer and reader, as
//            the IME and the desk use it): Momo is on the candidate glass
//            from the first key; a commit.
//   30 s     both cats are back on the desk.
//   away     5 minutes without input: tier off; for the next 10 minutes no
//            core wake, no sampling, no frame, only the 30 s input check,
//            which notices you are back.
#include "DeskArt.h"

#include <map>
#include <string>
#include <tuple>

namespace meowpuut::desk {

struct SmokeResult {
    bool passed = false;
    std::string log;
};

// images: nullptr draws every figure as a solid oval (OvalImages); the
// Windows build passes the real PNGs.
SmokeResult runSmoke(ImageSource *images = nullptr);

// Every figure as an opaque oval inside its bounds: for tests without the art.
class OvalImages : public ImageSource {
public:
    const Image *scaled(const CatSprite &sprite, int width, int height) override;

private:
    std::map<std::tuple<const CatSprite *, int, int>, Image> cache_;
};

} // namespace meowpuut::desk
