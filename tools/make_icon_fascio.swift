// Platea icon "Fascio" (velvet and gold): adaptive launcher layers, legacy icons, TV banner, README icon.
// Usage: fascio <res_dir> <docs_dir> <preview.png>
import AppKit

let args = CommandLine.arguments
let resDir = args[1], docsDir = args[2], previewPath = args[3]

func c(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ivory = c(0xFFF4E2)

func render(_ w: Int, _ h: Int, _ draw: (CGContext) -> Void) -> CGImage {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // Work in a top-left origin, like the SVG design
    ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1)
    draw(ctx)
    return ctx.makeImage()!
}

func save(_ img: CGImage, _ path: String) {
    let url = URL(fileURLWithPath: path)
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let rep = NSBitmapImageRep(cgImage: img)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

func background(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
    let g = CGGradient(colorsSpace: space, colors: [c(0x6E1A28), c(0x1A080C)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: w, y: h), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
}

/// The mark in the 1024 design space; `glow` and `dust` only at large sizes.
func mark(_ ctx: CGContext, scale s: CGFloat, dx: CGFloat = 0, dy: CGFloat = 0, detail: Bool) {
    ctx.saveGState()
    ctx.translateBy(x: dx, y: dy); ctx.scaleBy(x: s, y: s)
    if detail {
        let glow = CGGradient(colorsSpace: space, colors: [c(0xFFE9C0, 0.5), c(0xFFE9C0, 0)] as CFArray, locations: [0, 1])!
        ctx.saveGState()
        ctx.translateBy(x: 660, y: 360); ctx.scaleBy(x: 1, y: 250.0 / 330.0)
        ctx.drawRadialGradient(glow, startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: 330, options: [])
        ctx.restoreGState()
    }
    // Beam: from the lens to the screen
    let beam = CGMutablePath()
    beam.move(to: CGPoint(x: 196, y: 812)); beam.addLine(to: CGPoint(x: 470, y: 214))
    beam.addLine(to: CGPoint(x: 860, y: 214)); beam.addLine(to: CGPoint(x: 860, y: 526)); beam.closeSubpath()
    ctx.saveGState(); ctx.addPath(beam); ctx.clip()
    let beamGrad = CGGradient(colorsSpace: space, colors: [c(0xF0CF8A, 0.95), c(0xD9A55B, 0.16)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(beamGrad, start: CGPoint(x: 120, y: 900), end: CGPoint(x: 900, y: 250), options: [])
    ctx.restoreGState()
    // Screen
    ctx.setFillColor(ivory)
    ctx.addPath(CGPath(roundedRect: CGRect(x: 470, y: 214, width: 390, height: 312), cornerWidth: 20, cornerHeight: 20, transform: nil)); ctx.fillPath()
    // Lens
    ctx.fillEllipse(in: CGRect(x: 196 - 50, y: 812 - 50, width: 100, height: 100))
    if detail {
        ctx.setStrokeColor(c(0xD9A55B, 0.65)); ctx.setLineWidth(10)
        ctx.strokeEllipse(in: CGRect(x: 196 - 80, y: 812 - 80, width: 160, height: 160))
        for (x, y, r, a) in [(350.0, 560.0, 7.0, 0.7), (430, 470, 5, 0.6), (300, 650, 4, 0.5)] {
            ctx.setFillColor(c(0xFFF4E2, a)); ctx.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
        }
    }
    ctx.restoreGState()
}

func roundedIcon(_ px: Int) -> CGImage {
    render(px, px) { ctx in
        let s = CGFloat(px)
        ctx.addPath(CGPath(roundedRect: CGRect(x: 0, y: 0, width: s, height: s), cornerWidth: s * 0.219, cornerHeight: s * 0.219, transform: nil))
        ctx.clip()
        background(ctx, s, s)
        mark(ctx, scale: s / 1024, detail: px >= 144)
    }
}

// Adaptive icon (108 dp): background = velvet, foreground = mark inside the 66 dp safe zone
for (dir, px) in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)] {
    let s = CGFloat(px)
    save(render(px, px) { background($0, s, s) }, "\(resDir)/mipmap-\(dir)/ic_launcher_background.png")
    let inner = s * 66 / 108
    save(render(px, px) { mark($0, scale: inner / 1024, dx: (s - inner) / 2, dy: (s - inner) / 2, detail: px >= 216) },
         "\(resDir)/mipmap-\(dir)/ic_launcher_foreground.png")
}
// Legacy icons (48 dp)
for (dir, px) in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)] {
    let img = roundedIcon(px)
    save(img, "\(resDir)/mipmap-\(dir)/ic_launcher.png")
    save(img, "\(resDir)/mipmap-\(dir)/ic_launcher_round.png")
}

