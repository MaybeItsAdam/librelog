import SwiftUI

#if canImport(UIKit)
import UIKit
typealias PlatformColor = UIColor
#else
import AppKit
typealias PlatformColor = NSColor
#endif

/// The house palette (SPEC.md §14). Literal hexes live only here; views use
/// the semantic roles below, which flip between light and dark.
enum Palette {
    static let chalk = 0xfaf8f4
    static let white = 0xffffff
    static let grape = 0x444054
    static let azure = 0x007fff
    static let emerald = 0x4cc38e
    static let raspberry = 0xd62246
    static let amber = 0xffbf00
    static let purple = 0x7a4de8
    static let pink = 0xff88dc
    static let orange = 0xff6b2b
}

extension Color {
    /// A colour with one hex for light mode and one for dark.
    init(light: Int, dark: Int) {
        self.init(PlatformColor.dynamic(light: light, dark: dark))
    }

    init(hex: Int, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xff) / 255,
                  green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255,
                  opacity: alpha)
    }

    // Surfaces
    static let paper = Color(light: Palette.chalk, dark: 0x1c1a23)
    static let raised = Color(light: Palette.white, dark: 0x25232f)
    static let subtle = Color(light: 0xf1eff1, dark: 0x2d2b38)
    static let well = Color(light: 0xedebef, dark: 0x2d2b38)

    // Lines
    static let hairline = Color(light: 0xe6e4ea, dark: 0x34313f)
    static let inputBorder = Color(light: 0xd8d5dd, dark: 0x34313f)

    // Text
    static let ink = Color(light: Palette.grape, dark: 0xf5f4f7)
    static let muted = Color(light: 0x6e6b7c, dark: 0xb6b3bf)
    static let dim = Color(light: 0xb6b3bf, dark: 0x6e6b7c)

    // Accents: same hex in both modes.
    static let primary = Color(hex: Palette.azure)
    static let success = Color(hex: Palette.emerald)
    static let danger = Color(hex: Palette.raspberry)
    static let warning = Color(hex: Palette.amber)
}

/// Google's Sign-In button colours, fixed by its branding guidelines rather
/// than the house palette: fill, 1pt inside stroke (none for neutral), text.
enum GoogleBrand {
    struct Colours {
        let fill: Color
        let stroke: Color?
        let text: Color
    }

    static func colours(_ theme: GoogleSignInButton.Theme) -> Colours {
        switch theme {
        case .light: Colours(fill: Color(hex: 0xffffff), stroke: Color(hex: 0x747775), text: Color(hex: 0x1f1f1f))
        case .dark: Colours(fill: Color(hex: 0x131314), stroke: Color(hex: 0x8e918f), text: Color(hex: 0xe3e3e3))
        case .neutral: Colours(fill: Color(hex: 0xf2f2f2), stroke: nil, text: Color(hex: 0x1f1f1f))
        }
    }
}

extension PlatformColor {
    static func dynamic(light: Int, dark: Int) -> PlatformColor {
        #if canImport(UIKit)
        PlatformColor { $0.userInterfaceStyle == .dark ? .hex(dark) : .hex(light) }
        #else
        PlatformColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? .hex(dark) : .hex(light)
        }
        #endif
    }

    static func hex(_ v: Int) -> PlatformColor {
        let (r, g, b) = (CGFloat((v >> 16) & 0xff) / 255, CGFloat((v >> 8) & 0xff) / 255, CGFloat(v & 0xff) / 255)
        #if canImport(UIKit)
        return PlatformColor(red: r, green: g, blue: b, alpha: 1) // UIKit's default space is sRGB
        #else
        return PlatformColor(srgbRed: r, green: g, blue: b, alpha: 1)
        #endif
    }
}

/// One radius scale (SPEC.md §14): 8 for cards and sheets, 6 for controls.
enum Radius {
    static let card: CGFloat = 8
    static let control: CGFloat = 6
}

enum Typeface {
    static func serif(_ size: CGFloat, bold: Bool = false, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom(bold ? "Arvo-Bold" : "Arvo", size: size, relativeTo: style)
    }

    static func mono(_ size: CGFloat, relativeTo style: Font.TextStyle = .caption) -> Font {
        .custom("GeistMono-Regular", size: size, relativeTo: style)
    }

    static let body = serif(17)
    static let title = serif(28, bold: true, relativeTo: .largeTitle)
    static let headline = serif(19, bold: true, relativeTo: .headline)
}

extension View {
    /// The micro-label: 10pt, bold, uppercase, 0.15em tracking, muted.
    func microLabel() -> some View {
        font(.system(size: 10, weight: .bold))
            .textCase(.uppercase)
            .kerning(1.5)
            .foregroundStyle(Color.muted)
    }

    /// A raised card: white on paper, separated by a hairline, no shadow.
    func card(padding: CGFloat = 16) -> some View {
        self.padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.raised, in: RoundedRectangle(cornerRadius: Radius.card))
            .overlay(RoundedRectangle(cornerRadius: Radius.card).strokeBorder(Color.hairline))
    }
}

/// A squarish status tag (never a pill): tinted fill, border and text of one hue.
struct StatusTag: View {
    let text: String
    var hue: Color = .primary

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .textCase(.uppercase)
            .kerning(1)
            .foregroundStyle(hue)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(hue.opacity(0.1), in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(hue.opacity(0.4)))
    }
}

/// Tag chip: categorical colour, 6pt corners.
struct TagChip: View {
    let name: String

    var body: some View {
        Text(name)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(Color.ink)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.well, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}

/// Rating as amber stars, half steps allowed.
struct Stars: View {
    let rating: Double?
    var size: CGFloat = 12

    var body: some View {
        if let rating, rating > 0 {
            HStack(spacing: 1) {
                ForEach(0..<5, id: \.self) { i in
                    let fill = rating - Double(i)
                    Image(systemName: fill >= 1 ? "star.fill" : fill >= 0.5 ? "star.leadinghalf.filled" : "star")
                        .font(.system(size: size))
                        .foregroundStyle(fill > 0 ? Color.warning : Color.dim)
                }
            }
            .accessibilityElement()
            .accessibilityLabel("Rated \(rating.formatted()) out of 5")
        }
    }
}
