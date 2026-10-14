import SwiftUI
import UniformTypeIdentifiers

/// Import and export (SPEC.md §16), a section of Settings. The core does the
/// work; this picks files, shows the preview, and reports the result.
struct TransferSection: View {
    @Environment(AppModel.self) private var app
    @State private var choosingFormat = false
    @State private var exported: ExportedFile?
    @State private var exporting = false
    @State private var importing = false
    @State private var preview: PendingImport?
    @State private var busy = false
    @State private var note: String?
    @State private var failure: String?
    /// The last import's outcome; `shown` rolls up to `outcome.imported`.
    @State private var outcome: ImportResult?
    @State private var shown: UInt32 = 0
    @State private var flash = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Section {
            Button { choosingFormat = true } label: { row("Export…", symbol: "square.and.arrow.up") }
            Button { importing = true } label: { row("Import…", symbol: "square.and.arrow.down") }
            if busy {
                ProgressView().frame(maxWidth: .infinity, alignment: .leading)
            }
            if let outcome {
                importedRow(outcome)
            }
            if let note {
                Text(note).font(.system(size: 13)).foregroundStyle(Color.muted)
            }
            if let failure {
                Text(failure).font(.system(size: 13)).foregroundStyle(Color.danger)
            }
        } header: {
            Text("Import & export").microLabel()
        } footer: {
            Text("Exports include every entry's text, tags and ratings. Imports read Librelog backups, Day One, Daylio and Markdown; photos aren't imported.")
                .font(.system(size: 12))
                .foregroundStyle(Color.muted)
        }
        .disabled(busy)
        .sensoryFeedback(.success, trigger: outcome?.imported) { _, new in new != nil }
        .confirmationDialog("Export as", isPresented: $choosingFormat, titleVisibility: .visible) {
            ForEach(ExportChoice.all) { choice in
                Button(choice.title) { Task { await export(choice) } }
            }
        } message: {
            Text("A complete backup can be imported again on any device. Markdown, web page and text are for reading elsewhere.")
        }
        .fileExporter(isPresented: $exporting, document: exported, contentType: exported?.type ?? .json,
                      defaultFilename: exported?.name) { result in
            if case .failure(let e) = result { failure = e.localizedDescription }
            exported.map { try? FileManager.default.removeItem(at: $0.url) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: ImportTypes.all) { result in
            switch result {
            case .success(let url): Task { await prepareImport(url) }
            case .failure(let e): failure = e.localizedDescription
            }
        }
        .sheet(item: $preview) { p in
            ImportPreviewSheet(pending: p) { Task { await runImport(p) } }
        }
    }

    /// "128 entries imported", the count rolling up from zero over a brief
    /// emerald tint that fades back to the plain row.
    private func importedRow(_ r: ImportResult) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("\(shown)")
                    .font(Typeface.mono(17, relativeTo: .body))
                    .foregroundStyle(Color.ink)
                    .contentTransition(.numericText(value: Double(shown)))
                Text(r.imported == 1 ? "entry imported" : "entries imported")
                    .font(.system(size: 14))
                    .foregroundStyle(Color.ink)
            }
            if r.skipped > 0 {
                Text("\(r.skipped) were already here.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.muted)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.success.opacity(flash ? 0.1 : 0), in: RoundedRectangle(cornerRadius: Radius.control))
        .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.success.opacity(flash ? 0.4 : 0)))
        .accessibilityElement(children: .combine)
    }

    private func row(_ title: LocalizedStringKey, symbol: String) -> some View {
        Label(title, systemImage: symbol)
            .foregroundStyle(Color.ink)
            .frame(minHeight: 44, alignment: .leading)
    }

    // MARK: Export

    private func export(_ choice: ExportChoice) async {
        busy = true
        note = nil
        failure = nil
        outcome = nil
        defer { busy = false }
        let format = choice.format
        let ext = exportExtension(format: format)
        let name = "Librelog \(Day.today)"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).\(ext)")
        do {
            let summary = try await app.run { try $0.export(format: format, path: url.path, journalId: nil) }
            exported = ExportedFile(url: url, name: name, type: choice.type)
            exporting = true
            note = summary.entries == 1 ? String(localized: "Exported 1 entry.") : String(localized: "Exported \(summary.entries) entries.")
        } catch {
            failure = error.localizedDescription
        }
    }

    // MARK: Import

    /// Copies the picked file (or folder) somewhere the core can read
    /// without a security scope, then asks the core what it would import.
    private func prepareImport(_ picked: URL) async {
        busy = true
        note = nil
        failure = nil
        outcome = nil
        defer { busy = false }
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let copy = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-\(UUID().uuidString)")
            .appendingPathComponent(picked.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: copy.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: picked, to: copy)
            let p = try await app.run { try $0.previewImport(path: copy.path) }
            preview = PendingImport(url: copy, name: picked.lastPathComponent, preview: p)
        } catch {
            try? FileManager.default.removeItem(at: copy.deletingLastPathComponent())
            failure = error.localizedDescription
        }
    }

    private func runImport(_ p: PendingImport) async {
        preview = nil
        busy = true
        defer {
            busy = false
            try? FileManager.default.removeItem(at: p.url.deletingLastPathComponent())
        }
        do {
            let r = try await app.run { try $0.importFile(path: p.url.path, journalId: nil) }
            shown = 0
            flash = true
            outcome = r
            app.changed()
            app.sync.schedulePush()
            Task { await celebrate(r.imported) }
        } catch {
            failure = error.localizedDescription
        }
    }

    private func celebrate(_ count: UInt32) async {
        try? await Task.sleep(for: .milliseconds(150))
        withAnimation(reduceMotion ? nil : .snappy(duration: 0.7)) { shown = count }
        try? await Task.sleep(for: .milliseconds(1400))
        withAnimation(.easeOut(duration: 0.6)) { flash = false }
    }
}

