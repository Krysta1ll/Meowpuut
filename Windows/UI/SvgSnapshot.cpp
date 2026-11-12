#include "SvgSnapshot.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <sstream>

namespace meowpuut::ui {
namespace {

std::string num(float v) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.2f", static_cast<double>(v));
    std::string s(buf);
    while (!s.empty() && s.back() == '0') s.pop_back();
    if (!s.empty() && s.back() == '.') s.pop_back();
    return s == "-0" ? "0" : s;
}

std::string rgb(const Color &c) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "#%02X%02X%02X", static_cast<int>(std::lround(c.r * 255)),
                  static_cast<int>(std::lround(c.g * 255)), static_cast<int>(std::lround(c.b * 255)));
    return buf;
}

std::string escape(const std::string &text) {
    std::string out;
    for (char ch : text) {
        switch (ch) {
        case '&': out += "&amp;"; break;
        case '<': out += "&lt;"; break;
        case '>': out += "&gt;"; break;
        case '"': out += "&quot;"; break;
        default: out += ch;
        }
    }
    return out;
}

std::string pathData(const BezierPath &path) {
    std::ostringstream d;
    d << "M" << num(path.start.x) << " " << num(path.start.y);
    for (const auto &s : path.segments)
        d << "C" << num(s.c1.x) << " " << num(s.c1.y) << " " << num(s.c2.x) << " " << num(s.c2.y) << " "
          << num(s.end.x) << " " << num(s.end.y);
    d << "Z";
    return d.str();
}

std::string polyline(const std::vector<PointF> &pts) {
    std::ostringstream d;
    for (std::size_t i = 0; i < pts.size(); ++i) d << (i ? "L" : "M") << num(pts[i].x) << " " << num(pts[i].y);
    return d.str();
}

const std::string &spriteHref(const SvgAssets &assets, Sprite sprite) {
    return sprite == Sprite::Mimimo ? assets.mimimo : sprite == Sprite::Momo ? assets.momo : assets.ornament;
}

std::string matrix(const Affine &m) {
    return "matrix(" + num(m.a) + " " + num(m.b) + " " + num(m.c) + " " + num(m.d) + " " + num(m.e) + " " + num(m.f) + ")";
}

class Writer {
public:
    Writer(const Scene &scene, const SvgAssets &assets, const std::string &family)
        : scene_(scene), assets_(assets), family_(family) {}

    std::string run(float scale) {
        for (std::size_t i = 0; i < scene_.outlines.size(); ++i)
            defs_ << "<path id=\"o" << i << "\" d=\"" << pathData(scene_.outlines[i].path) << "\"/>\n";
        for (const auto &c : scene_.commands) command(c);
        std::ostringstream s;
        s << "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\""
          << num(scene_.size.width * scale) << "\" height=\"" << num(scene_.size.height * scale) << "\" viewBox=\"0 0 "
          << num(scene_.size.width) << " " << num(scene_.size.height) << "\">\n<defs>\n"
          << defs_.str() << "</defs>\n" << body_.str() << "</svg>\n";
        return s.str();
    }

private:
    const Scene &scene_;
    const SvgAssets &assets_;
    const std::string &family_;
    std::ostringstream defs_, body_;
    int nextId_ = 0;

    std::string stops(const Paint &p) {
        std::string out;
        for (const auto &stop : p.stops)
            out += "<stop offset=\"" + num(stop.offset) + "\" stop-color=\"" + rgb(stop.color) + "\" stop-opacity=\"" +
                   num(stop.color.a) + "\"/>";
        return out;
    }

