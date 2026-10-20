import SwiftUI

/// Writing one entry (SPEC.md §6.2). Autosaves a moment after typing stops
/// and when leaving; there is no Save button.
struct EntryEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let entryID: String

    @State private var entry: Entry?
    @State private var controller = EditorController()
    @State private var title = ""
    @State private var wordCount: UInt32 = 0
    @State private var saveTask: Task<Void, Never>?
    @State private var toolbarTick = 0
    @State private var addingTag = false
    @State private var newTag = ""
    @State private var showConflicts = false
    @State private var confirmDelete = false
    @State private var showDetails = false
    /// Set when leaving discarded the entry for being empty, so coming back
    /// to it (from a full-screen photo, say) can bring it back.
    @State private var discarded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let entry {
                    header(entry)
                    if entry.hasConflict { conflictBanner }
                    RichTextEditor(controller: controller)
                        #if os(macOS)
                        // AppKit's text view scrolls itself; give it room so the
                        // day's sections below never crowd the writing.
                        .frame(minHeight: 320)
                        #endif
                        .padding(.top, 8)
                    DayExtras(date: entry.date)
                        .padding(.top, 32)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 80)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 40)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.paper)
        .safeAreaInset(edge: .bottom) { formatBar }
        .toolbar { toolbarContent }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: entryID) { await load() }
        .onAppear(perform: reopen)
        .onDisappear(perform: close)
        .sheet(isPresented: $showConflicts) {
            if let entry { ConflictsView(entryID: entry.id) }
        }
        .sheet(isPresented: $showDetails) { detailsSheet }
        .confirmationDialog("Delete this entry?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                let id = entryID
                saveTask?.cancel()
                Task {
                    await app.write { try $0.deleteEntry(id: id) }
                    dismiss()
                }
            }
        } message: {
            Text("It stays in Trash for 30 days.")
        }
    }

    // MARK: Header

    /// The title first, then one quiet line of what the entry is (day, time,
    /// how many days) that opens Details, with the rating beside it. Tags
    /// show only once there are some; everything else lives in Details.
    private func header(_ entry: Entry) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("Title", text: $title, axis: .vertical)
                .font(Typeface.title)
                .foregroundStyle(Color.ink)
                .textFieldStyle(.plain)
                .onSubmit(saveTitle)
                .onChange(of: title) { scheduleSave() }
                .padding(.top, 16)

            HStack(spacing: 8) {
                Button { showDetails = true } label: {
                    HStack(spacing: 6) {
                        if entry.isFavorite {
                            Image(systemName: "star.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(Color.warning)
                        }
                        Text(metaLine(entry))
                            .microLabel()
                            .lineLimit(1)
                            .contentTransition(.opacity)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(Color.dim)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Details: \(metaLine(entry))")
                .accessibilityHint("Change the day, time, length, rating and tags")
                Spacer(minLength: 8)
                RatingPicker(rating: entry.rating) { setRating($0) }
                    .scaleEffect(0.85, anchor: .trailing)
            }

            if !entry.tags.isEmpty {
                tagRow(entry, adding: false)
                    .padding(.bottom, 4)
            }
        }
    }

    /// "TUESDAY 14 OCTOBER · 09:30" or "26 – 28 AUG · 3 DAYS" (cased by the micro-label).
    private func metaLine(_ entry: Entry) -> String {
        var parts = [entry.endDate == nil ? Day.title(entry.date) : Day.range(entry.date, entry.endDate)]
        if let time = entry.time { parts.append(time) }
        if let end = entry.endDate {
            parts.append(String(localized: "\(Day.between(entry.date, end) + 1) days"))
        }
        return parts.joined(separator: " · ")
    }

    private func setRating(_ value: Double?) {
        guard let entry else { return }
        let id = entry.id
        self.entry?.rating = value
        Task { await app.write { try $0.setRating(id: id, rating: value) } }
    }

    private func setFavorite(_ fav: Bool) {
        guard let entry else { return }
        let id = entry.id
        self.entry?.isFavorite = fav
        Task { await app.write { try $0.setFavorite(id: id, favorite: fav) } }
    }

    // MARK: Details

    /// Everything about the entry that isn't the writing: when it was, how
    /// many days it covers, rating, tags and favourite.
    private var detailsSheet: some View {
        NavigationStack {
            Form {
                if let entry {
                    Section {
                        DatePicker("Day", selection: dayBinding(entry), displayedComponents: .date)
                            .listRowBackground(Color.raised)
                        Toggle("Time", isOn: hasTimeBinding(entry))
                            .tint(Color.primary)
                            .listRowBackground(Color.raised)
                        if entry.time != nil {
                            DatePicker("At", selection: timeBinding(entry), displayedComponents: .hourAndMinute)
                                .listRowBackground(Color.raised)
                        }
                    } header: {
                        Text("When").microLabel()
                    }

                    Section {
                        spanRows(entry)
                    } header: {
                        Text("Length").microLabel()
                    } footer: {
                        Text("For a trip or a festival, written up as one entry.")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.muted)
                    }

                    Section {
                        HStack {
                            Text("Rating")
                            Spacer()
                            RatingPicker(rating: entry.rating) { setRating($0) }
                        }
                        .listRowBackground(Color.raised)
                        Toggle("Favourite", isOn: Binding(get: { entry.isFavorite }, set: { setFavorite($0) }))
                            .tint(Color.primary)
                            .listRowBackground(Color.raised)
                    } header: {
                        Text("Mark").microLabel()
                    }

                    Section {
                        tagRow(entry, adding: true)
                            .padding(.vertical, 6)
                            .listRowBackground(Color.raised)
                    } header: {
                        Text("Tags").microLabel()
                    }
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle("Details")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { showDetails = false }
                }
            }
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        #else
        .frame(minWidth: 440, minHeight: 520)
        #endif
    }

    private func dayBinding(_ entry: Entry) -> Binding<Date> {
        Binding(
            get: { Day.date(entry.date) ?? Date() },
            set: { setDateTime(date: Day.string($0), time: entry.time) }
        )
    }

    private func hasTimeBinding(_ entry: Entry) -> Binding<Bool> {
        Binding(
            get: { entry.time != nil },
            set: { on in setDateTime(date: entry.date, time: on ? Day.nowTime : nil) }
        )
    }

    private func timeBinding(_ entry: Entry) -> Binding<Date> {
        Binding(
            get: {
                let day = Day.date(entry.date) ?? Date()
                let hm = (entry.time ?? "").split(separator: ":").compactMap { Int($0) }
                guard hm.count == 2 else { return day }
                return Calendar.current.date(bySettingHour: hm[0], minute: hm[1], second: 0, of: day) ?? day
            },
            set: {
                let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                setDateTime(date: entry.date, time: String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0))
            }
        )
    }

    /// Moves the entry; one covering several days keeps its length.
    private func setDateTime(date: String, time: String?) {
        guard let entry, entry.date != date || entry.time != time else { return }
        let id = entry.id
        if let end = entry.endDate {
            self.entry?.endDate = Day.adding(Day.between(entry.date, end), to: date)
        }
        self.entry?.date = date
        self.entry?.time = time
        Task {
            await app.write { try $0.setDateTime(id: id, date: date, time: time) }
            if let e = try? await app.run({ try $0.entry(id: id) }) {
                self.entry?.date = e.date
                self.entry?.time = e.time
                self.entry?.endDate = e.endDate
            }
        }
    }

    /// "Several days": a trip or a festival, written up as one entry. Turning
    /// it on starts with the next day as the last; the picker allows a year.
    @ViewBuilder
    private func spanRows(_ entry: Entry) -> some View {
        let start = Day.date(entry.date) ?? Date()
        let cal = Calendar.current
        let earliest = cal.date(byAdding: .day, value: 1, to: start) ?? start
        let latest = cal.date(byAdding: .day, value: 365, to: start) ?? start
        let several = Binding<Bool>(
            get: { entry.endDate != nil },
            set: { on in setEnd(on ? Day.string(earliest) : nil) }
        )
        let last = Binding<Date>(
            get: { entry.endDate.flatMap(Day.date) ?? earliest },
            set: { setEnd(Day.string($0)) }
        )
        Toggle("Several days", isOn: several)
            .tint(Color.primary)
            .sensoryFeedback(.selection, trigger: entry.endDate != nil)
            .listRowBackground(Color.raised)
        if entry.endDate != nil {
            DatePicker("Last day", selection: last, in: earliest...latest, displayedComponents: .date)
                .listRowBackground(Color.raised)
        }
    }

    private func setEnd(_ end: String?) {
        guard let entry, entry.endDate != end else { return }
        let id = entry.id
        withMotion(Motion.snappy, reduce: reduceMotion) { self.entry?.endDate = end }
        Task { await app.write { try $0.setEndDate(id: id, endDate: end) } }
    }

    /// The entry's tags, each with a menu to remove it; `adding` shows the
    /// "+ Tag" control (Details) rather than leaving it to Details (header).
    private func tagRow(_ entry: Entry, adding showAdd: Bool) -> some View {
        FlowLayout(spacing: 6) {
            ForEach(entry.tags, id: \.id) { tag in
                Menu {
                    Button("Remove tag", role: .destructive) {
                        let (e, t) = (entry.id, tag.id)
                        Task {
                            await app.write { try $0.untagEntry(entryId: e, tagId: t) }
                            await reloadTags()
                        }
                    }
                } label: {
                    TagChip(name: tag.name)
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
            }
            if !showAdd {
                EmptyView()
            } else if addingTag {
                TextField("Tag", text: $newTag)
                    .font(.system(size: 13))
                    .textFieldStyle(.plain)
                    .frame(width: 110)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.inputBorder))
                    .onSubmit(addTag)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
            } else {
                Button {
                    addingTag = true
                } label: {
                    Label("Tag", systemImage: "plus")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Color.muted)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var conflictBanner: some View {
        Button { showConflicts = true } label: {
            HStack {
                Image(systemName: "arrow.triangle.branch")
                Text("Edited on two devices — review").font(.system(size: 13, weight: .medium))
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11))
            }
            .foregroundStyle(Color.warning)
            .padding(10)
            .background(Color.warning.opacity(0.1), in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.warning.opacity(0.4)))
        }
        .buttonStyle(.plain)
        .padding(.top, 10)
    }

    // MARK: Format bar

    private var formatBar: some View {
        let _ = toolbarTick // re-render when the caret moves
        return HStack(spacing: 2) {
            formatButton("bold", active: controller.isActive(BlockText.bold)) { controller.toggle(BlockText.bold) }
            formatButton("italic", active: controller.isActive(BlockText.italic)) { controller.toggle(BlockText.italic) }
            formatButton("underline", active: controller.isActive(BlockText.underline)) { controller.toggle(BlockText.underline) }
            formatButton("highlighter", active: controller.isActive(BlockText.highlight)) { controller.toggle(BlockText.highlight) }
            Divider().frame(height: 20).padding(.horizontal, 4)
            formatButton("textformat.size", active: isHeading) { controller.setKind(.heading(level: 2)) }
            formatButton("list.bullet", active: controller.currentKind == .bullet) { controller.setKind(.bullet) }
            formatButton("list.number", active: controller.currentKind == .numbered) { controller.setKind(.numbered) }
            formatButton("checklist", active: isCheck) { controller.setKind(.check(checked: false)) }
            formatButton("text.quote", active: controller.currentKind == .quote) { controller.setKind(.quote) }
            Spacer(minLength: 8)
            Text("\(wordCount) words")
                .font(Typeface.mono(11))
                .foregroundStyle(Color.muted)
                .monospacedDigit()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.raised)
        .overlay(alignment: .top) { Rectangle().fill(Color.hairline).frame(height: 1) }
    }

    private var isHeading: Bool {
        if case .heading = controller.currentKind { return true }
        return false
    }

    private var isCheck: Bool {
        if case .check = controller.currentKind { return true }
        return false
    }

    private func formatButton(_ symbol: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(active ? Color.primary : Color.ink)
                .frame(width: 34, height: 34)
                .background(active ? Color.primary.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: Radius.control))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(symbol)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .primaryAction) {
            Menu {
                if let entry {
                    Button { showDetails = true } label: {
                        Label("Details", systemImage: "slider.horizontal.3")
                    }
                    Button {
                        setFavorite(!entry.isFavorite)
                    } label: {
                        Label(entry.isFavorite ? "Unfavourite" : "Favourite", systemImage: entry.isFavorite ? "star.slash" : "star")
                    }
                    ShareLink(item: blocksToMarkdown(blocks: controller.blocks())) {
                        Label("Share as text", systemImage: "square.and.arrow.up")
                    }
                    Button { showConflicts = true } label: {
                        Label("Version history", systemImage: "clock.arrow.circlepath")
                    }
                    Divider()
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }

    // MARK: Loading and saving

    private func load() async {
        let id = entryID
        guard let e = try? await app.run({ try $0.entry(id: id) }) else { return }
        entry = e
        title = e.title ?? ""
        wordCount = e.wordCount
        controller.onChange = { scheduleSave() }
        controller.onSelectionChange = { toolbarTick &+= 1 }
        // Give the text view a turn to attach before loading.
        await Task.yield()
        controller.load(e.body)
    }

    private func reloadTags() async {
        let id = entryID
        if let e = try? await app.run({ try $0.entry(id: id) }) { entry?.tags = e.tags }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            await save()
        }
    }

    /// Leaving: saves what's pending, then deletes the entry if nothing was
    /// ever written in it, so a stray "New entry" leaves nothing behind.
    private func close() {
        saveTask?.cancel()
        let id = entryID
        let deleting = confirmDelete
        Task {
            await save()
            guard !deleting else { return }
            if await app.discardIfEmpty(id) { discarded = true }
        }
    }

    private func reopen() {
        guard discarded else { return }
        discarded = false
        let id = entryID
        Task { await app.write { try $0.restoreEntry(id: id) } }
    }

    private func save() async {
        guard entry != nil else { return }
        let id = entryID
        let blocks = controller.blocks()
        let newTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let titleChanged = newTitle != (entry?.title ?? "")
        do {
            wordCount = try await app.run { store in
                if titleChanged { try store.setTitle(id: id, title: newTitle.isEmpty ? nil : newTitle) }
                return try store.saveBody(id: id, body: blocks)
            }
            entry?.title = newTitle.isEmpty ? nil : newTitle
            app.changed()
            app.sync.schedulePush()
        } catch {
            app.lastError = error.localizedDescription
        }
    }

    private func saveTitle() {
        scheduleSave()
    }

    private func addTag() {
        let name = newTag.trimmingCharacters(in: .whitespaces)
        newTag = ""
        addingTag = false
        guard !name.isEmpty, let entry else { return }
        let id = entry.id
        Task {
            await app.write { store in
                let tag = try store.tagNamed(name: name)
                try store.tagEntry(entryId: id, tagId: tag.id, value: nil)
            }
            await reloadTags()
        }
    }
}

