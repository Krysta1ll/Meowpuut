import AppKit
import QuartzCore

// One small window per cat (PETS.md §2.5, §8.3): a borderless, non-activating, transparent panel above normal
// windows and the Dock (Dock level + 1; below menus, the menu bar and the candidate window), on every Space but not
// in full-screen Spaces (no .fullScreenAuxiliary), unaffected by Mission Control, never key or main.
//
// Clicks go through it (ignoresMouseEvents) until the pointer has rested on the cat's own opaque pixels for 250 ms
// (DeskController); hit tests use the picture's alpha, never the window's transparency, and the soft shadow never
// counts.
//
// Drawing is Core Animation: a body layer (the cut behind an edge is its mask) holding the sprite layer, whose
// contents are the clay picture and whose transform (about the paws) carries the core's squash, the tilt and the
// mirror. Changing pose changes only `contents`.

final class DeskPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

final class DeskCatView: NSView {
    let cat: DeskCat
    weak var controller: DeskController?

    init(cat: DeskCat, frame: NSRect) {
        self.cat = cat
        super.init(frame: frame)
        setAccessibilityElement(false)
    }

    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        trackingAreas.forEach(removeTrackingArea)
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseMoved, .mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                       owner: self, userInfo: nil))
    }

    override func mouseDown(with event: NSEvent) { controller?.mouseDown(cat, event) }
    override func mouseDragged(with event: NSEvent) { controller?.mouseDragged(cat) }
    override func mouseUp(with event: NSEvent) { controller?.mouseUp(cat) }
    override func rightMouseDown(with event: NSEvent) { controller?.rightMouseDown(cat, event, in: self) }
    override func mouseMoved(with event: NSEvent) { controller?.pointerMovedOverPanel(cat) }
    override func mouseExited(with event: NSEvent) { controller?.pointerMovedOverPanel(cat) }
}

final class CatPanel {
    /// Room for the largest picture (edge_lounge, side_hold), a tilt, a hop and the shadow.
    static let size = CGSize(width: 176, height: 168)
    /// Where the core's x/y (between the paws) sits in the panel, from its bottom left.
    static let foot = CGPoint(x: 88, y: 48)

    let cat: DeskCat
    let panel: DeskPanel
    let view: DeskCatView
    private let root = CALayer()
    private let shadow = CAGradientLayer()
    private let body = CALayer()
    private let sprite = CALayer()
    private let mask = CAShapeLayer()

    private(set) var look: DeskLook?
    private(set) var picture: DeskArt.Picture?
    /// The visible part of the body (in the body layer), nil when the whole picture shows.
    private(set) var maskPath: CGPath?
    private(set) var shown = false
    /// The panel's origin in Cocoa screen coordinates.
    private(set) var origin = CGPoint(x: -10_000, y: -10_000)
    /// What the last layout drew from: an unchanged cat is not laid out again (only what changed is updated).
    private var drawn: (cat: [UInt8], look: String, k: CGFloat, motion: Bool, height: CGFloat)?
    /// Which walk frame shows, and the `turn` after a walk (DeskArt.swift).
    private(set) var gait: DeskGait
    /// FINAL §4.4: the vertical scale never goes under this.
    static let leastScaleY: CGFloat = 0.94

