#pragma once
// How the desk-pet process draws the clay cats (PETS.md §6.1, FINAL.md §4.4).
// The art is the user's own clay figures, as PNGs: the program only picks one,
// places it by the paws (a lifted cat by the scruff), mirrors it, squashes it
// (≤ 6 %, the core's hints, capped here too), tilts it, and cuts it at a window
// edge (cats.json `cut` and `paws`) so a cat hides behind the edge with its paws
// over it. It never redraws or recolours a cat. Each cat is one small
// premultiplied-BGRA frame for a layered window; the frame keeps a mask of the
// sprite's own opaque pixels, the only pixels that may catch the mouse (never
// the contact shadow).
//
// Portable C++17; the Win32 shell supplies the decoded PNGs (WIC) through
// ImageSource and hands the frame to UpdateLayeredWindow.
#include "../../Engine/crates/meowpuut-ffi/include/meowpuut.h"
#include "../UI/CatSpriteData.h"

#include <cstdint>
#include <string_view>
#include <vector>

namespace meowpuut::desk {

// The sitting, reacting and sleeping figures (cats.json `sprites`, same layout
// as CatSpriteData.h; build-windows-check.sh compares the tables). The edge and
// side figures, with their cut and paws, are kCatSprites in CatSpriteData.h.
extern const CatSprite kDeskSprites[8];
// The desk poses of CLAY-POSES batches 1 and 2 (cats.json `desk`, the same as
// Assets\desk.json): each cat's walk cycle of 12 frames in order (96 × 64 pt,
// facing right; walk_1–4 are every third frame), turn, look_up, lift (64 × 80 pt,
// anchored at the scruff), fall, and momo_sleep. No cut, no paws.
extern const CatSprite kDeskPoses[33];
// Frames in a cat's walk cycle: its walk rows in kDeskPoses when they are a
// multiple of four (12), else 4 (walk_1–4). cat: 0 Mimimo, 1 Momo.
int walkCycleLength(int cat);
// The figure at `frame` (0…length-1) of a cycle of `length` frames: the table's
// cycle when it has that length, else walk_1–4 by frame % 4.
const CatSprite *walkSprite(int cat, int frame, int length);
// Any table by asset name (the PNG is Assets\<name>.png); nullptr when unknown.
const CatSprite *findSprite(std::string_view name);

// Walk: a frame of the walk cycle (no tilt, no bounce: the clay walks now).
// Held: the lift figure, hung by its scruff. Prep, Air, Stretch: no breathing.
enum class Fx { None, Walk, Prep, Air, Held, Stretch };

struct Art {
    const CatSprite *sprite = nullptr;
    bool behind = false; // only what is above the edge shows, plus the paws over it
    bool side = false;   // side_hold: on its feet beside a window's side
    int native = 1;      // the way the figure faces as drawn: 1 right (walk, and the front poses), -1 left (look_up)
    Fx fx = Fx::None;
};

// What the clay cats (art SLEEP|PEEK|SIDE|WALK) wear for a pose: pets-art.js
// clayPose with CLAY-POSES batches 1 and 2 in. walk/trot: walk_<frame + 1>;
// look: look_up; jump: momo_jump (Mimimo has no hop figure: idle); air: fall;
// held: lift; sleep: Mimimo's and Momo's own; sit just after a walk: turn
// (the side-to-front turn); prep and stretch: idle.
// cat: 0 Mimimo, 1 Momo; walkFrame 0…walkLength-1 (Gait::walkFrame; walkLength Gait::length).
Art clayArt(int cat, const MPDeskCat &c, int walkFrame = 0, bool turning = false, int walkLength = 4);

// What the shell remembers of one cat between core steps to pick the figures
// the core does not name: the walk frame and the turn after a walk. A step (one
// key, one step while you type; one core step on a stroll: the core moves a cat
// a whole stride per step, the parity `phase` flips) advances length / 4
// frames, so it lands on the next key pose walk_1-4 exactly and the
// in-betweens never show. They are for playback by time, which the desk does
// not do yet: the core places a cat only at its steps.
inline constexpr std::uint64_t kTurnMs = 250;
class Gait {
public:
    Gait() = default;
    // Frames in the cycle: a multiple of four, else 4.
    explicit Gait(int length) : length_(length >= 4 && length % 4 == 0 ? length : 4) {}
    int length() const { return length_; }
    int stepFrames() const { return length_ / 4; }
    // Once after every core step, with the cat as the core left it.
    void step(const MPDeskCat &c, std::uint64_t nowMs);
    int walkFrame() const { return frame_; }                 // 0…length-1
    int keyPose() const { return frame_ / stepFrames(); }     // 0…3: walk_1…walk_4
    bool walking() const { return walking_; }
    bool turning(std::uint64_t nowMs) const { return nowMs < turnUntil_; }
    std::uint64_t turnUntil() const { return turnUntil_; } // 0: no turn
    std::uint64_t steps() const { return steps_; }          // steps seen (tests)

private:
    bool walking_ = false;
    std::uint8_t phase_ = 0;
    int length_ = 4;
    int frame_ = 0;
    std::uint64_t turnUntil_ = 0, steps_ = 0;
};

// Premultiplied BGRA, top-down, width × height × 4 bytes.
struct Image {
    int width = 0, height = 0;
    std::vector<std::uint8_t> bgra;
};

class ImageSource {
public:
    virtual ~ImageSource() = default;
    // The sprite's PNG resampled to exactly width × height device pixels;
    // nullptr when it cannot be loaded.
    virtual const Image *scaled(const CatSprite &sprite, int width, int height) = 0;
};

// A sprite pixel at least this opaque catches the mouse.
inline constexpr std::uint8_t kHitAlpha = 128;
// The window each cat lives in, in points at size 1: room for the widest pose
// (edge_lounge; a walk frame is 96 pt), the tallest (side_hold), tilts and the
// contact shadow. The paws sit at (kCanvasW / 2, kPawsY); a lifted cat's
// scruff at (kCanvasW / 2, kScruffY), so its dangling legs stay inside.
inline constexpr double kCanvasW = 160, kCanvasH = 136, kPawsY = 116, kScruffY = 30;

struct Frame {
    bool visible = false;
    std::int32_t left = 0, top = 0;  // physical screen pixels
    int width = 0, height = 0;
    std::uint8_t opacity = 255;      // the whole window (a cross-fade)
    std::vector<std::uint8_t> bgra;  // premultiplied, top-down
    std::vector<std::uint8_t> mask;  // 1 where the sprite itself is opaque
    std::uint64_t signature = 0;     // what was drawn; an equal one is not redrawn
};

struct PaintContext {
    std::uint32_t unitDpi = 96;    // desk points to physical pixels (DeskWorld.h)
    std::uint32_t monitorDpi = 96; // the monitor under the paws: a cat keeps its size on every screen
    double catScale = 1.0;
    double timeMs = 0;             // breathing, stepped at 12 Hz like the core
    bool reduceMotion = false;
    double opacity = 1.0;          // 0–1 while a cross-fade runs
    int walkFrame = 0;             // Gait::walkFrame
    int walkLength = 4;            // Gait::length
    bool turning = false;          // Gait::turning (never with reduceMotion)
};

// A squash or stretch never goes past this (FINAL.md §4.4: ≥ 0.94 down; the lift's 5 % up).
inline constexpr double kSquashCap = 0.06;
// The scruff of a lifted cat is this much of the body's height (52 pt) above its paws (desk.rs drag).
inline constexpr double kScruffRise = 0.82, kBodyHeight = 52;

// Draws one cat; true when the frame changed and its window must be updated
// (or hidden: frame.visible false).
bool paintCat(int cat, const MPDeskCat &c, const PaintContext &ctx, ImageSource &images, Frame &frame);
// Whether a physical screen pixel is on the cat's own opaque pixels.
bool hitTest(const Frame &frame, std::int32_t x, std::int32_t y);

} // namespace meowpuut::desk
