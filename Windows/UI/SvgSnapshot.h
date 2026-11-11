#pragma once
// Writes a scene as standalone SVG, to look at the Windows candidate window
// on any machine (rsvg-convert turns it into a PNG).
#include "Scene.h"

#include <string>

namespace meowpuut::ui {

struct SvgAssets {
    // data: URIs (or file paths) of the bitmaps in Windows/UI/Assets.
    std::string mimimo[3];  // indexed by CatFace
    std::string momo[3];
    std::string tail;
    // Festival overlays on the same canvas as the sprite (docs/FESTIVALS.md).
    std::string mimimoBack, mimimoFront, momoBack, momoFront;
};

std::string renderSvg(const Scene &scene, const SvgAssets &assets,
                      const std::string &fontFamily = "Microsoft JhengHei UI, PingFang TC, sans-serif",
                      float scale = 2.0f);

} // namespace meowpuut::ui
