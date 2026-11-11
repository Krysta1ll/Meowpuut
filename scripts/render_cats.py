#!/usr/bin/env python3
"""Render 米米莫 and 莫莫 for every Meowpuut client.

    python3 scripts/render_cats.py                 masters, cats.json and every client's assets
    python3 scripts/render_cats.py --masters DIR   only the masters and cats.json, into DIR
    python3 scripts/render_cats.py --root DIR      use DIR as the repo root

Both cats follow the design mockup and the owner's render of 莫莫: matte,
simple, flat features, soft forms.
  米米莫  one matte-black shape in which the head flows into the body;
          pointed ears with a lighter charcoal inside; cream-yellow half-almond eyes under flat lids that
          slant down toward the nose, with slit pupils; a dot nose; three
          white whiskers on his left cheek
  莫莫    cream coat; cocoa ears (a lighter rim round a darker hollow) and
          paws with toe lines; bean eyes, a bold ω and three bold whiskers on
          a flat pale patch; no nose

米米莫's outline was measured on the mockup. His surface is a height field
over that outline (a rounded edge plus a low inflated dome), lit as matte
black: darkest where it faces the viewer, lighter on the rounded edge, with
a soft sheen where the dome faces the key (top of the head, his back). The
tail shares the same height field, so it meets him without a seam. His eyes,
nose, whiskers and inner ears are flat paint on that surface.

莫莫 is drawn from flat shapes (head, ears, paws), each shaded as a soft matte
pillow under the same key light, with no highlight; a soft form shadow
darkens the edges that turn under (her chin, her paws).

Both cats and the tail get a thin light line inside the outline (rim_light),
sized in screen points; on 米米莫 it is strong all round, so he stays visible
on a dark keyboard.

Before anything is written, verify() measures the anchors in the rendered
masters and stops if one is more than 0.004 of the sprite away.

Outputs (all deterministic):
  assets/brand/cats/*.png               masters (transparent)
  assets/brand/cats/cats.json           anchor metrics shared by every client
  iOS/Keyboard/Cats.xcassets            @2x/@3x image sets
  Resources/Cats                        macOS @1x/@2x PNGs
  Android/app/src/main/res/drawable-nodpi
  Windows/UI/Assets
"""
import argparse
import json
import os

import numpy as np
from PIL import Image
from scipy import ndimage
from scipy.sparse import csr_matrix
from scipy.sparse.linalg import spsolve

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
CATS = os.path.join(ROOT, "assets", "brand", "cats")
FESTIVALS = os.path.join(ROOT, "assets", "festivals")


def set_root(root):
    """Point every input and output path at another checkout."""
    global ROOT, CATS, FESTIVALS
    ROOT = os.path.abspath(root)
    CATS = os.path.join(ROOT, "assets", "brand", "cats")
    FESTIVALS = os.path.join(ROOT, "assets", "festivals")

# --------------------------------------------------------------------------
# Canvases. Client code depends on these sizes and on the anchors below, so
# they are fixed here instead of being measured from the art.
# --------------------------------------------------------------------------

# 米米莫 is laid out in "units": 100 units span the old 2048 px working canvas.
UNIT = 20.48
MIMIMO_SIZE = (468, 512)
MIMIMO_BOX = (137 / UNIT, 6 / UNIT, 1953 / UNIT, 1994 / UNIT)  # units covered by his master
TAIL_SIZE = (643, 160)
TAIL_ASPECT = 1958 / 487  # the aspect clients draw the tail at
MOMO_SIZE = (540, 512)
MOMO_ASPECT = 1009 / 957  # the aspect clients draw her at

# Anchors (units). cats.json is written from these.
EYE_LINE_Y = 44.25      # the left eye's upper lid (no client reads it)
EYE_BOTTOM_Y = 52.5     # lowest point of the eyes; clients rest it on the glass rim
EYE_X = (45.5, 68.6)    # middle of each eye's visible iris
NOSE = (60.4, 48.2)
FACE_RIGHT_X = 92.5
BODY_LEFT_X = 7.0

SUPERSAMPLE = 3         # samples per pixel side for 米米莫 and the tail
MOMO_SUPERSAMPLE = 4

# Point heights of the phone layout (docs/DESIGN.md). The rim line is sized
# in these points so it is equally thin on both cats and the tail.
MIMIMO_PT = 62.0
MOMO_PT = 54.0
TAIL_PT = 28.0
UNITS_PER_PT = (MIMIMO_BOX[3] - MIMIMO_BOX[1]) / MIMIMO_PT