/// Tappable stars; tapping the current rating clears it.
struct RatingPicker: View {
    let rating: Double?
    let set: (Double?) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { i in
                let value = Double(i)
                Button {
                    set(rating == value ? nil : value)
                } label: {
                    Image(systemName: (rating ?? 0) >= value ? "star.fill" : (rating ?? 0) >= value - 0.5 ? "star.leadinghalf.filled" : "star")
                        .font(.system(size: 16))
                        .foregroundStyle((rating ?? 0) >= value - 0.5 ? Color.warning : Color.dim)
                        // Filled stars pop when the rating changes; the rest stay still.
                        .symbolEffect(.bounce.up, options: .speed(1.6), value: (rating ?? 0) >= value - 0.5 ? rating : nil)
                        .symbolEffectsRemoved(reduceMotion)
                        .animation(Motion.gentle, value: rating)
                        .frame(width: 28, height: 44) // 44pt hit target, 16pt glyph
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(i) stars")
            }
        }
        .sensoryFeedback(.selection, trigger: rating)
    }
}

/// Simple wrapping layout for chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(proposal.width ?? .infinity, subviews)
        return CGSize(width: proposal.width ?? rows.width, height: rows.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowHeight = max(rowHeight, s.height)
        }
    }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (width: CGFloat, height: CGFloat) {
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > width, x > 0 {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += s.width + spacing
            maxX = max(maxX, x)
            rowHeight = max(rowHeight, s.height)
        }
        return (maxX, y + rowHeight)
    }
}
