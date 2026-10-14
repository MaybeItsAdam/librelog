import SwiftUI

/// Motion tokens. Quick and tactile, never bouncy for its own sake: things
/// settle in about a quarter of a second. With Reduce Motion on, movement
/// collapses to a fade or to nothing (see `Motion.animation(_:reduce:)`).
enum Motion {
    /// Press-down and release of anything tappable.
    static let press = Animation.spring(response: 0.22, dampingFraction: 0.72)
    /// Lists, expanders, selection moving between cells.
    static let snappy = Animation.snappy(duration: 0.26)
    /// Fades: rows arriving, dots appearing, tints.
    static let gentle = Animation.easeOut(duration: 0.22)
    /// Month pages sliding across.
    static let page = Animation.spring(response: 0.34, dampingFraction: 0.9)
    /// How far apart rows start when a list first fades in.
    static let stagger = 0.03

    /// `animation`, or none when Reduce Motion is on.
    static func animation(_ animation: Animation, reduce: Bool) -> Animation? {
        reduce ? nil : animation
    }
}

/// Runs `body` animated, unless Reduce Motion is on.
@MainActor
func withMotion<R>(_ animation: Animation, reduce: Bool, _ body: () throws -> R) rethrows -> R {
    try withAnimation(Motion.animation(animation, reduce: reduce), body)
}

/// Presses in slightly and springs back. Rows use the default; the one
/// primary button presses a little deeper.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.97

    func makeBody(configuration: Configuration) -> some View {
        Pressed(label: configuration.label, isPressed: configuration.isPressed, scale: scale)
    }

    private struct Pressed: View {
        let label: Configuration.Label
        let isPressed: Bool
        let scale: CGFloat
        @Environment(\.accessibilityReduceMotion) private var reduceMotion

        var body: some View {
            label
                .scaleEffect(isPressed && !reduceMotion ? scale : 1)
                .opacity(isPressed ? 0.8 : 1)
                .animation(Motion.press, value: isPressed)
        }
    }
}

extension ButtonStyle where Self == PressableStyle {
    static var pressable: PressableStyle { PressableStyle() }
    static func pressable(scale: CGFloat) -> PressableStyle { PressableStyle(scale: scale) }
}

extension View {
    /// While `active`, fades and lifts the view in as it first appears,
    /// `index` steps after the first. Screens keep it active only for their
    /// first load, so rows that scroll in later simply appear.
    func reveal(_ active: Bool, index: Int) -> some View {
        modifier(Reveal(active: active, index: index))
    }
}

private struct Reveal: ViewModifier {
    let active: Bool
    let index: Int
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let hidden = active && !shown
        content
            .opacity(hidden ? 0 : 1)
            .offset(y: hidden && !reduceMotion ? 8 : 0)
            .onAppear {
                guard active, !shown else { return }
                withAnimation(Motion.gentle.delay(Double(min(index, 10)) * Motion.stagger)) { shown = true }
            }
    }
}
