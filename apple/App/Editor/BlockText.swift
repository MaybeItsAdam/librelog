import Foundation
import SwiftUI

#if canImport(UIKit)
import UIKit
typealias PlatformFont = UIFont
#else
import AppKit
typealias PlatformFont = NSFont
#endif

/// The bridge between the core's block document (doc.rs) and the platform
/// text system.
///
/// Meaning lives in custom attributes (`dl.*`); fonts and colours are derived
/// from them by `restyle`. That way bold-in-a-heading or italic-in-a-quote
/// round-trips exactly, which reading font traits back could not do.
///
/// One block = one paragraph. List markers ("• ", "1. ", "☐ ") are real
/// characters tagged `dl.marker`, so the caret skips naturally and they are
/// dropped on the way back to blocks. Mark offsets are UTF-16, the same unit
/// NSString ranges use, so they map one to one.
enum BlockText {
    static let kind = NSAttributedString.Key("dl.kind")         // String, see encode(_:)
    static let indent = NSAttributedString.Key("dl.indent")     // Int
    static let marker = NSAttributedString.Key("dl.marker")     // Bool
    static let bold = NSAttributedString.Key("dl.bold")
    static let italic = NSAttributedString.Key("dl.italic")
    static let underline = NSAttributedString.Key("dl.underline")
    static let strike = NSAttributedString.Key("dl.strike")
    static let highlight = NSAttributedString.Key("dl.highlight")
    static let code = NSAttributedString.Key("dl.code")
    static let entryLink = NSAttributedString.Key("dl.entryLink") // String entry id

    static let flagKeys: [(NSAttributedString.Key, MarkKind)] = [
        (bold, .bold), (italic, .italic), (underline, .underline),
        (strike, .strike), (highlight, .highlight), (code, .code),
    ]

    // MARK: Blocks → attributed string

    static func attributed(_ blocks: [Block]) -> NSAttributedString {
        let out = NSMutableAttributedString()
        var number = 0
        var previous: BlockKind?
        let doc = blocks.isEmpty ? [Block(kind: .paragraph, text: "", marks: [], indent: 0)] : blocks
        for (i, block) in doc.enumerated() {
            number = (block.kind == .numbered) ? (previous == .numbered ? number + 1 : 1) : 0
            let base: [NSAttributedString.Key: Any] = [kind: encode(block.kind), indent: Int(block.indent)]

            if let m = markerText(block.kind, number: number) {
                var attrs = base
                attrs[marker] = true
                out.append(NSAttributedString(string: m, attributes: attrs))
            }
            let start = out.length
            out.append(NSAttributedString(string: block.text, attributes: base))
            for mark in block.marks {
                let r = NSRange(location: start + Int(mark.start), length: Int(mark.end) - Int(mark.start))
                guard r.length > 0, NSMaxRange(r) <= out.length else { continue }
                switch mark.kind {
                case .bold: out.addAttribute(bold, value: true, range: r)
                case .italic: out.addAttribute(italic, value: true, range: r)
                case .underline: out.addAttribute(underline, value: true, range: r)
                case .strike: out.addAttribute(strike, value: true, range: r)
                case .highlight: out.addAttribute(highlight, value: true, range: r)
                case .code: out.addAttribute(code, value: true, range: r)
                case .link(let url): out.addAttribute(.link, value: url, range: r)
                case .entryLink(let id): out.addAttribute(entryLink, value: id, range: r)
                }
            }
            if i < doc.count - 1 {
                out.append(NSAttributedString(string: "\n", attributes: base))
            }
            previous = block.kind
        }
        restyle(out, in: NSRange(location: 0, length: out.length))
        return out
    }

    static func markerText(_ k: BlockKind, number: Int) -> String? {
        switch k {
        case .bullet: "•\u{2002}"
        case .numbered: "\(max(number, 1)).\u{2002}"
        case .check(let checked): checked ? "☑\u{2002}" : "☐\u{2002}"
        default: nil
        }
    }

    // MARK: Attributed string → blocks