    // fill="…" or stroke="…" for the paint; gradients go to <defs>.
    std::string paint(const char *attribute, const Paint &p) {
        const std::string name(attribute);
        if (p.kind == Paint::Kind::Solid)
            return name + "=\"" + rgb(p.color) + "\" " + name + "-opacity=\"" + num(p.color.a) + "\"";
        const std::string id = "g" + std::to_string(nextId_++);
        if (p.kind == Paint::Kind::Linear) {
            defs_ << "<linearGradient id=\"" << id << "\" gradientUnits=\"userSpaceOnUse\" x1=\"" << num(p.from.x)
                  << "\" y1=\"" << num(p.from.y) << "\" x2=\"" << num(p.to.x) << "\" y2=\"" << num(p.to.y) << "\">"
                  << stops(p) << "</linearGradient>\n";
        } else {
            // An elliptical gradient is a circular one squeezed vertically.
            const float rx = std::max(0.01f, p.to.x), ry = std::max(0.01f, p.to.y);
            defs_ << "<radialGradient id=\"" << id << "\" gradientUnits=\"userSpaceOnUse\" cx=\"" << num(p.from.x)
                  << "\" cy=\"" << num(p.from.y) << "\" r=\"" << num(rx) << "\" gradientTransform=\"translate("
                  << num(p.from.x) << " " << num(p.from.y) << ") scale(1 " << num(ry / rx) << ") translate("
                  << num(-p.from.x) << " " << num(-p.from.y) << ")\">" << stops(p) << "</radialGradient>\n";
        }
        return name + "=\"url(#" + id + ")\"";
    }

    // A Gaussian blur filter that covers the whole window.
    std::string blurFilter(float sigma) {
        const std::string id = "f" + std::to_string(nextId_++);
        defs_ << "<filter id=\"" << id << "\" filterUnits=\"userSpaceOnUse\" x=\"-40\" y=\"-40\" width=\""
              << num(scene_.size.width + 80) << "\" height=\"" << num(scene_.size.height + 80)
              << "\"><feGaussianBlur stdDeviation=\"" << num(sigma) << "\"/></filter>\n";
        return id;
    }

    // Blurs an image; with a tint, only its shape is kept, in the tint's colour.
    std::string imageFilter(const DrawCommand &c) {
        const std::string id = "f" + std::to_string(nextId_++);
        defs_ << "<filter id=\"" << id << "\" filterUnits=\"userSpaceOnUse\" x=\"-40\" y=\"-40\" width=\""
              << num(scene_.size.width + 80) << "\" height=\"" << num(scene_.size.height + 80) << "\">";
        if (c.tint.a > 0)
            defs_ << "<feFlood flood-color=\"" << rgb(c.tint) << "\"/><feComposite in2=\"SourceAlpha\" operator=\"in\"/>";
        if (c.blur > 0) defs_ << "<feGaussianBlur stdDeviation=\"" << num(c.blur) << "\"/>";
        defs_ << "</filter>\n";
        return id;
    }

