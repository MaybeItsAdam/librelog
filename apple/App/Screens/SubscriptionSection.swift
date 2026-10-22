import StoreKit
import SwiftUI

/// Settings → Sync: the yearly sync subscription (SPEC.md §15). Shows what
/// the server says the account has paid for, and offers the App Store
/// subscription, restore, and the store's own management screen.
struct SubscriptionSection: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    @State private var managing = false

    var body: some View {
        let subs = app.subscriptions
        let status = app.sync.subscription
        Section {
            LabeledContent("Subscription") { tag(status) }
            Text(status.summary())
                .font(.system(size: 13))
                .foregroundStyle(Color.muted)

            if !status.isActive {
                Button {
                    Task { await subs.purchase() }
                } label: {
                    if let price = subs.priceText {
                        Text("Subscribe for \(price)")
                    } else {
                        Text("Subscribe")
                    }
                }
                .disabled(subs.product == nil || subs.busy)
            }
            if status.store == "app_store" || (status.store == nil && status.isActive) {
                Button("Manage subscription") { manage() }
            } else if let name = status.storeName {
                Text("Bought through \(name). Manage it there.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.muted)
            }
            Button("Restore purchases") { Task { await subs.restore() } }
                .disabled(subs.busy)
            if subs.busy { ProgressView().controlSize(.small) }
            if let message = subs.message {
                Text(message).font(.system(size: 13)).foregroundStyle(Color.danger)
            }
        } header: {
            Text("Subscription").microLabel()
        } footer: {
            disclosure(price: subs.priceText)
        }
        .task {
            subs.start()
            await subs.loadProduct()
            await subs.reportUnfinished()
        }
        #if os(iOS)
        .manageSubscriptionsSheet(isPresented: $managing)
        #endif
    }

    private func tag(_ status: SubscriptionStatus) -> some View {
        switch status.access() {
        case .active where status.state == "grace" || status.state == "billing_retry":
            StatusTag(text: String(localized: "Payment due"), hue: .warning)
        case .active:
            StatusTag(text: String(localized: "Active"), hue: .success)
        case .lapsed:
            StatusTag(text: String(localized: "Lapsed"), hue: .warning)
        case .none, .ended:
            StatusTag(text: String(localized: "Not active"), hue: .danger)
        }
    }

    /// The auto-renewal terms App Review asks to see next to the button.
    private func disclosure(price: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("LibreLog Sync is a 1-year subscription\(price.map { " at \($0)" } ?? "") that keeps your journal in step across your devices; the price covers what the service costs to run. Payment is charged to your Apple Account when you confirm. It renews automatically each year unless you turn off auto-renew at least 24 hours before the end of the current year, and the renewal is charged within the 24 hours before then. Manage or cancel it in your Apple Account settings at any time.")
            HStack(spacing: 16) {
                Link("Terms of Use", destination: Legal.terms)
                Link("Privacy Policy", destination: Legal.privacy)
            }
        }
        .font(.system(size: 12))
    }

    private func manage() {
        #if os(iOS)
        managing = true
        #else
        openURL(Legal.manageSubscriptions)
        #endif
    }
}