def smoothstep(a, b, t):
    t = np.clip((t - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def canvas_grid(box, size, ss):
    """Sample centres of a canvas of `size` pixels covering `box`, `ss` samples per pixel side."""
    x0, y0, x1, y1 = box
    w, h = size[0] * ss, size[1] * ss
    xs = x0 + (np.arange(w) + 0.5) * (x1 - x0) / w
    ys = y0 + (np.arange(h) + 0.5) * (y1 - y0) / h
    return np.meshgrid(xs, ys)


# --------------------------------------------------------------------------
# 2D shapes (signed distance, negative inside). x right, y down.
# --------------------------------------------------------------------------

def smin(a, b, k):
    """Smooth union: the two shapes meet in a fillet about `k` wide."""
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1)
    return b + (a - b) * h - k * h * (1 - h)


def smax(a, b, k):
    return -smin(-a, -b, k)


def sd_ellipse(x, y, cx, cy, rx, ry):
    qx, qy = (x - cx) / rx, (y - cy) / ry
    k0 = np.sqrt(qx * qx + qy * qy)
    k1 = np.sqrt((qx / rx) ** 2 + (qy / ry) ** 2)
    return k0 * (k0 - 1) / np.maximum(k1, 1e-9)


def sd_superellipse_2d(u, v, radii, power):
    ax, ay = np.abs(u) / radii[0], np.abs(v) / radii[1]
    s = np.maximum(ax ** power + ay ** power, 1e-12)
    g = s ** (1 / power)
    gx = s ** (1 / power - 1) * ax ** (power - 1) / radii[0]
    gy = s ** (1 / power - 1) * ay ** (power - 1) / radii[1]
    return (g - 1) / np.maximum(np.hypot(gx, gy), 1e-6)


def sd_convex(x, y, corners):
    """Distance to a convex polygon given clockwise on screen (y down)."""
    inside = np.full(x.shape, -np.inf)
    outside = np.full(x.shape, np.inf)
    for (ax, ay), (bx, by) in zip(corners, corners[1:] + corners[:1]):
        vx, vy = bx - ax, by - ay
        length = np.hypot(vx, vy)
        px, py = x - ax, y - ay
        inside = np.maximum(inside, (px * vy - py * vx) / length)
        h = np.clip((px * vx + py * vy) / (length * length), 0, 1)
        outside = np.minimum(outside, np.hypot(px - vx * h, py - vy * h))
    return np.where(inside > 0, outside, inside)


def sd_uneven_capsule(r, t, r1, r2, length):
    """2D distance to the hull of two circles: radius r1 at t=0, r2 at t=length.
    `r` is the distance from the axis, `t` the position along it."""
    b = (r1 - r2) / length
    a = np.sqrt(1 - b * b)
    k = t * a - r * b
    return np.where(k < 0, np.hypot(r, t) - r1,
                    np.where(k > a * length, np.hypot(r, t - length) - r2, r * a + t * b - r1))


def axis_coords(px, py, a, b):
    """Distance from the axis a->b and position along it."""
    vx, vy = b[0] - a[0], b[1] - a[1]
    length = np.hypot(vx, vy)
    vx, vy = vx / length, vy / length
    t = (px - a[0]) * vx + (py - a[1]) * vy
    r = np.abs((px - a[0]) * vy - (py - a[1]) * vx)
    return r, t, length


def sd_stroke(x, y, points, radii):
    """Distance to a round-ended stroke along a polyline with a radius per point."""
    best = np.full(x.shape, np.inf)
    for (ax, ay), (bx, by), ar, br in zip(points, points[1:], radii, radii[1:]):
        px, py = x - ax, y - ay
        vx, vy = bx - ax, by - ay
        h = np.clip((px * vx + py * vy) / (vx * vx + vy * vy), 0, 1)
        best = np.minimum(best, np.hypot(px - vx * h, py - vy * h) - (ar + (br - ar) * h))
    return best


def quadratic(p0, p1, p2, steps=28):
    t = np.linspace(0, 1, steps + 1)
    xs = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
    ys = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
    return list(zip(xs, ys)), t


# --------------------------------------------------------------------------
# 米米莫: the silhouette
# --------------------------------------------------------------------------
#
# Measured on the design mockup (A_left.png) scaled onto his canvas so that
# the eye bottom, body left edge and face right edge fall on the anchors.
# The head and both ears are one rounded convex outline with the dip between
# the ears cut out; the body is a broad blob under it that bulges out on the
# left, and the head flows into it.

HEAD_CORNERS = [(23.8, 20.4),    # left ear tip
                (65.6, 10.6),    # right ear tip
                (76.6, 25.9),    # the right ear's outer edge bows out a little
                (88.9, 45.8),    # cheek under the right ear
                (86.5, 57.5),
                (72.0, 64.0),
                (30.0, 61.0),
                (22.1, 55.0),    # the left side of the head is upright
                (22.1, 34.0)]
HEAD_ROUND = 3.6                 # radius of the ear tips and of every corner
EAR_DIP = ((47.4, 29.8), (27.0, 17.6), (63.6, 9.2))  # bottom of the dip, a point on each inner ear edge
EAR_DIP_ROUND = (5.0, 4.0)      # rounding of the dip's bottom, and where it meets the ear tips
BODY = ((47.0, 73.0), (40.0, 24.0), 2.4)               # centre, radii, superellipse power
BODY_FILLET = 12.0


def sd_head(x, y):
    hull = sd_convex(x, y, HEAD_CORNERS) - HEAD_ROUND
    (bx, by), left, right = EAR_DIP

    def beside(point, sign):
        """Distance to the line from the dip's bottom through `point`, negative toward the dip."""
        vx, vy = point[0] - bx, point[1] - by
        return sign * ((x - bx) * vy - (y - by) * vx) / np.hypot(vx, vy)

    dip = smax(beside(left, 1), beside(right, -1), EAR_DIP_ROUND[0])  # the wedge between the ears
    return smax(hull, -dip, EAR_DIP_ROUND[1])


def sd_mimimo(x, y):
    (cx, cy), radii, power = BODY
    return smin(sd_head(x, y), sd_superellipse_2d(x - cx, y - cy, radii, power), BODY_FILLET)


# --------------------------------------------------------------------------
# 米米莫: the tail (capsule glass only)
# --------------------------------------------------------------------------

# Clients draw the tail canvas 3 pt right of 米米莫's left edge, with its bottom
# 1 pt below the glass's bottom rim. Phone and desktop differ a little in
# (cat height, glass height, tail height); the tail is modelled for their mean.
TAIL_LAYOUTS = ((62.0, 46.0, 28.0), (48.0, 38.0, 22.0))


def tail_box():
    """Units of 米米莫's layout covered by the tail canvas in the mean layout."""
    cat_units = MIMIMO_BOX[3] - MIMIMO_BOX[1]
    boxes = []
    for cat, glass, tail in TAIL_LAYOUTS:
        point = cat_units / cat
        left = MIMIMO_BOX[0] + 3 * point
        bottom = EYE_BOTTOM_Y + (glass + 1) * point
        height = tail * point
        boxes.append((left, bottom - height, left + height * TAIL_SIZE[0] / TAIL_SIZE[1], bottom))
    return tuple(np.mean(boxes, axis=0))


TAIL_BOX = tail_box()
TAIL_PX = (TAIL_BOX[3] - TAIL_BOX[1]) / TAIL_SIZE[1]  # units per tail pixel

# Shape of the tail in tail pixels. In every layout the rounded left end of
# the glass is close to a circle of radius 135 centred (192, 19) with its
# bottom rim at y = 153.5. The tail drops from his rump around that circle,
# runs along the rim and lifts a little at the tip. Its inner edge keeps a
# fixed distance inside the rim; the root is thicker and grows outward.
TAIL_CORNER = (192.5, 13.0)        # centre of the corner arc
TAIL_INNER = 119.3                 # radius of the tail's inner edge around that centre
TAIL_RADIUS = 13.0                 # half thickness of the even part
TAIL_ROOT_RADIUS = 30.0            # half thickness where it leaves the body
TAIL_ROOT_TOP = -48.0              # y where the centre line starts, inside his body
TAIL_RUN_END = 444.0               # x where the straight run ends
TAIL_LIFT = (470.0, 0.16)          # radius and angle of the lift at the tip
TAIL_FILLET = 10.0                 # width of the fillet between body and tail (units)


def tail_shape(px, py):
    """Signed distance to the tail's outline and to its centre line, in tail pixels.

    The centre line is a straight root, a quarter turn, a straight run and a
    shallow lift. The half thickness depends on the position along it; the
    run keeps one thickness to its round tip.
    """
    cx, cy = TAIL_CORNER
    turn = np.clip(np.arctan2(py - cy, -(px - cx)), 0, np.pi / 2)
    # Half thickness: the root value down to the start of the turn, easing to
    # the even value a little past half way round.
    flare = 1 - smoothstep(0.0, 1.15, turn + np.clip((py - cy) / 60.0, -1.5, 0))
    lift, sweep = TAIL_LIFT
    lx, ly = TAIL_RUN_END, cy + TAIL_INNER + TAIL_RADIUS - lift
    rise = np.clip(np.arctan2(px - lx, py - ly), 0, sweep)
    half = TAIL_RADIUS + (TAIL_ROOT_RADIUS - TAIL_RADIUS) * flare
    centre = TAIL_INNER + TAIL_RADIUS + (TAIL_ROOT_RADIUS - TAIL_RADIUS) * flare
    # Distance to the centre line, piece by piece.
    root = np.hypot(px - (cx - centre), py - np.clip(py, TAIL_ROOT_TOP, cy))
    corner = np.hypot(px - (cx - centre * np.cos(turn)), py - (cy + centre * np.sin(turn)))
    run = np.hypot(px - np.clip(px, cx, lx), py - (cy + centre))
    tip = np.hypot(px - (lx + lift * np.sin(rise)), py - (ly + lift * np.cos(rise)))
    axis = np.minimum(np.minimum(root, corner), np.minimum(run, tip))
    return axis - half, axis


def sd_tail(x, y):
    """The tail's outline in units."""
    outline, _ = tail_shape((x - TAIL_BOX[0]) / TAIL_PX, (y - TAIL_BOX[1]) / TAIL_PX)
    return outline * TAIL_PX


def sd_figure(x, y):
    """米米莫 with his tail attached."""
    return smin(sd_mimimo(x, y), sd_tail(x, y), TAIL_FILLET)


# --------------------------------------------------------------------------
# 米米莫: surface and light
# --------------------------------------------------------------------------
#
# The surface is a height field over the silhouette: a rounded bevel along
# the outline plus a low dome from inflating the whole figure (head, body
# and tail together, so both sprites share it and the tail meets him
# without a seam). It is lit as matte charcoal vinyl.

BEVEL = 5.0              # radius of the rounded edge, units
DOME = 0.9               # height of the dome per sqrt(unit^2) of inflation
FIELD_BOX = (0.0, 0.0, 192.0, 132.0)
FIELD_CELL = 0.4         # units per cell of the inflation grid


def unit(v):
    v = np.array(v, dtype=np.float64)
    return v / np.linalg.norm(v)


# Light for both cats: a broad key from the upper left. 米米莫's coat is matte
# black: dark where it faces the viewer, a little lighter on the slopes that
# face the key, and lighter again on the rounded edge, most on the lit side.
KEY = unit((-0.50, -0.68, 0.72))
COAT = 0.0085                                 # linear light of the coat facing the viewer
COAT_KEY = (0.075, 8.0)                       # broad key on the slopes: strength, tightness
COAT_EDGE = (0.30, 1.5, 0.70)                 # light on the rounded edge: strength, falloff, share on the far side
COAT_TINT = np.array([1.0, 0.91, 0.92])       # a little warm, as in the mockup
COAT_SHEEN = (0.05, 30.0)                     # soft matte sheen where the dome faces the key: strength, tightness


def shade(n):
    """Matte black coat, sRGB."""
    nx, ny, nz = n
    key = np.clip(nx * KEY[0] + ny * KEY[1] + nz * KEY[2], 0, 1) ** COAT_KEY[1]
    tilt = np.maximum(np.hypot(nx, ny), 1e-6)
    facing = (nx * KEY[0] + ny * KEY[1]) / (tilt * np.hypot(KEY[0], KEY[1]))
    edge = (1 - nz) ** COAT_EDGE[1] * (COAT_EDGE[2] + (1 - COAT_EDGE[2]) * smoothstep(-0.6, 0.9, facing))
    half = unit(KEY + np.array([0.0, 0.0, 1.0]))
    sheen = np.clip(nx * half[0] + ny * half[1] + nz * half[2], 0, 1) ** COAT_SHEEN[1]
    return to_srgb((COAT + COAT_KEY[0] * key + COAT_EDGE[0] * edge + COAT_SHEEN[0] * sheen)[..., None] * COAT_TINT)


def to_srgb(linear):
    linear = np.clip(linear, 0, 1)
    return np.where(linear <= 0.0031308, 12.92 * linear, 1.055 * linear ** (1 / 2.4) - 0.055)


_dome = {}


def dome_field():
    """Inflation of the whole figure on a coarse grid (units^2), cached."""
    if not _dome:
        x, y = canvas_grid(FIELD_BOX, (int((FIELD_BOX[2] - FIELD_BOX[0]) / FIELD_CELL),
                                       int((FIELD_BOX[3] - FIELD_BOX[1]) / FIELD_CELL)), 1)
        _dome["u"] = inflate(sd_figure(x, y) < 0, 1 / FIELD_CELL, scale=1.0)
    return _dome["u"]


def surface(x, y, d):
    """Height of the surface at (x, y) units, where `d` is the distance to the visible outline."""
    u = ndimage.map_coordinates(dome_field(), [(y - FIELD_BOX[1]) / FIELD_CELL - 0.5,
                                               (x - FIELD_BOX[0]) / FIELD_CELL - 0.5], order=3, mode="nearest")
    t = np.clip(-d / BEVEL, 0, 1)
    return BEVEL * np.sqrt(1 - (1 - t) ** 2) + DOME * np.sqrt(np.clip(u, 0, None))


def render_surface(x, y, pixel, d):
    """Light the surface on a sample grid (units, `pixel` apart). `d` is the
    distance to the visible outline there. Returns sRGB colour and coverage."""
    h = surface(x, y, d)
    gy, gx = np.gradient(h, pixel)
    norm = np.sqrt(1 + gx * gx + gy * gy)
    rgb = shade((-gx / norm, -gy / norm, 1 / norm))
    coverage = np.clip(0.5 - d / pixel, 0, 1)
    return rgb, coverage


# The light line just inside every outline. Width in screen points.
RIM_LINE_WIDTH = 0.62
RIM_LINE_TOP = 0.62                              # opacity where the edge faces the key light
RIM_LINE_AROUND = 0.17                           # opacity on the far side
MIMIMO_RIM = (0.92, 0.72, 0.80)                  # 米米莫 and his tail: wider and stronger, as in the mockup;
                                                 # it keeps him off a dark keyboard
RIM_LINE_COLOR = np.array([1.0, 0.975, 0.95])


def rim_light(rgb, d, px_per_pt, top=RIM_LINE_TOP, around=RIM_LINE_AROUND, width=RIM_LINE_WIDTH):
    """Thin light line just inside the outline, strongest where the edge
    faces the key light. `d` is the signed distance to the outline in points
    (negative inside); `top` and `around` are its opacity on the lit and the
    far side, `width` its width in points."""
    gy, gx = np.gradient(ndimage.gaussian_filter(d, 0.12 * px_per_pt))
    norm = np.maximum(np.hypot(gx, gy), 1e-9)
    facing = np.clip((gx * KEY[0] + gy * KEY[1]) / (norm * np.hypot(KEY[0], KEY[1])), -1, 1)
    amount = around + (top - around) * smoothstep(-0.25, 0.85, facing)
    line = 1 - smoothstep(0.45 * width, width, -d)
    a = (line * amount)[..., None]
    return rgb * (1 - a) + RIM_LINE_COLOR * a


def downsample(rgb, alpha, ss=SUPERSAMPLE):
    """Box-filter a supersampled image down to the canvas, weighting colour by alpha."""
    h, w = alpha.shape[0] // ss, alpha.shape[1] // ss
    a = alpha.reshape(h, ss, w, ss).mean(axis=(1, 3))
    premult = (rgb * alpha[..., None]).reshape(h, ss, w, ss, 3).mean(axis=(1, 3))
    out = np.zeros((h, w, 4))
    out[..., :3] = premult / np.maximum(a, 1e-6)[..., None]
    out[..., 3] = a
    return Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8))