    static func blocks(_ text: NSAttributedString) -> [Block] {
        let ns = text.string as NSString
        var out: [Block] = []
        var location = 0
        repeat {
            let para = ns.paragraphRange(for: NSRange(location: location, length: 0))
            out.append(block(text, paragraph: para))
            location = NSMaxRange(para)
        } while location < ns.length
        // A trailing newline means an empty last paragraph. It has no
        // characters to carry attributes (the newline's belong to the
        // paragraph before it), so it's always plain.
        if ns.length > 0, ns.character(at: ns.length - 1) == 0x0A {
            out.append(Block(kind: .paragraph, text: "", marks: [], indent: 0))
        }
        return out
    }

    private static func block(_ text: NSAttributedString, paragraph para: NSRange) -> Block {
        let ns = text.string as NSString
        var body = para
        if body.length > 0, ns.character(at: NSMaxRange(body) - 1) == 0x0A { body.length -= 1 }

        let attrsAt = para.length > 0 ? text.attributes(at: para.location, effectiveRange: nil) : [:]
        let k = decode(attrsAt[kind] as? String)
        let ind = UInt8(clamping: attrsAt[indent] as? Int ?? 0)

        // Skip the list marker run.
        var content = body
        if content.length > 0 {
            var markerRange = NSRange()
            if text.attribute(marker, at: content.location, effectiveRange: &markerRange) != nil {
                let end = min(NSMaxRange(markerRange), NSMaxRange(content))
                content = NSRange(location: end, length: NSMaxRange(content) - end)
            }
        }

        let string = ns.substring(with: content)
        var marks: [Mark] = []
        if content.length > 0 {
            for (key, mk) in flagKeys {
                text.enumerateAttribute(key, in: content) { value, r, _ in
                    guard value != nil else { return }
                    marks.append(Mark(start: UInt32(r.location - content.location),
                                      end: UInt32(NSMaxRange(r) - content.location), kind: mk))
                }
            }
            text.enumerateAttribute(.link, in: content) { value, r, _ in
                let url = (value as? URL)?.absoluteString ?? value as? String
                guard let url else { return }
                marks.append(Mark(start: UInt32(r.location - content.location),
                                  end: UInt32(NSMaxRange(r) - content.location), kind: .link(url: url)))
            }
            text.enumerateAttribute(entryLink, in: content) { value, r, _ in
                guard let id = value as? String else { return }
                marks.append(Mark(start: UInt32(r.location - content.location),
                                  end: UInt32(NSMaxRange(r) - content.location), kind: .entryLink(entryId: id)))
            }
        }
        marks.sort { ($0.start, $0.end) < ($1.start, $1.end) }
        return Block(kind: k, text: string, marks: marks, indent: ind)
    }

    // MARK: Kinds as attribute values

    static func encode(_ k: BlockKind) -> String {
        switch k {
        case .paragraph: "p"
        case .heading(let level): "h\(level)"
        case .bullet: "bullet"
        case .numbered: "numbered"
        case .check(let checked): checked ? "check1" : "check0"
        case .quote: "quote"
        case .code: "code"
        case .rule: "rule"
        case .image(let id): "image:\(id)"
        }
    }

    static func decode(_ s: String?) -> BlockKind {
        switch s {
        case "h1": .heading(level: 1)
        case "h2": .heading(level: 2)
        case "h3": .heading(level: 3)
        case "bullet": .bullet
        case "numbered": .numbered
        case "check0": .check(checked: false)
        case "check1": .check(checked: true)
        case "quote": .quote
        case "code": .code
        case "rule": .rule
        case let s? where s.hasPrefix("image:"): .image(attachmentId: String(s.dropFirst(6)))
        default: .paragraph
        }
    }

    // MARK: Styling

    static let bodySize: CGFloat = 17