    void command(const DrawCommand &c) {
        using Kind = DrawCommand::Kind;
        switch (c.kind) {
        case Kind::Shadow:
            body_ << "<g filter=\"url(#" << blurFilter(c.blur) << ")\"><use href=\"#o" << c.path << "\" "
                  << paint("fill", c.paint) << " transform=\"translate(0 " << num(c.offsetY) << ")\"/></g>\n";
            break;
        case Kind::FillPath:
            body_ << "<use href=\"#o" << c.path << "\" " << paint("fill", c.paint) << "/>\n";
            break;
        case Kind::StrokePath:
            body_ << "<use href=\"#o" << c.path << "\" fill=\"none\" " << paint("stroke", c.paint) << " stroke-width=\""
                  << num(c.width) << "\"/>\n";
            break;
        case Kind::PushClip: {
            const std::string id = "c" + std::to_string(nextId_++);
            defs_ << "<clipPath id=\"" << id << "\"><use href=\"#o" << c.path << "\"/></clipPath>\n";
            body_ << "<g clip-path=\"url(#" << id << ")\">\n";
            break;
        }
        case Kind::PushClipOut: {
            const std::string id = "c" + std::to_string(nextId_++);
            const float w = scene_.size.width, h = scene_.size.height;
            defs_ << "<clipPath id=\"" << id << "\"><path clip-rule=\"evenodd\" d=\"M-40 -40H" << num(w + 40) << "V"
                  << num(h + 40) << "H-40Z" << pathData(scene_.outlines[c.path].path) << "\"/></clipPath>\n";
            body_ << "<g clip-path=\"url(#" << id << ")\">\n";
            break;
        }
        case Kind::PushClipOvals: {
            const std::string id = "c" + std::to_string(nextId_++);
            defs_ << "<clipPath id=\"" << id << "\">";
            for (const RectF &o : c.ovals)
                defs_ << "<ellipse cx=\"" << num(o.midX()) << "\" cy=\"" << num(o.midY()) << "\" rx=\"" << num(o.width / 2)
                      << "\" ry=\"" << num(o.height / 2) << "\" transform=\"" << matrix(c.transform) << "\"/>";
            defs_ << "</clipPath>\n";
            body_ << "<g clip-path=\"url(#" << id << ")\">\n";
            break;
        }
        case Kind::PopClip:
            body_ << "</g>\n";
            break;
        case Kind::StrokeLine:
            body_ << "<path d=\"" << polyline(c.points) << "\" fill=\"none\" " << paint("stroke", c.paint)
                  << " stroke-width=\"" << num(c.width) << "\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n";
            break;
        case Kind::FillRoundRect:
            body_ << "<rect x=\"" << num(c.rect.x) << "\" y=\"" << num(c.rect.y) << "\" width=\"" << num(c.rect.width)
                  << "\" height=\"" << num(c.rect.height) << "\" rx=\"" << num(c.radius) << "\" "
                  << paint("fill", c.paint) << "/>\n";
            break;
        case Kind::FillEllipse:
            body_ << "<ellipse cx=\"" << num(c.rect.midX()) << "\" cy=\"" << num(c.rect.midY()) << "\" rx=\""
                  << num(c.rect.width / 2) << "\" ry=\"" << num(c.rect.height / 2) << "\" ";
            if (c.rotation != 0)
                body_ << "transform=\"rotate(" << num(c.rotation) << " " << num(c.rect.midX()) << " "
                      << num(c.rect.midY()) << ")\" ";
            body_ << paint("fill", c.paint) << "/>\n";
            break;
        case Kind::Image: {
            const std::string &href = spriteHref(assets_, c.sprite);
            if (href.empty()) break;
            body_ << "<g transform=\"" << matrix(c.transform) << "\">";
            if (c.blur > 0 || c.tint.a > 0) body_ << "<g filter=\"url(#" << imageFilter(c) << ")\">";
            body_ << "<image href=\"" << href << "\" x=\"" << num(c.rect.x) << "\" y=\"" << num(c.rect.y)
                  << "\" width=\"" << num(c.rect.width) << "\" height=\"" << num(c.rect.height)
                  << "\" preserveAspectRatio=\"none\" opacity=\"" << num(c.opacity) << "\"/>";
            if (c.blur > 0 || c.tint.a > 0) body_ << "</g>";
            body_ << "</g>\n";
            break;
        }
        case Kind::Text: {
            const char *anchor = c.align == TextAlign::Center ? "middle" : c.align == TextAlign::Trailing ? "end" : "start";
            body_ << "<text x=\"" << num(c.x) << "\" y=\"" << num(c.baseline) << "\" font-family=\"" << escape(family_)
                  << "\" font-size=\"" << num(c.size) << "\" font-weight=\"" << (c.medium ? 500 : 400)
                  << "\" text-anchor=\"" << anchor << "\" " << paint("fill", c.paint) << ">" << escape(c.text)
                  << "</text>\n";
            break;
        }
        }
    }
};

} // namespace

std::string renderSvg(const Scene &scene, const SvgAssets &assets, const std::string &fontFamily, float scale) {
    return Writer(scene, assets, fontFamily).run(scale);
}

} // namespace meowpuut::ui
