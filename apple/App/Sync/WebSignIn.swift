import AuthenticationServices
import CryptoKit
import Security
import Foundation

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A provider's web sign-in (Google) in the system's browser sheet, hung from
/// the key window. The sheet catches the `librelog://auth/callback` redirect
/// itself, so it never reaches `onOpenURL`.
@MainActor
final class WebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    /// Where Supabase sends the browser back to. It has to be in the
    /// project's Redirect URLs (supabase/config.toml for local runs).
    static let callback = "librelog://auth/callback"

    /// The sheet showing now, held so it lives until it answers.
    private static var current: ASWebAuthenticationSession?
    private static let anchor = WebSignIn()

    /// The callback URL, or nil if the person closed the sheet.
    static func run(_ url: URL) async throws -> URL? {
        defer { current = nil }
        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "librelog") { @Sendable url, error in
                // Nonisolated on purpose: AuthenticationServices answers from
                // its XPC queue, and a main-actor closure traps there.
                if let url {
                    continuation.resume(returning: url)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(returning: nil)
                } else {
                    continuation.resume(throwing: error ?? SyncError.signedOut)
                }
            }
            session.presentationContextProvider = anchor
            current = session
            if !session.start() {
                current = nil
                continuation.resume(throwing: SyncError.server(0, String(localized: "Couldn't open the sign-in window.")))
            }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { Self.keyWindow() }
    }

    private static func keyWindow() -> ASPresentationAnchor {
        #if canImport(UIKit)
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        return windows.first(where: \.isKeyWindow) ?? windows.first ?? ASPresentationAnchor()
        #else
        return NSApplication.shared.keyWindow ?? NSApplication.shared.windows.first ?? ASPresentationAnchor()
        #endif
    }
}

/// Proof Key for Code Exchange (RFC 7636): Supabase is shown the verifier's
/// hash when the flow starts and the verifier itself when it ends.
enum PKCE {
    static func verifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(Data(bytes))
    }

    static func challenge(_ verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