    /// Derives fonts, colours and paragraph styles from the `dl.*` attributes.
    static func restyle(_ s: NSMutableAttributedString, in range: NSRange) {
        guard s.length > 0 else { return }
        let ns = s.string as NSString
        let full = ns.paragraphRange(for: NSRange(location: min(range.location, s.length), length: min(range.length, s.length - min(range.location, s.length))))
        s.beginEditing()
        var location = full.location
        while location < NSMaxRange(full) {
            let para = ns.paragraphRange(for: NSRange(location: location, length: 0))
            let k = decode(s.attribute(kind, at: para.location, effectiveRange: nil) as? String)
            let ind = s.attribute(indent, at: para.location, effectiveRange: nil) as? Int ?? 0
            s.addAttribute(.paragraphStyle, value: paragraphStyle(k, indent: ind), range: para)

            s.enumerateAttributes(in: para) { attrs, r, _ in
                let isMarker = attrs[marker] != nil
                let isCode = attrs[code] != nil || k == .code
                let underlined = attrs[underline] != nil || attrs[.link] != nil || attrs[entryLink] != nil
                let struck = attrs[strike] != nil || (k == .check(checked: true) && !isMarker)
                let background: PlatformColor? = attrs[highlight] != nil
                    ? PlatformColor.hex(Palette.amber).withAlphaComponent(0.3)
                    : isCode ? PlatformColor.dynamic(light: 0xedebef, dark: 0x2d2b38) : nil
                // nil means "remove", so stale styling is cleared when a mark goes.
                let styled: [(NSAttributedString.Key, Any?)] = [
                    (.font, font(for: k, bold: attrs[bold] != nil, italic: attrs[italic] != nil, code: isCode)),
                    (.foregroundColor, isMarker || k == .quote
                        ? PlatformColor.dynamic(light: 0x6e6b7c, dark: 0xb6b3bf)
                        : PlatformColor.dynamic(light: Palette.grape, dark: 0xf5f4f7)),
                    (.underlineStyle, underlined ? NSUnderlineStyle.single.rawValue : nil),
                    (.strikethroughStyle, struck ? NSUnderlineStyle.single.rawValue : nil),
                    (.backgroundColor, background),
                ]
                for (key, value) in styled {
                    if let value { s.addAttribute(key, value: value, range: r) } else { s.removeAttribute(key, range: r) }
                }
                if attrs[entryLink] != nil {
                    s.addAttribute(.foregroundColor, value: PlatformColor.hex(Palette.azure), range: r)
                }
            }
            location = NSMaxRange(para)
            if para.length == 0 { break }
        }
        s.endEditing()
    }

    static func font(for k: BlockKind, bold: Bool, italic: Bool, code: Bool) -> PlatformFont {
        if code {
            return PlatformFont(name: "GeistMono-Regular", size: bodySize - 2)
                ?? .monospacedSystemFont(ofSize: bodySize - 2, weight: .regular)
        }
        var size = bodySize
        var b = bold
        var i = italic
        switch k {
        case .heading(let level):
            size = [1: 26, 2: 22, 3: 19][Int(level)] ?? 19
            b = true
        case .quote:
            i = true
        default: break
        }
        let name = switch (b, i) {
        case (true, true): "Arvo-BoldItalic"
        case (true, false): "Arvo-Bold"
        case (false, true): "Arvo-Italic"
        case (false, false): "Arvo"
        }
        #if canImport(UIKit)
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: size)
        return PlatformFont(name: name, size: scaled) ?? .systemFont(ofSize: scaled)
        #else
        return PlatformFont(name: name, size: size) ?? .systemFont(ofSize: size)
        #endif
    }

    static func paragraphStyle(_ k: BlockKind, indent: Int) -> NSParagraphStyle {
        let p = NSMutableParagraphStyle()
        p.lineHeightMultiple = 1.25
        p.paragraphSpacing = 6
        switch k {
        case .heading:
            p.paragraphSpacingBefore = 10
            p.paragraphSpacing = 4
        case .bullet, .numbered, .check:
            let base = CGFloat(indent) * 22
            p.firstLineHeadIndent = base
            p.headIndent = base + 22 // wrapped lines align with the text, not the marker
            p.paragraphSpacing = 2
        case .quote:
            p.firstLineHeadIndent = 16
            p.headIndent = 16
        default:
            break
        }
        return p
    }

    /// Attributes for typing inside a paragraph of this kind.
    static func typingAttributes(_ k: BlockKind, indent ind: Int = 0) -> [NSAttributedString.Key: Any] {
        [
            kind: encode(k),
            indent: ind,
            .font: font(for: k, bold: false, italic: false, code: k == .code),
            .foregroundColor: PlatformColor.dynamic(light: Palette.grape, dark: 0xf5f4f7),
            .paragraphStyle: paragraphStyle(k, indent: ind),
        ]
    }
}