# --------------------------------------------------------------------------
# 米米莫: face and frames
# --------------------------------------------------------------------------

IRIS = np.array([0.965, 0.862, 0.635])        # cream yellow, flat (sRGB)
PUPIL = np.array([0.040, 0.034, 0.036])
LINER = np.array([0.040, 0.034, 0.036])       # rim of the eye, darker than the coat
WHISKER = np.array([0.960, 0.950, 0.935])
NOSE_COLOR = np.array([0.060, 0.052, 0.054])

# Each eye is half an almond under a straight upper lid; the head tilts up
# to the right, so the right lid slants steeply and the left one is level,
# both lower toward the nose. lid centre, lid angle (degrees, screen), half
# width, depth below the lid, how much deeper the right half is, pupil
# position along the lid (-1 left end, 1 right end; he looks to the right).
MIMIMO_EYES = (
    ((45.45, 44.25), 0.0, 8.3, 8.12, 0.05, 0.30),
    ((67.85, 37.15), -33.5, 7.45, 8.6, 0.0, 0.30),
)
PUPIL_WIDTH = 1.80                            # half width where it meets the lid, units
PUPIL_TIP = 1.15                              # radius of its round bottom
PUPIL_DEPTH = 0.80                            # share of the eye's depth it hangs down
# Whiskers on the left cheek: root, bend point, tip.
MIMIMO_WHISKERS = (
    ((34.2, 59.2), (25.8, 60.6), (17.8, 64.4)),
    ((34.2, 62.4), (26.2, 65.6), (19.2, 70.6)),
    ((34.6, 65.2), (28.2, 69.4), (23.0, 74.6)),
)
WHISKER_RADIUS = 0.74
# Inside of the ears: tip, then the two ends of the base (clockwise).
MIMIMO_INNER_EARS = (((25.6, 22.6), (39.0, 30.2), (23.4, 34.5)),
                     ((66.0, 14.5), (73.0, 28.5), (55.0, 28.8)))
INNER_EAR = (np.array([0.360, 0.330, 0.335]), 0.6, 2.4, 0.75)  # colour, rounding, edge softness (units), opacity
CLOSED_TILT = 0.4                             # share of an eye's tilt its closed line takes


def edge(sd, feather):
    """Anti-aliased coverage of the region where `sd` is negative."""
    return np.clip(0.5 - sd / feather, 0, 1)


class Decals:
    """Flat paint over the lit surface, in sRGB."""

    def __init__(self, x, y, pixel):
        self.x, self.y, self.pixel = x, y, pixel
        self.rgb = np.zeros(x.shape + (3,))
        self.alpha = np.zeros(x.shape)

    def window(self, x0, y0, x1, y1):
        """Index of the samples inside a box, so small shapes skip the rest of the canvas."""
        cols = np.nonzero((self.x[0] >= x0) & (self.x[0] <= x1))[0]
        rows = np.nonzero((self.y[:, 0] >= y0) & (self.y[:, 0] <= y1))[0]
        return np.s_[rows[0]:rows[-1] + 1, cols[0]:cols[-1] + 1]

    def paint(self, where, alpha, color):
        a = np.clip(alpha, 0, 1)[..., None]
        self.rgb[where] = self.rgb[where] * (1 - a) + color * a
        self.alpha[where] = self.alpha[where] * (1 - a[..., 0]) + a[..., 0]


