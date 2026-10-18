import AuthenticationServices
import SwiftUI

/// "Continue with Google", drawn to Google's Sign-In branding guidelines
/// (developers.google.com/identity/branding-guidelines): the standard-colour
/// "G" at 20pt, 16pt in from the edge with 12pt before the label, Google's
/// fills, 1pt inside stroke and text colours, and a 44pt height (the iOS
/// spec, which is also the minimum touch target). The label is the system
/// font at 14pt medium, as Roboto isn't bundled. The theme follows the
/// colour scheme — light in light mode, dark in dark — unless one is given.
struct GoogleSignInButton: View {
    enum Theme { case light, dark, neutral }

    var theme: Theme?
    let action: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.isEnabled) private var isEnabled

    private static let label = String(localized: "Continue with Google")

    var body: some View {
        let theme = theme ?? (colorScheme == .dark ? .dark : .light)
        let colours = GoogleBrand.colours(theme)
        Button(action: action) {
            HStack(spacing: 12) {
                Image("GoogleG")
                    .resizable()
                    .frame(width: 20, height: 20)
                    .accessibilityHidden(true)
                Text(Self.label)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(1)
                    .foregroundStyle(colours.text)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(colours.fill, in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay {
                if let stroke = colours.stroke {
                    RoundedRectangle(cornerRadius: Radius.control).strokeBorder(stroke, lineWidth: 1)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: Radius.control))
        }
        .buttonStyle(GooglePressStyle(tint: colours.text))
        .opacity(isEnabled ? 1 : 0.38)
        .accessibilityLabel(Self.label)
    }
}

/// Google's state layer: the label colour laid over the fill, faintly.
private struct GooglePressStyle: ButtonStyle {
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label.overlay {
            RoundedRectangle(cornerRadius: Radius.control)
                .fill(tint.opacity(configuration.isPressed ? 0.12 : 0))
                .allowsHitTesting(false)
        }
    }
}

/// Sign in with Apple, as the Human Interface Guidelines ask: Apple's own
/// `SignInWithAppleButton`, black on a light background and white on a dark
/// one, at the control radius the other buttons use and 44pt tall.
struct AppleSignInButton: View {
    let onRequest: (ASAuthorizationAppleIDRequest) -> Void
    let onCompletion: (Result<ASAuthorization, Error>) -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        SignInWithAppleButton(.continue, onRequest: onRequest, onCompletion: onCompletion)
            .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
            // The style is read once when the button is made; rebuild it when
            // the appearance changes so it flips with the rest of the app.
            .id(colorScheme)
            .frame(maxWidth: .infinity, minHeight: 44, maxHeight: 44)
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
    }
}