// TV banner 16:9, full bleed (the launcher rounds the corners)
func banner(_ w: Int) -> CGImage {
    let h = w * 9 / 16
    return render(w, h) { ctx in
        let W = CGFloat(w), H = CGFloat(h), k = W / 480
        background(ctx, W, H)
        // Mark scaled from the 480x270 banner design: lens (38,236), screen 112..200 x 52..124
        mark(ctx, scale: k * 88 / 390, dx: k * 112 - k * 88 / 390 * 470, dy: k * 52 - k * 88 / 390 * 214, detail: false)
        let font = NSFont(name: "Figtree-ExtraBold", size: 64 * k) ?? NSFont.systemFont(ofSize: 62 * k, weight: .heavy)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(cgColor: ivory)!]
        let text = NSAttributedString(string: "Platea", attributes: attrs)
        NSGraphicsContext.saveGraphicsState()
        // Text needs the flipped context NSString drawing expects
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
        let size = text.size()
        text.draw(at: CGPoint(x: k * 226, y: (H - size.height) / 2 + k * 18))
        NSGraphicsContext.restoreGraphicsState()
    }
}
for (dir, w) in [("xhdpi", 320), ("xxhdpi", 480), ("xxxhdpi", 640)] {
    save(banner(w), "\(resDir)/mipmap-\(dir)/platea_banner.png")
}
save(banner(320), "\(resDir)/mipmap-xhdpi/ic_banner.png")

// README icon and banner
save(roundedIcon(512), "\(docsDir)/icon.png")
save(banner(640), "\(docsDir)/banner.png")

// Preview: banner, legacy icon, adaptive icon under round and squircle masks
let preview = render(1100, 420) { ctx in
    ctx.setFillColor(c(0x1E1E1E)); ctx.fill(CGRect(x: 0, y: 0, width: 1100, height: 420))
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: CGRect(x: 30, y: 30, width: 640, height: 360), cornerWidth: 22, cornerHeight: 22, transform: nil)); ctx.clip()
    ctx.translateBy(x: 30, y: 390); ctx.scaleBy(x: 1, y: -1)
    ctx.draw(banner(640), in: CGRect(x: 0, y: 0, width: 640, height: 360))
    ctx.restoreGState()
    for (i, round) in [false, true].enumerated() {
        let r = CGRect(x: 710 + CGFloat(i) * 190, y: 130, width: 160, height: 160)
        ctx.saveGState()
        ctx.addPath(round ? CGPath(ellipseIn: r, transform: nil) : CGPath(roundedRect: r, cornerWidth: 38, cornerHeight: 38, transform: nil)); ctx.clip()
        background(ctx, 1100, 420)
        let full = r.insetBy(dx: -r.width * 18 / 72, dy: -r.height * 18 / 72)
        let inner = full.width * 66 / 108
        mark(ctx, scale: inner / 1024, dx: full.minX + (full.width - inner) / 2, dy: full.minY + (full.height - inner) / 2, detail: true)
        ctx.restoreGState()
    }
}
save(preview, previewPath)
print("ok")