def eye_frame(d, eye):
    """Window around an eye and the eye's own coordinates there: s along the
    lid toward the nose, t down from the lid (both in units)."""
    (cx, cy), angle, a, b = eye[:4]
    w = d.window(cx - a - 3, cy - a - b - 3, cx + a + 3, cy + a + b + 3)
    c, s_ = np.cos(np.radians(angle)), np.sin(np.radians(angle))
    dx, dy = d.x[w] - cx, d.y[w] - cy
    return w, dx * c + dy * s_, -dx * s_ + dy * c


def eye_opening(s, t, eye):
    """Signed distance (close to the outline) to the visible half almond."""
    a, b, skew = eye[2], eye[3], eye[4]
    depth = b * (1 + skew * s / a)
    q = np.sqrt((s / a) ** 2 + (np.maximum(t, 0) / depth) ** 2)
    bowl = (q - 1) * np.minimum(a, depth) / np.maximum(np.sqrt(q), 0.3)
    return np.maximum(bowl, -t)


def paint_open_eye(d, eye):
    w, s, t = eye_frame(d, eye)
    a, b, skew, pupil_at = eye[2], eye[3], eye[4], eye[5]
    f = d.pixel
    opening = eye_opening(s, t, eye)
    d.paint(w, edge(opening - 0.38, f), LINER)
    d.paint(w, edge(opening, f), IRIS)
    # A thin vertical slit pupil hanging from the lid, in screen coordinates.
    angle = np.radians(eye[1])
    (cx, cy) = eye[0]
    px = cx + pupil_at * a * np.cos(angle)
    py_lid = cy + pupil_at * a * np.sin(angle)
    depth = b * (1 + skew * pupil_at) * np.sqrt(max(0.0, 1 - pupil_at ** 2)) * PUPIL_DEPTH
    x, y = d.x[w], d.y[w]
    slit = sd_stroke(x, y, [(px, py_lid - 1.5), (px, py_lid + depth - PUPIL_TIP)], [PUPIL_WIDTH, PUPIL_TIP])
    d.paint(w, edge(np.maximum(slit, opening - 0.38), f), PUPIL)


def paint_closed_eye(d, eye, happy):
    """Closed eyes are drawn in the iris colour so they read on a dark face.
    They sit in the middle of the open eye and take only part of its tilt."""
    (cx, cy), angle, a, b = eye[:4]
    c, s_ = np.cos(np.radians(angle)), np.sin(np.radians(angle))
    middle = (cx - s_ * b * 0.45, cy + c * b * 0.45)
    w, s, t = eye_frame(d, (middle, angle * CLOSED_TILT, a, b))
    v = np.linspace(-1, 1, 33)
    span = a * 0.80
    if happy:
        line = [(span * k, b * 0.18 - b * 0.50 * (1 - k * k)) for k in v]
    else:
        line = [(span * k, -b * 0.06 + b * 0.22 * (1 - k * k)) for k in v]
    radii = [1.0 - 0.25 * abs(k) ** 3 for k in v]
    d.paint(w, edge(sd_stroke(s, t, line, radii) - 0.5, d.pixel), LINER)
    d.paint(w, edge(sd_stroke(s, t, line, radii), d.pixel), IRIS)


def paint_inner_ears(d):
    """A lighter charcoal shape inside each ear, fading out toward the head."""
    for corners in MIMIMO_INNER_EARS:
        xs, ys = [c[0] for c in corners], [c[1] for c in corners]
        w = d.window(min(xs) - 2, min(ys) - 2, max(xs) + 2, max(ys) + 2)
        x, y = d.x[w], d.y[w]
        tip, base = np.array(corners[0]), (np.array(corners[1]) + np.array(corners[2])) / 2
        along = ((x - tip[0]) * (base - tip)[0] + (y - tip[1]) * (base - tip)[1]) / np.sum((base - tip) ** 2)
        shape = edge(sd_convex(x, y, corners) - INNER_EAR[1], INNER_EAR[2])
        d.paint(w, shape * INNER_EAR[3] * (1 - smoothstep(0.45, 1.0, along)), INNER_EAR[0])


def paint_face(d, expression):
    paint_inner_ears(d)
    for eye in MIMIMO_EYES:
        if expression == "open":
            paint_open_eye(d, eye)
        else:
            paint_closed_eye(d, eye, expression == "happy")
    # Nose: a small dark dot.
    nx, ny = NOSE
    w = d.window(nx - 3, ny - 3, nx + 3, ny + 3)
    d.paint(w, edge(np.hypot(d.x[w] - nx, d.y[w] - ny) - 0.55, d.pixel), NOSE_COLOR)
    # Whiskers: three bold strokes with round ends.
    w = d.window(14, 54, 38, 79)
    x, y = d.x[w], d.y[w]
    for root, bend, tip in MIMIMO_WHISKERS:
        points, t = quadratic(root, bend, tip)
        radii = WHISKER_RADIUS * (1 - 0.22 * t ** 2)
        d.paint(w, edge(sd_stroke(x, y, points, list(radii)), d.pixel), WHISKER)


def bleed(rgb, inside):
    """Samples outside `inside` take the nearest colour in it, so edges stay clean when scaled."""
    _, (iy, ix) = ndimage.distance_transform_edt(~inside, return_indices=True)
    return rgb[iy, ix]


def render_mimimo_frames():
    """The three frames share one surface; only the eye paint differs."""
    x, y = canvas_grid(MIMIMO_BOX, MIMIMO_SIZE, SUPERSAMPLE)
    pixel = (MIMIMO_BOX[2] - MIMIMO_BOX[0]) / x.shape[1]
    d = sd_mimimo(x, y)
    rgb, coverage = render_surface(x, y, pixel, d)
    px_per_pt = x.shape[0] / MIMIMO_PT
    rgb = bleed(rim_light(rgb, d / UNITS_PER_PT, px_per_pt, *MIMIMO_RIM), d < 0)
    frames = {}
    for expression in ("open", "happy", "blink"):
        paint = Decals(x, y, pixel)
        paint_face(paint, expression)
        a = paint.alpha[..., None]
        frames[expression] = downsample(rgb * (1 - a) + paint.rgb * a, coverage)
    return frames


def render_tail():
    x, y = canvas_grid(TAIL_BOX, TAIL_SIZE, SUPERSAMPLE)
    d = sd_figure(x, y)
    rgb, coverage = render_surface(x, y, TAIL_PX / SUPERSAMPLE, d)
    px, py = (x - TAIL_BOX[0]) / TAIL_PX, (y - TAIL_BOX[1]) / TAIL_PX
    outside, _ = tail_shape(px, py)
    # Keep the tail and the body on its outer side, so the join is the same
    # surface in both sprites. Nothing extra is kept on the side that lies
    # over the glass, and the root fades in from the top.
    cx, cy = TAIL_CORNER
    glass_side = np.where(py < cy, px > cx - TAIL_INNER - TAIL_ROOT_RADIUS, np.hypot(px - cx, py - cy) < TAIL_INNER + TAIL_RADIUS)
    keep = np.where(glass_side, 1 - smoothstep(0.0, 2.0, outside), 1 - smoothstep(36.0, 64.0, outside))
    keep = keep * smoothstep(0.0, 26.0, py)
    # The rim follows his real outline on the outer side and the tail's own
    # edge on the glass side.
    px_per_pt = x.shape[0] / TAIL_PT
    rim_d = np.where(glass_side, np.maximum(d, outside * TAIL_PX), d) / UNITS_PER_PT
    rgb = bleed(rim_light(rgb, rim_d, px_per_pt, *MIMIMO_RIM), rim_d < 0)
    return downsample(rgb, coverage * keep)


# --------------------------------------------------------------------------
# 莫莫
# --------------------------------------------------------------------------
#
# Drawn from flat shapes in canvas pixels, each part shaded as a soft matte
# pillow under the key light. Her face is laid out in a frame that turns with
# her head: u along the eye line, v down the face, origin between the eyes.
# The face follows the owner's render (assets/brand/cats/momo_reference.png)
# and the design mockup: bean eyes, a bold ω, three bold whiskers, no nose,
# all on a flat pale patch.

