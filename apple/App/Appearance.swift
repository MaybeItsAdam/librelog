import Foundation
import Observation
import SwiftUI

/// The user's appearance choices (docs/appearance.md): synced settings, read
/// at launch and after each sync pull, applied live. An unset key is the
/// default.
struct Appearance: Equatable, Sendable {
    enum Theme: String, CaseIterable, Sendable { case system, light, dark }
    enum Accent: String, CaseIterable, Sendable { case azure, emerald, raspberry, amber, purple, pink, orange }
    enum Face: String, CaseIterable, Sendable { case arvo, sans, serif, mono }
    enum TextSize: String, CaseIterable, Sendable { case small, `default`, large, larger }

    var theme = Theme.system
    var accent = Accent.azure
    var face = Face.arvo
    var textSize = TextSize.default

    static let themeKey = "appearance.theme"
    static let accentKey = "appearance.accent"
    static let faceKey = "appearance.font"
    static let textSizeKey = "appearance.text_size"

    /// Reads all four settings; anything unset or unknown is the default.
    static func load(from store: Store) throws -> Appearance {
        var a = Appearance()
        if let v = try store.setting(key: themeKey), let t = Theme(rawValue: v) { a.theme = t }
        if let v = try store.setting(key: accentKey), let t = Accent(rawValue: v) { a.accent = t }
        if let v = try store.setting(key: faceKey), let t = Face(rawValue: v) { a.face = t }
        if let v = try store.setting(key: textSizeKey), let t = TextSize(rawValue: v) { a.textSize = t }
        return a
    }

    /// The light/dark override, or nil to follow the system.
    var colorScheme: ColorScheme? {
        switch theme {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    /// How much the reading face grows on top of Dynamic Type.
    var scale: CGFloat {
        switch textSize {
        case .small: 0.9
        case .default: 1
        case .large: 1.15
        case .larger: 1.3
        }
    }

    /// The hex of the primary role: actions, links, focus and selection.
    var accentHex: Int {
        switch accent {
        case .azure: Palette.azure
        case .emerald: Palette.emerald
        case .raspberry: Palette.raspberry
        case .amber: Palette.amber
        case .purple: Palette.purple
        case .pink: Palette.pink
        case .orange: Palette.orange
        }
    }

    /// Amber is light, so text and icons on it take the ink colour.
    var accentNeedsInk: Bool { accent == .amber }

    // MARK: Live value

    /// The current choice. SwiftUI reads it through `AppearanceModel`, which
    /// is observed, so views redraw when it changes; UIKit and AppKit text
    /// (the editor) read this copy, which any thread may.
    static var current: Appearance { box.value }

    fileprivate static let box = Box()

    fileprivate final class Box: @unchecked Sendable {
        private let lock = NSLock()
        private var stored = Appearance()
        var value: Appearance {
            get { lock.withLock { stored } }
            set { lock.withLock { stored = newValue } }
        }
    }
}

/// The observed appearance every role token reads, so changing it redraws
/// whatever used the accent or the reading face.
@MainActor
@Observable
final class AppearanceModel {
    static let shared = AppearanceModel()

    private(set) var value = Appearance()

    func apply(_ new: Appearance) {
        guard new != value else { return }
        value = new
        Appearance.box.value = new
    }
}

/// Settings → Appearance: theme, accent, typeface and text size. Each choice
/// is saved as a synced setting the moment it's made.
struct AppearanceSection: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let a = AppearanceModel.shared.value
        Section {
            Picker("Theme", selection: binding(\.theme, key: Appearance.themeKey)) {
                Text("System").tag(Appearance.Theme.system)
                Text("Light").tag(Appearance.Theme.light)
                Text("Dark").tag(Appearance.Theme.dark)
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.raised)

            VStack(alignment: .leading, spacing: 8) {
                Text("Accent").font(.system(size: 13)).foregroundStyle(Color.muted)
                HStack(spacing: 0) {
                    ForEach(Appearance.Accent.allCases, id: \.self) { accent in
                        accentSwatch(accent, selected: a.accent == accent)
                    }
                }
            }
            .padding(.vertical, 4)
            .listRowBackground(Color.raised)

            Picker("Typeface", selection: binding(\.face, key: Appearance.faceKey)) {
                Text("Arvo").tag(Appearance.Face.arvo)
                Text("Sans").tag(Appearance.Face.sans)
                Text("Serif").tag(Appearance.Face.serif)
                Text("Mono").tag(Appearance.Face.mono)
            }
            .listRowBackground(Color.raised)

            Picker("Text size", selection: binding(\.textSize, key: Appearance.textSizeKey)) {
                Text("Small").tag(Appearance.TextSize.small)
                Text("Default").tag(Appearance.TextSize.default)
                Text("Large").tag(Appearance.TextSize.large)
                Text("Larger").tag(Appearance.TextSize.larger)
            }
            .listRowBackground(Color.raised)

            Text("The quick brown fox wrote it all down.")
                .font(Typeface.body)
                .foregroundStyle(Color.ink)
                .padding(.vertical, 6)
                .listRowBackground(Color.raised)
        } header: {
            Text("Appearance").microLabel()
        }
    }

    private func accentSwatch(_ accent: Appearance.Accent, selected: Bool) -> some View {
        var probe = Appearance()
        probe.accent = accent
        let hue = Color(hex: probe.accentHex)
        return Button {
            app.setAppearance(\.accent, accent, key: Appearance.accentKey)
        } label: {
            RoundedRectangle(cornerRadius: Radius.control)
                .fill(hue)
                .frame(width: 28, height: 28)
                .overlay {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(probe.accentNeedsInk ? Color(hex: Palette.grape) : .white)
                    }
                }
                .padding(3)
                .overlay(RoundedRectangle(cornerRadius: Radius.control + 2).strokeBorder(selected ? hue : .clear, lineWidth: 1.5))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.pressable(scale: 0.9))
        .accessibilityLabel(accent.rawValue.capitalized)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func binding<V: RawRepresentable<String> & Sendable>(_ path: WritableKeyPath<Appearance, V>, key: String) -> Binding<V> {
        Binding(
            get: { AppearanceModel.shared.value[keyPath: path] },
            set: { app.setAppearance(path, $0, key: key) }
        )
    }
}
