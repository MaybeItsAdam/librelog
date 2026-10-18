import SwiftUI

/// Earlier versions of an entry, including the losing side of any edit made
/// on two devices at once (SPEC.md §12.5). Copy what you need back, then mark
/// them reviewed.
struct ConflictsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let entryID: String
    @State private var revisions: [Revision] = []

    var body: some View {
        NavigationStack {
            List {
                if revisions.isEmpty {
                    ContentUnavailableView("No other versions", systemImage: "clock",
                                           description: Text("Versions kept from edits on other devices appear here."))
                        .listRowBackground(Color.paper)
                }
                ForEach(revisions, id: \.id) { r in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            if r.isConflict { StatusTag(text: String(localized: "From another device"), hue: .warning) }
                            Spacer()
                            Text(r.createdAt.prefix(16).replacingOccurrences(of: "T", with: " "))
                                .font(Typeface.mono(11))
                                .foregroundStyle(Color.muted)
                        }
                        Text(blocksToMarkdown(blocks: r.body))
                            .font(Typeface.reading(15))
                            .foregroundStyle(Color.ink)
                            .textSelection(.enabled)
                    }
                    .padding(.vertical, 8)
                    .listRowBackground(Color.paper)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle("Versions")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Mark reviewed") {
                        let id = entryID
                        Task {
                            await app.write { try $0.resolveConflicts(entryId: id) }
                            dismiss()
                        }
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .task {
                let id = entryID
                revisions = (try? await app.run { try $0.revisions(entryId: id) }) ?? []
            }
        }
    }
}