CREAM = np.array([0.990, 0.948, 0.910])
POINTS_COCOA = np.array([0.470, 0.325, 0.255])  # ears and paws
PATCH = np.array([0.900, 0.822, 0.780])         # flat pale patch behind eyes and mouth
EAR_RIM = np.array([0.600, 0.440, 0.360])
EAR_HOLLOW = np.array([0.500, 0.330, 0.295])
BLUSH = np.array([0.975, 0.660, 0.640])
TEAL = np.array([0.105, 0.235, 0.290])          # bean eyes
INK = np.array([0.205, 0.145, 0.115])           # mouth, whiskers, closed eyes

MOMO_EYE_LINE = 0.5003                          # fraction of her height; clients seat her by it
MOMO_TILT = np.radians(11.0)                    # her head tips down to the viewer's right
MOMO_ORIGIN = (228.0, MOMO_EYE_LINE * MOMO_SIZE[1])
CANVAS_PER_PT = MOMO_SIZE[1] / MOMO_PT          # canvas pixels per point

# Shapes in the face frame (canvas pixels).
MOMO_HEAD = ((36.0, 24.0), (255.0, 186.0), 2.2)        # centre, radii, superellipse power
MOMO_EARS = (((-100.0, -112.0), (-122.0, -203.0), 78.0, 30.0),   # base, tip, base radius, tip radius
             ((136.0, -116.0), (186.0, -214.0), 76.0, 30.0))
MOMO_EAR_RIM = 13.0
MOMO_EYES = ((-80.0, 0.0), (80.0, 0.0))
MOMO_EYE_RADII = (16.5, 26.5)
MOMO_PATCH = ((0.0, 15.0), (118.0, 70.0))               # centre, radii
MOMO_MOUTH = (0.0, 14.0, 34.0, 17.0, 5.2)               # top centre, half width, depth, stroke radius
MOMO_STROKE = 4.9                                       # radius of whiskers and closed eyes
MOMO_WHISKERS = (((152.0, 14.0), (206.0, 20.0), (262.0, 38.0)),
                 ((148.0, 44.0), (200.0, 54.0), (252.0, 76.0)),
                 ((142.0, 74.0), (190.0, 88.0), (234.0, 114.0)))
# Faint blush where the iOS and Android clients put their petting blush
# (fractions of her canvas).
MOMO_BLUSH = ((0.23, 0.595), (0.585, 0.672))
MOMO_PAWS = (((-50.0, 184.0), (46.0, 32.0)), ((48.0, 186.0), (46.0, 32.0)))

MOMO_HEAD_PILLOW = (0.80, 0.52)
MOMO_HEAD_LIGHT = dict(ambient=0.69, key=0.39, fill=0.03, glow=0.05)
MOMO_POINT_PILLOW = (0.70, 0.52)
MOMO_POINT_LIGHT = dict(ambient=0.70, key=0.40, fill=0.05, glow=0.12)
MOMO_SHADE_TINT = np.array([1.0, 0.94, 0.90])   # colour of the unlit side relative to the lit side
CHIN_SHADOW = (0.15, 3.5)                       # form shadow under her chin: strength, width (points)
PAW_SHADOW = (0.22, 1.2)                        # and under her paws


def over(rgb, color, a):
    """Paint `color` over `rgb` with coverage `a`."""
    a = np.clip(a, 0, 1)[..., None]
    return rgb * (1 - a) + np.asarray(color) * a


def face_frame(x, y):
    """Canvas pixels to 莫莫's face frame."""
    c, s = np.cos(MOMO_TILT), np.sin(MOMO_TILT)
    dx, dy = x - MOMO_ORIGIN[0], y - MOMO_ORIGIN[1]
    return dx * c + dy * s, -dx * s + dy * c


def sd_momo_ear(u, v, ear):
    base, tip, r1, r2 = ear
    r, t, length = axis_coords(u, v, base, tip)
    return sd_uneven_capsule(r, t, r1, r2, length)


def inflate(mask, px_per_pt, scale=0.25):
    """Smooth balloon filling `mask`.

    Solves  -Laplace(u) = 1  inside the mask with u = 0 on its edge, on a
    reduced grid, and returns u in square points. A disc of radius R gives
    (R^2 - r^2) / 4. The result has no creases, even in thin parts like ears.
    """
    small = ndimage.zoom(mask.astype(np.float64), scale, order=1) > 0.5
    n = int(small.sum())
    index = -np.ones(small.shape, dtype=np.int64)
    index[small] = np.arange(n)
    ys, xs = np.nonzero(small)
    rows, cols, vals = [np.arange(n)], [np.arange(n)], [np.full(n, 4.0)]
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ny, nx = ys + dy, xs + dx
        ok = (ny >= 0) & (ny < small.shape[0]) & (nx >= 0) & (nx < small.shape[1])
        ok[ok] &= small[ny[ok], nx[ok]]
        rows.append(np.arange(n)[ok])
        cols.append(index[ny[ok], nx[ok]])
        vals.append(np.full(int(ok.sum()), -1.0))
    a = csr_matrix((np.concatenate(vals), (np.concatenate(rows), np.concatenate(cols))), shape=(n, n))
    u_small = np.zeros(small.shape)
    u_small[small] = spsolve(a, np.ones(n))
    u = ndimage.zoom(u_small, (mask.shape[0] / small.shape[0], mask.shape[1] / small.shape[1]), order=3)
    u = ndimage.gaussian_filter(np.clip(u, 0, None), 0.6 / scale)
    cell = 1.0 / (scale * px_per_pt)  # one reduced cell in points
    return u * cell * cell * mask


PILLOW_RADIUS = 20.0   # radius, in points, at which a pillow is `flat` times as deep as it is wide


def pillow_normals(mask, px_per_pt, flat=0.42, puff=0.6):
    """Unit normals of a soft pillow over `mask`. Wide parts rise higher than
    thin ones. `puff` 0.5 gives a ball-like edge; larger is gentler."""
    u = inflate(mask, px_per_pt)
    h = flat * PILLOW_RADIUS * (4 * u / PILLOW_RADIUS ** 2) ** puff
    gy, gx = np.gradient(h)
    gx, gy = gx * px_per_pt, gy * px_per_pt
    nz = 1.0 / np.sqrt(1 + gx * gx + gy * gy)
    return -gx * nz, -gy * nz, nz


def shade_matte(n, d, albedo, *, ambient, key, wrap=0.5, fill=0.0, glow=0.0, glow_width=2.2,
                glow_color=(1.0, 0.95, 0.91), tint=(1.0, 1.0, 1.0)):
    """Matte paint under the key light, in sRGB. No highlight.

    `n` are surface normals, `d` the signed distance to the outline in points.
    `fill` lifts slopes that face the light. `glow` is the soft light a matte
    surface catches along edges that face the light, fading over `glow_width`
    points. `tint` colours the unlit side.
    """
    nx, ny, nz = n
    ndl = nx * KEY[0] + ny * KEY[1] + nz * KEY[2]
    diffuse = np.clip((ndl + wrap) / (1 + wrap), 0, 1)
    rgb = albedo * (ambient + key * diffuse)[..., None]
    rgb = rgb * (np.asarray(tint) + (1 - np.asarray(tint)) * diffuse[..., None])
    tilt = np.hypot(nx, ny)
    side = (nx * KEY[0] + ny * KEY[1]) / (np.maximum(tilt, 1e-6) * np.hypot(KEY[0], KEY[1]))
    lit = smoothstep(-0.55, 0.95, side)
    extra = fill * tilt * lit + glow * np.exp(-np.clip(-d, 0, None) / glow_width) * (0.10 + 0.90 * lit)
    return rgb + extra[..., None] * np.array(glow_color)


def shoulder(rgb, knee=0.90):
    """Ease bright values toward 1 without changing their hue, so lit cream does not clip to white."""
    peak = rgb.max(axis=-1, keepdims=True)
    eased = knee + (1 - knee) * (1 - np.exp(-np.clip(peak - knee, 0, None) / (1 - knee)))
    return rgb * np.where(peak > knee, eased / np.maximum(peak, 1e-6), 1.0)


