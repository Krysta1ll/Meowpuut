#pragma once
// Writes a scene as standalone SVG, to look at the Windows candidate window
// on any machine (rsvg-convert turns it into a PNG).
#include "Scene.h"

#include <string>

namespace meowpuut::ui {

struct SvgAssets {
    // data: URIs (or file paths) of the cats' pictures in Windows/UI/Assets.
    std::string mimimo, momo;
    std::string ornament;  // a festival's cluster at the bar's right end
};

std::string renderSvg(const Scene &scene, const SvgAssets &assets,
                      const std::string &fontFamily = "Microsoft JhengHei UI, PingFang TC, sans-serif",
                      float scale = 2.0f);

} // namespace meowpuut::ui