// MARK: - Formats

private struct ExportChoice: Identifiable {
    let format: ExportFormat
    let title: LocalizedStringKey
    let type: UTType
    var id: String { exportExtension(format: format) }

    static var all: [ExportChoice] { [
        ExportChoice(format: .json, title: "Complete backup (JSON)", type: .json),
        ExportChoice(format: .markdown, title: "Markdown files (.zip)", type: .zip),
        ExportChoice(format: .html, title: "Web page (.html)", type: .html),
        ExportChoice(format: .text, title: "Plain text (.txt)", type: .plainText),
    ] }
}

private enum ImportTypes {
    static let all: [UTType] = {
        var types: [UTType] = [.json, .zip, .commaSeparatedText, .plainText, .folder]
        if let md = UTType("net.daringfireball.markdown") { types.append(md) }
        return types
    }()
}

/// The exported file handed to the system save dialog as-is.
struct ExportedFile: FileDocument {
    static let readableContentTypes: [UTType] = []
    let url: URL
    let name: String
    let type: UTType

    init(url: URL, name: String, type: UTType) {
        self.url = url
        self.name = name
        self.type = type
    }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.featureUnsupported) // write-only
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try FileWrapper(url: url, options: .immediate)
    }
}

// MARK: - Preview

struct PendingImport: Identifiable {
    let id = UUID()
    let url: URL
    let name: String
    let preview: ImportPreview
}

private struct ImportPreviewSheet: View {
    @Environment(\.dismiss) private var dismiss
    let pending: PendingImport
    let onImport: () -> Void

    private var p: ImportPreview { pending.preview }
    private var toImport: UInt32 { p.entries - min(p.alreadyImported, p.entries) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("From", value: sourceName)
                    LabeledContent("Entries", value: "\(p.entries)")
                    if let first = p.firstDate, let last = p.lastDate {
                        LabeledContent("Dates", value: first == last ? Day.short(first) : "\(Day.short(first)) – \(Day.short(last))")
                    }
                    if p.tags > 0 { LabeledContent("Tags", value: "\(p.tags)") }
                    if p.alreadyImported > 0 {
                        Text("\(p.alreadyImported) already in your journal and will be skipped.")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.muted)
                    }
                } header: {
                    Text(pending.name).microLabel()
                }
                if !p.warnings.isEmpty {
                    Section {
                        ForEach(p.warnings, id: \.self) { w in
                            Text(w).font(.system(size: 13)).foregroundStyle(Color.ink)
                        }
                    } header: {
                        Text("Before you import").microLabel()
                    }
                }
                Section {
                    Button {
                        onImport()
                        dismiss()
                    } label: {
                        Text(toImport == 1 ? "Import 1 entry" : "Import \(toImport) entries")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(toImport == 0)
                    .listRowBackground(Color.clear)
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle("Import")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 380)
        #endif
    }

    private var sourceName: String {
        switch p.source {
        case .librelog: "Librelog backup"
        case .dayOne: "Day One"
        case .daylio: "Daylio"
        case .markdown: "Markdown"
        }
    }
}