def momo_part(d, albedo, pillow, light, ppt):
    """Shade one part whose outline is the zero level of `d` (canvas pixels)."""
    n = pillow_normals(d < 0, ppt, *pillow)
    return shade_matte(n, d / CANVAS_PER_PT, albedo, tint=MOMO_SHADE_TINT, **light)


def under_shadow(rgb, d, strength, width):
    """Soft warm form shadow along the downward-facing edge of a part
    (`d` in canvas pixels, `width` in points), where it turns under."""
    gy, gx = np.gradient(d)
    down = smoothstep(0.1, 0.9, gy / np.maximum(np.hypot(gx, gy), 1e-9))
    under = down * np.exp(-np.clip(-d, 0, None) / (width * CANVAS_PER_PT))
    return rgb * (1 - strength * under[..., None] * np.array([0.85, 1.0, 1.1]))


def momo_body():
    """Silhouette, shading and the parts shared by all three frames."""
    x, y = canvas_grid((0, 0) + MOMO_SIZE, MOMO_SIZE, MOMO_SUPERSAMPLE)
    u, v = face_frame(x, y)
    ppt = x.shape[0] / MOMO_PT                       # work pixels per point

    def cover(d):
        return np.clip(0.5 - d * MOMO_SUPERSAMPLE, 0, 1)

    (hu, hv), radii, power = MOMO_HEAD
    d_head = sd_superellipse_2d(u - hu, v - hv, radii, power)
    d_ears = [sd_momo_ear(u, v, ear) for ear in MOMO_EARS]
    d_paws = [sd_ellipse(u, v, cu, cv, ru, rv) for (cu, cv), (ru, rv) in MOMO_PAWS]
    d_all = np.minimum.reduce([d_head] + d_ears + d_paws)
    alpha = cover(d_all)

    # Ears, behind the head: a lighter cocoa rim round a darker hollow. The
    # hollow is set into the ear, so its wall on the lit side is in shadow.
    rgb = np.zeros(x.shape + (3,))
    for d in d_ears:
        depth = -d - MOMO_EAR_RIM
        hollow = smoothstep(-4.0, 4.0, depth)
        gy, gx = np.gradient(d)
        facing = (gx * KEY[0] + gy * KEY[1]) / (np.maximum(np.hypot(gx, gy), 1e-9) * np.hypot(KEY[0], KEY[1]))
        wall = hollow * np.exp(-np.clip(depth, 0, None) / 9.0) * smoothstep(-0.3, 0.8, facing)
        albedo = over(np.broadcast_to(EAR_RIM, x.shape + (3,)), EAR_HOLLOW, hollow)
        albedo = albedo * (1 - 0.30 * wall)[..., None]
        ear = momo_part(d, albedo, MOMO_POINT_PILLOW, MOMO_POINT_LIGHT, ppt)
        rgb = over(rgb, ear, cover(d))
    rgb = rgb * (1 - 0.26 * np.exp(-np.clip(d_head, 0, None) / (1.4 * CANVAS_PER_PT)))[..., None]
    rgb = rim_light(rgb, d_all / CANVAS_PER_PT, ppt)

    # Head: cream, with a flat pale patch over the middle of the face.
    (pu, pv), (pru, prv) = MOMO_PATCH
    albedo = over(np.broadcast_to(CREAM, x.shape + (3,)), PATCH, cover(sd_ellipse(u, v, pu, pv, pru, prv)))
    for bx, by in MOMO_BLUSH:
        bu, bv = face_frame(bx * MOMO_SIZE[0], by * MOMO_SIZE[1])
        albedo = over(albedo, BLUSH, 0.30 * np.exp(-((u - bu) / 24.0) ** 2 - ((v - bv) / 13.0) ** 2))
    head = shoulder(momo_part(d_head, albedo, MOMO_HEAD_PILLOW, MOMO_HEAD_LIGHT, ppt))
    # Her chin turns under toward the glass she rests on: a soft form shadow
    # along the lower edge of the head.
    head = under_shadow(head, d_head, *CHIN_SHADOW)

    # A bold ω mouth with round ends; its outer ends curl up a little.
    cu, cv, half, depth, weight = MOMO_MOUTH
    t = np.linspace(-1, 1, 61)
    bowl = 2 * np.abs(t) - 1                      # -1..1 across each half of the ω
    mouth = list(zip(cu + half * t, cv + depth * (1 - bowl * bowl) ** 0.65 - 4.0 * t ** 6))
    w = stroke_window(u, v, mouth, weight + 4)
    head[w] = over(head[w], INK, cover(sd_stroke(u[w], v[w], mouth, [weight] * len(mouth))))

    # Three bold whiskers with round ends on the cheek at the right of the picture.
    for root, bend, tip in MOMO_WHISKERS:
        points, _ = quadratic(root, bend, tip)
        w = stroke_window(u, v, points, MOMO_STROKE + 4)
        head[w] = over(head[w], INK, cover(sd_stroke(u[w], v[w], points, [MOMO_STROKE] * len(points))))
    head = rim_light(head, d_head / CANVAS_PER_PT, ppt)
    rgb = over(rgb, head, cover(d_head))

    # Cocoa paws on the glass, in front of her chin, with a soft shadow on the face.
    for d in d_paws:
        shadow = np.exp(-np.clip(d + 2.0, 0, None) / (0.9 * CANVAS_PER_PT)) * (d > -1)
        rgb = rgb * (1 - 0.22 * shadow)[..., None]
        paw = momo_part(d, np.broadcast_to(POINTS_COCOA, x.shape + (3,)), MOMO_POINT_PILLOW, MOMO_POINT_LIGHT, ppt)
        paw = under_shadow(paw, d, *PAW_SHADOW)
        paw = rim_light(paw, d / CANVAS_PER_PT, ppt)
        rgb = over(rgb, paw, cover(d))
    # Toe lines: short, curving with the paw, stopping just inside its edge.
    for (pu, pv), (ru, rv) in MOMO_PAWS:
        for k in (-0.32, 0.32):
            toe, t = quadratic((pu + k * ru * 0.92, pv + rv * 0.84), (pu + k * ru * 1.06, pv + rv * 0.62),
                               (pu + k * ru * 1.02, pv + rv * 0.40), 12)
            w = stroke_window(u, v, toe, 8)
            rgb[w] = over(rgb[w], POINTS_COCOA * 0.55, cover(sd_stroke(u[w], v[w], toe, list(2.5 - 0.6 * t))))
    return x, u, v, rgb, alpha


def stroke_window(x, y, points, margin):
    """Slice of the work grid around a polyline, so a stroke skips the rest of the canvas."""
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    rows, cols = np.nonzero((x >= min(xs) - margin) & (x <= max(xs) + margin)
                            & (y >= min(ys) - margin) & (y <= max(ys) + margin))
    return np.s_[rows.min():rows.max() + 1, cols.min():cols.max() + 1]


def paint_momo_eye(rgb, u, v, centre, expression):
    cu, cv = centre
    ru, rv = MOMO_EYE_RADII
    w = stroke_window(u, v, [(cu - ru, cv - rv), (cu + ru, cv + rv)], 12)
    u, v = u[w], v[w]

    def cover(d):
        return np.clip(0.5 - d * MOMO_SUPERSAMPLE, 0, 1)

    if expression == "open":
        # A bean: one solid dark oval, nothing inside it.
        rgb[w] = over(rgb[w], TEAL, cover(sd_ellipse(u, v, cu, cv, ru, rv)))
        return
    # Closed: one bold arc, ^ when happy, a soft curve down when blinking.
    span = ru * 1.45
    rise, base = (-0.50, 0.30) if expression == "happy" else (0.26, -0.02)
    t = np.linspace(-1, 1, 33)
    points = list(zip(cu + span * t, cv + span * (base + rise * (1 - t * t))))
    rgb[w] = over(rgb[w], INK, cover(sd_stroke(u, v, points, [MOMO_STROKE] * len(points))))


def render_momo_frames():
    x, u, v, body, alpha = momo_body()
    frames = {}
    for expression in ("open", "happy", "blink"):
        rgb = body.copy()
        for centre in MOMO_EYES:
            paint_momo_eye(rgb, u, v, centre, expression)
        frames[expression] = downsample(np.clip(rgb, 0, 1), alpha, MOMO_SUPERSAMPLE)
    return frames

# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------

def scaled(img, height):
    w = max(1, round(img.width * height / img.height))
    return img.resize((w, height), Image.LANCZOS)


def blurred(img, radius):
    """A frosted copy for clients without a live backdrop (Android, Windows)."""
    pad = int(radius * 3)
    canvas = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    canvas.paste(img, (pad, pad))
    arr = np.array(canvas).astype(np.float64) / 255
    a = arr[..., 3:4]
    premult = arr[..., :3] * a
    premult = np.stack([ndimage.gaussian_filter(premult[..., c], radius) for c in range(3)], axis=-1)
    a = ndimage.gaussian_filter(a[..., 0], radius)[..., None]
    rgb = np.where(a > 1e-4, premult / np.maximum(a, 1e-4), 0)
    out = np.concatenate([rgb, a], axis=-1)
    return Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)).crop((pad, pad, pad + img.width, pad + img.height))


def save_imageset(directory, name, img, point_height):
    folder = os.path.join(directory, name + ".imageset")
    os.makedirs(folder, exist_ok=True)
    for f in os.listdir(folder):
        os.remove(os.path.join(folder, f))
    images = []
    for scale in (2, 3):
        file = f"{name}@{scale}x.png"
        scaled(img, point_height * scale).save(os.path.join(folder, file), optimize=True)
        images.append({"filename": file, "idiom": "universal", "scale": f"{scale}x"})
    with open(os.path.join(folder, "Contents.json"), "w") as f:
        json.dump({"images": images, "info": {"author": "xcode", "version": 1},
                   "properties": {"preserves-vector-representation": False}}, f, indent=2)


def render_masters():
    masters = {}
    for expression, img in render_mimimo_frames().items():
        masters[f"mimimo_{expression}"] = img
    masters["mimimo_tail"] = render_tail()
    for expression, img in render_momo_frames().items():
        masters[f"momo_{expression}"] = img
    return masters


TOLERANCE = 0.004  # of the sprite size; clients place the cats by these fractions


def verify(masters):
    """Stop if the art has drifted from what client code assumes: canvas
    sizes, the anchors in cats.json, and frames that differ only at the eyes."""
    sizes = {"mimimo": MIMIMO_SIZE, "momo": MOMO_SIZE, "mimimo_tail": TAIL_SIZE}
    for name, img in masters.items():
        expected = sizes["mimimo_tail" if name == "mimimo_tail" else name.split("_")[0]]
        if img.size != expected or img.mode != "RGBA":
            raise SystemExit(f"{name}: expected RGBA {expected}, got {img.mode} {img.size}")

    x0, y0, x1, y1 = MIMIMO_BOX
    checks = []  # label, measured fraction, expected fraction

    # 米米莫's outline.
    m = np.array(masters["mimimo_open"]).astype(int)
    h, w = m.shape[:2]
    solid = m[..., 3] > 127
    rows = y0 + (np.arange(h) + 0.5) * (y1 - y0) / h
    face = np.nonzero(solid[(rows > 42) & (rows < 52)].any(axis=0))[0]
    rump = np.nonzero(solid[(rows > 66) & (rows < 88)].any(axis=0))[0]
    checks.append(("米米莫 face right edge", (face.max() + 1) / w, (FACE_RIGHT_X - x0) / (x1 - x0)))
    checks.append(("米米莫 body left edge", rump.min() / w, (BODY_LEFT_X - x0) / (x1 - x0)))

    # His eyes: the cream iris pixels (whiskers are white, so r - b separates them).
    r, b = m[..., 0], m[..., 2]
    ys, xs = np.nonzero((r > 170) & (r - b > 50) & (m[..., 3] > 250))
    checks.append(("米米莫 eye bottom", (ys.max() + 1) / h, (EYE_BOTTOM_Y - y0) / (y1 - y0)))
    left = xs < 0.6 * w
    for label, part, anchor in (("left", xs[left], EYE_X[0]), ("right", xs[~left], EYE_X[1])):
        checks.append((f"米米莫 {label} eye centre", (part.min() + part.max() + 1) / 2 / w, (anchor - x0) / (x1 - x0)))
    checks.append(("米米莫 eye line", ys[left].min() / h, (EYE_LINE_Y - y0) / (y1 - y0)))

    # His nose: the darkest spot near the anchor.
    nx, ny = int((NOSE[0] - x0) / (x1 - x0) * w), int((NOSE[1] - y0) / (y1 - y0) * h)
    spot = m[ny - 18:ny + 19, nx - 18:nx + 19, :3].max(axis=2)
    sy, sx = np.nonzero(spot < (spot.min() + np.median(spot)) / 2)
    checks.append(("米米莫 nose x", (sx.mean() + nx - 18 + 0.5) / w, (NOSE[0] - x0) / (x1 - x0)))
    checks.append(("米米莫 nose y", (sy.mean() + ny - 18 + 0.5) / h, (NOSE[1] - y0) / (y1 - y0)))

    # 莫莫's eye line: the middle of her two eyes.
    m = np.array(masters["momo_open"]).astype(int)
    blue = (m[..., 2] > m[..., 0] + 30) & (m[..., 0] < 90) & (m[..., 3] > 250)
    labels, n = ndimage.label(blue)
    biggest = np.argsort(ndimage.sum(blue, labels, range(1, n + 1)))[::-1][:2] + 1
    middles = [(np.nonzero(labels == i)[0].min() + np.nonzero(labels == i)[0].max() + 1) / 2 for i in biggest]
    checks.append(("莫莫 eye line", np.mean(middles) / m.shape[0], MOMO_EYE_LINE))

    for label, found, wanted in checks:
        if abs(found - wanted) > TOLERANCE:
            raise SystemExit(f"{label} is at {found:.4f} of the sprite, expected {wanted:.4f}")

    # Frames of one cat may differ only around the eyes.
    for cat, (left, top, right, bottom) in (("mimimo", (0.28, 0.33, 0.90, 0.60)), ("momo", (0.15, 0.36, 0.72, 0.66))):
        first = np.array(masters[f"{cat}_open"]).astype(int)
        h, w = first.shape[:2]
        for expression in ("happy", "blink"):
            diff = np.abs(np.array(masters[f"{cat}_{expression}"]).astype(int) - first).max(axis=2)
            diff[int(top * h):int(bottom * h), int(left * w):int(right * w)] = 0
            if diff.max() > 0:
                raise SystemExit(f"{cat}_{expression} differs from {cat}_open outside the eyes")

    # The tail must not be cut by its canvas at the bottom.
    if np.array(masters["mimimo_tail"])[-1, :, 3].max() > 0:
        raise SystemExit("mimimo_tail touches the bottom of its canvas")
    return checks


def main():
    parser = argparse.ArgumentParser(description="Render 米米莫 and 莫莫 for every Meowpuut client.")
    parser.add_argument("--root", default=ROOT, help="repo root (default: the parent of this script's directory)")
    parser.add_argument("--masters", metavar="DIR", help="write only the masters and cats.json into DIR")
    args = parser.parse_args()
    set_root(args.root)

    masters = render_masters()
    for label, found, wanted in verify(masters):
        print(f"  {label}: {found:.4f} (anchor {wanted:.4f})")
    target = args.masters or CATS
    os.makedirs(target, exist_ok=True)
    for name, img in masters.items():
        img.save(os.path.join(target, name + ".png"), optimize=True)
    write_metrics(target)
    if args.masters:
        print("masters written to", target)
        return
    export_platforms(masters)
    export_festivals()
    print("cats rendered for iOS, macOS, Android and Windows")


# Nominal point heights. Every client sizes the sprites at run time from its
# own bar height, so these only set the pixel budget. The largest use is the
# widest phones and the iPad: 71.3, 62.1 and 32.2 pt (iOS KeyboardGeometry).
POINTS = {"mimimo": 72, "momo": 63, "mimimo_tail": 33}
# Blur of the *_frost copies, in pixels of a 3x export.
FROST_RADIUS = {"mimimo": 7.9, "momo": 6.8, "mimimo_tail": 4.5}


def nominal(name):
    return POINTS["mimimo_tail" if name == "mimimo_tail" else name.split("_")[0]]