    init(cat: DeskCat) {
        self.cat = cat
        gait = DeskGait(length: DeskArt.cycleLength(cat))
        panel = DeskPanel(contentRect: NSRect(origin: origin, size: Self.size), styleMask: [.borderless, .nonactivatingPanel],
                          backing: .buffered, defer: true)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.dockWindow)) + 1)
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.ignoresMouseEvents = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.animationBehavior = .none
        panel.becomesKeyOnlyIfNeeded = true
        panel.isMovable = false
        panel.acceptsMouseMovedEvents = true
        panel.title = cat == .mimimo ? "米米莫" : "莫莫"
        view = DeskCatView(cat: cat, frame: NSRect(origin: .zero, size: Self.size))
        root.frame = view.bounds
        view.layer = root
        view.wantsLayer = true
        panel.contentView = view

        shadow.type = .radial
        shadow.colors = [NSColor(white: 0, alpha: 0.2).cgColor, NSColor(white: 0, alpha: 0).cgColor]
        shadow.startPoint = CGPoint(x: 0.5, y: 0.5)
        shadow.endPoint = CGPoint(x: 1, y: 1)
        shadow.isHidden = true
        root.addSublayer(shadow)
        sprite.contentsGravity = .resize
        sprite.minificationFilter = .trilinear
        body.addSublayer(sprite)
        mask.fillColor = NSColor.black.cgColor
        root.addSublayer(body)
    }

    struct Frame {
        var k: CGFloat = 1
        var motion = true
        /// The core is ticking at 12 Hz (an event in the last 20 s): breathe.
        var ticking = false
        var nowMs: Double = 0
        var primaryHeight: CGFloat
        /// The STEP events of the core step being drawn (one per key the walker stepped with); 0 for a redraw.
        var typingSteps = 0
    }

    /// Lays the cat out as the core says. False when there is nothing to draw (home, on the candidate glass, a
    /// picture missing); the caller hides the panel then.
    @discardableResult
    func layout(_ c: MPDeskCat, art: DeskArt, frame f: Frame) -> Bool {
        gait.update(c, typingSteps: f.typingSteps, nowMs: f.nowMs, motion: f.motion)
        guard let look = DeskLook.of(cat, c, gait: gait, nowMs: f.nowMs), let pic = art.picture(for: look) else { return false }
        let key = withUnsafeBytes(of: c) { Array($0) }
        let breathing = f.motion && (f.ticking || look.slowBreath)
        if !breathing, let drawn, drawn.cat == key, drawn.look == pic.sprite.name, drawn.k == f.k, drawn.motion == f.motion,
           drawn.height == f.primaryHeight {
            return true
        }
        drawn = (key, pic.sprite.name, f.k, f.motion, f.primaryHeight)
        let kk = f.k * (look.behind ? 0.86 : 1)
        let w = pic.sprite.size.width * kk, h = pic.sprite.size.height * kk
        let ax = pic.foot.x, ay = pic.foot.y
        // FINAL §4.4: never squashed more than 6 % (the core's hints already are; breathing must not add to it).
        var sx = CGFloat(c.scale_x), sy = CGFloat(c.scale_y), tilt = CGFloat(c.tilt)
        let lounge = pic.sprite.name.hasSuffix("lounge")
        // A stand-in (a fallback picture) faces as the front-facing poses do.
        let native: Int8 = lounge ? (cat == .mimimo ? -1 : 1) : pic.sprite.name == look.name ? look.native : 1
        let flip = (look.behind && !lounge) || look.side ? false : c.facing != native
        let asleep = c.flags & UInt32(MP_DESK_CAT_ASLEEP) != 0
        // The walk frames carry the step; a stand-in for them (no frames in the bundle) wobbles and lifts as before.
        let framed = look.walk && pic.sprite.name == look.name
        var dy: CGFloat = 0
        if f.motion {
            if look.walk && !framed {
                tilt += (c.phase != 0 ? 1 : -1) * (Int(c.pose) == MP_DESK_POSE_TROT ? 4 : 3)
                if c.flags & UInt32(MP_DESK_CAT_FRESH_STEP) != 0 { dy = -3 * kk }
            } else if f.ticking && !asleep && !look.held && !look.walk && sx == 1 && sy == 1 {
                sy *= 1 + 0.018 * CGFloat(sin(f.nowMs / 5200 * 2 * .pi + (cat == .mimimo ? 0 : 2)))
            }
            if look.slowBreath { sy *= 1 + 0.03 * CGFloat(sin(f.nowMs / 2600 * 2 * .pi)) }
        }
        sy = max(sy, Self.leastScaleY)
        let sink = CGFloat(c.sink) * (ay * h - pic.sprite.bounds.minY * h + 2)
        // Where the picture's anchor goes in the panel (the paws' point, or the scruff above it).
        var pin = Self.foot
        // Lifted: the core's x/y is where the paws would be; the scruff it hangs from is 0.82 of the body above them
        // (desk.rs drag), and `lift` hangs from its anchor there.
        let scruff = look.held && pic.sprite.name == look.name
        if scruff { pin.y += 0.82 * cat.body.height * f.k }

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if sprite.contents == nil || self.picture?.sprite.name != pic.sprite.name { sprite.contents = pic.image }
        body.frame = CGRect(x: pin.x - ax * w, y: pin.y - (h * (1 - ay) + dy + sink), width: w, height: h)
        // Turn about the paws; a lifted cat about the scruff (its stand-in about the neck).
        let pivot = look.held && !scruff ? CGPoint(x: 0.5, y: 0.75) : CGPoint(x: ax, y: 1 - ay)
        sprite.setAffineTransform(.identity)
        sprite.bounds = CGRect(x: 0, y: 0, width: w, height: h)
        sprite.anchorPoint = pivot
        sprite.position = CGPoint(x: pivot.x * w, y: pivot.y * h)
        // Mirror first, then the tilt (clockwise on screen, as the core gives it).
        sprite.setAffineTransform(CGAffineTransform(scaleX: flip ? -sx : sx, y: sy).concatenating(CGAffineTransform(rotationAngle: -tilt * .pi / 180)))

        // Behind an edge: only what is above the edge shows, and the paws over it (pets-view.js drawClay).
        if look.behind || c.sink > 0 {
            let edgeDown = ay * h - sink - dy, edgeUp = h - edgeDown
            let path = CGMutablePath()
            path.addRect(CGRect(x: -20, y: edgeUp, width: w + 40, height: h + 40 - edgeUp))
            if c.sink == 0 {
                for paw in pic.sprite.paws {
                    let cx = (flip ? 1 - paw.x : paw.x) * w, a = paw.halfWidth * w + 1
                    let low = h - (edgeDown + paw.depth * h + 1.5), high = h - (edgeDown - 1)
                    path.addRect(CGRect(x: cx - a, y: low, width: 2 * a, height: high - low))
                }
            }
            mask.frame = body.bounds
            mask.path = path
            body.mask = mask
            maskPath = path
        } else {
            body.mask = nil
            maskPath = nil
        }

        // A soft contact shadow under a cat standing on top of something (never part of a hit).
        let standing = Int(c.body) == MP_DESK_BODY_GROUND && Int(c.seat) == MP_DESK_SEAT_TOP && !look.side && !look.behind
        shadow.isHidden = !standing
        if standing {
            let sw = cat.body.width * f.k * 1.25, sh = 8 * f.k
            shadow.frame = CGRect(x: Self.foot.x - sw / 2, y: Self.foot.y + 1 - sh / 2, width: sw, height: sh)
        }
        CATransaction.commit()

        self.look = look
        picture = pic
        let next = CGPoint(x: (CGFloat(c.x) - Self.foot.x).rounded(), y: (f.primaryHeight - CGFloat(c.y) - Self.foot.y).rounded())
        if next != origin {
            origin = next
            panel.setFrameOrigin(next)
        }
        return true
    }

    func show() {
        guard !shown else { return }
        shown = true
        panel.alphaValue = 1
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0
        fade.toValue = 1
        fade.duration = 0.2
        root.add(fade, forKey: "fade")
        panel.orderFrontRegardless()
    }

    /// Fades out (0.12 s, FINAL.md §4.2) and leaves the screen.
    func hide(animated: Bool = true) {
        guard shown else { return }
        shown = false
        panel.ignoresMouseEvents = true
        guard animated else { panel.orderOut(nil); return }
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.12
            panel.animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            guard let self, !self.shown else { return }
            self.panel.orderOut(nil)
            self.panel.alphaValue = 1
        })
    }

    /// Whether a desktop point is on the cat's own opaque pixels as last laid out (transform and cut included).
    func opaque(atDesktop point: CGPoint, primaryHeight: CGFloat) -> Bool {
        guard let picture else { return false }
        let local = CGPoint(x: point.x - origin.x, y: primaryHeight - point.y - origin.y)
        guard root.bounds.contains(local) else { return false }
        if let maskPath, !maskPath.contains(body.convert(local, from: root)) { return false }
        let s = sprite.convert(local, from: root)
        let w = sprite.bounds.width, h = sprite.bounds.height
        guard w > 0, h > 0 else { return false }
        return picture.opaque(u: s.x / w, v: 1 - s.y / h)
    }

    /// The panel as drawn, for Desk/shots (CALayer.render(in:) leaves masks out, so this draws the same geometry
    /// itself: shadow, then the picture clipped by the cut).
    func snapshot(scale: CGFloat = 2) -> CGImage? {
        guard let picture else { return nil }
        let width = Int(Self.size.width * scale), height = Int(Self.size.height * scale)
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.scaleBy(x: scale, y: scale)
        if !shadow.isHidden {
            context.saveGState()
            context.translateBy(x: shadow.frame.minX, y: shadow.frame.minY)
            shadow.render(in: context)
            context.restoreGState()
        }
        context.saveGState()
        context.translateBy(x: body.frame.minX, y: body.frame.minY)
        if let maskPath {
            context.addPath(maskPath)
            context.clip()
        }
        let t = sprite.affineTransform()
        context.translateBy(x: sprite.position.x, y: sprite.position.y)
        context.concatenate(t)
        context.translateBy(x: -sprite.anchorPoint.x * sprite.bounds.width, y: -sprite.anchorPoint.y * sprite.bounds.height)
        context.interpolationQuality = .high
        context.draw(picture.image, in: sprite.bounds)
        context.restoreGState()
        return context.makeImage()
    }
}