def write_metrics(directory):
    """Anchor fractions every client uses to seat the cats on the glass.

    They are written from the layout constants at the top of this file (and
    莫莫's eye line from her reference render), not measured from the output.
    verify() checks that the rendered art agrees with them.
    """
    x0, y0, x1, y1 = MIMIMO_BOX

    def frac_x(units):
        return round((units - x0) / (x1 - x0), 4)

    def frac_y(units):
        return round((units - y0) / (y1 - y0), 4)

    metrics = {
        "mimimo": {
            "aspect": round((x1 - x0) / (y1 - y0), 4),
            "eyeLineY": frac_y(EYE_LINE_Y),
            "eyeBottomY": frac_y(EYE_BOTTOM_Y),
            "eyeLeftX": frac_x(EYE_X[0]),
            "eyeRightX": frac_x(EYE_X[1]),
            "noseX": frac_x(NOSE[0]),
            "noseY": frac_y(NOSE[1]),
            "faceRightX": frac_x(FACE_RIGHT_X),
            "bodyLeftX": frac_x(BODY_LEFT_X),
        },
        "momo": {
            "aspect": round(MOMO_ASPECT, 4),
            "eyeLineY": MOMO_EYE_LINE,
        },
        "mimimo_tail": {
            "aspect": round(TAIL_ASPECT, 4),
        },
    }
    with open(os.path.join(directory, "cats.json"), "w") as f:
        json.dump(metrics, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(json.dumps(metrics, ensure_ascii=False))


def festival_targets():
    # client directory, pixel scale over the nominal point height
    return {
        "ios": (os.path.join(ROOT, "iOS", "Festivals"), 3),
        "macos": (os.path.join(ROOT, "Resources", "Festivals"), 2),
        "android": (os.path.join(ROOT, "Android", "app", "src", "main", "assets", "festivals"), 4),
        "windows": (os.path.join(ROOT, "Windows", "UI", "Assets", "festivals"), 4),
    }


def check_manifest(manifest):
    """Stop on a manifest the clients would skip without saying why.

    Clients drop an outfit they cannot read and show the plain cats, so a
    typo in a date would only be noticed on the holiday itself.
    """
    import datetime
    import re

    def stop(where, problem):
        raise SystemExit(f"assets/festivals/festivals.json: {where}: {problem}")

    if manifest.get("version") != 1:
        stop("version", "expected 1")
    outfits = manifest.get("outfits")
    if not isinstance(outfits, list):
        stop("outfits", "expected a list (an empty list is fine)")
    seen = set()
    for index, outfit in enumerate(outfits):
        where = f"outfits[{index}]"
        if not isinstance(outfit, dict):
            stop(where, "expected an object")
        ident = outfit.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", ident):
            stop(where, "id must be lower-case letters, digits and hyphens")
        where = ident
        if ident in seen:
            stop(where, "id is used twice")
        seen.add(ident)
        title = outfit.get("title")
        if not isinstance(title, dict) or not title.get("hant") or not title.get("hans"):
            stop(where, "title needs hant and hans")
        days = []
        for key in ("start", "end"):
            value = outfit.get(key)
            if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                stop(where, f"{key} must be yyyy-MM-dd")
            try:
                days.append(datetime.date.fromisoformat(value))
            except ValueError:
                stop(where, f"{key} is not a real date: {value}")
        if days[0] > days[1]:
            stop(where, "start is after end")
        cats = outfit.get("cats")
        if not isinstance(cats, dict) or not cats:
            stop(where, "cats needs mimimo, momo or both")
        for cat, layers in cats.items():
            if cat not in ("mimimo", "momo"):
                stop(where, f"unknown cat {cat}")
            if not isinstance(layers, dict):
                stop(where, f"{cat} must be an object")
            frames = layers.get("frames", {})
            if not isinstance(frames, dict) or any(k not in ("open", "happy", "blink") for k in frames):
                stop(where, f"{cat}.frames may hold open, happy and blink")
            names = [layers.get("front"), layers.get("back")] + list(frames.values())
            names = [name for name in names if name is not None]
            if not names:
                stop(where, f"{cat} has no image")
            for name in names:
                if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\.png", name):
                    stop(where, f"{cat}: file names are letters, digits, _ and - with .png, got {name!r}")
        pill = outfit.get("pill")
        if pill is not None and (not isinstance(pill, str) or not re.fullmatch(r"#[0-9A-Fa-f]{6}", pill)):
            stop(where, "pill must be #RRGGBB")


def export_festivals():
    """Copy holiday outfits (docs/FESTIVALS.md) to every client.

    Each outfit image is drawn on the master canvas of its cat (the same
    pixel size as assets/brand/cats/<cat>_open.png), so clients place it
    exactly over or behind the sprite without extra anchors.
    """
    import shutil
    with open(os.path.join(FESTIVALS, "festivals.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    check_manifest(manifest)
    for directory, scale in festival_targets().values():
        if os.path.isdir(directory):
            shutil.rmtree(directory)
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, "festivals.json"), "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
            f.write("\n")
        for outfit in manifest.get("outfits", []):
            source = os.path.join(FESTIVALS, outfit["id"])
            target = os.path.join(directory, outfit["id"])
            os.makedirs(target, exist_ok=True)
            for cat, layers in outfit.get("cats", {}).items():
                master = Image.open(os.path.join(CATS, cat + "_open.png"))
                names = [layers.get("front"), layers.get("back")] + list(layers.get("frames", {}).values())
                for name in filter(None, names):
                    img = Image.open(os.path.join(source, name)).convert("RGBA")
                    if img.size != master.size:
                        raise SystemExit(f"{outfit['id']}/{name}: expected {master.size}, got {img.size}")
                    scaled(img, POINTS[cat] * scale).save(os.path.join(target, name), optimize=True)


def export_platforms(masters):
    # iOS keyboard: asset catalog image sets.
    xcassets = os.path.join(ROOT, "iOS", "Keyboard", "Cats.xcassets")
    os.makedirs(xcassets, exist_ok=True)
    keep = set()
    for name, img in masters.items():
        save_imageset(xcassets, name, img, nominal(name))
        keep.add(name + ".imageset")
    for entry in os.listdir(xcassets):
        if entry.endswith(".imageset") and entry not in keep:
            folder = os.path.join(xcassets, entry)
            for f in os.listdir(folder):
                os.remove(os.path.join(folder, f))
            os.rmdir(folder)

    # macOS: loose @1x/@2x PNGs copied into the app bundle by build.sh.
    mac = os.path.join(ROOT, "Resources", "Cats")
    os.makedirs(mac, exist_ok=True)
    for name, img in masters.items():
        scaled(img, nominal(name)).save(os.path.join(mac, name + ".png"), optimize=True)
        scaled(img, nominal(name) * 2).save(os.path.join(mac, name + "@2x.png"), optimize=True)

    # Android: one high-density bitmap per frame (drawable-nodpi), plus a
    # frosted copy for the part of a cat that sits behind the glass.
    android = os.path.join(ROOT, "Android", "app", "src", "main", "res", "drawable-nodpi")
    os.makedirs(android, exist_ok=True)
    for name, img in masters.items():
        px = nominal(name) * 4
        big = scaled(img, px)
        big.save(os.path.join(android, "cat_" + name + ".png"), optimize=True)
        if name in ("mimimo_open", "momo_open"):
            blurred(big, FROST_RADIUS[name.split("_")[0]] * 4 / 3).save(
                os.path.join(android, "cat_" + name.split("_")[0] + "_frost.png"), optimize=True)

    # Windows: 4x bitmaps; Direct2D scales them per monitor DPI.
    windows = os.path.join(ROOT, "Windows", "UI", "Assets")
    os.makedirs(windows, exist_ok=True)
    for name, img in masters.items():
        px = nominal(name) * 4
        big = scaled(img, px)
        big.save(os.path.join(windows, name + ".png"), optimize=True)
        if name in ("mimimo_open", "momo_open"):
            blurred(big, FROST_RADIUS[name.split("_")[0]] * 4 / 3).save(
                os.path.join(windows, name.split("_")[0] + "_frost.png"), optimize=True)


if __name__ == "__main__":
    main()
