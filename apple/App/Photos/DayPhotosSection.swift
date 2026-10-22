import SwiftUI

extension DayPhoto: Identifiable {}

/// A day's photos, in the editor and under a day in Calendar
/// (docs/photos.md). They belong to the day, beside its kept events, rather
/// than to an entry. Each photo's menu also picks the day's preview in the
/// journal list, or none (DayPreview).
struct DayPhotosSection: View {
    @Environment(AppModel.self) private var app
    let date: String

    @State private var photos: [DayPhoto] = []
    /// The photo standing for the day in the journal; nil for none.
    @State private var previewID: String?
    @State private var choosing = false
    @State private var viewing: DayPhoto?
    @State private var captioning: DayPhoto?
    @State private var caption = ""
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let columns = [GridItem(.adaptive(minimum: 96, maximum: 160), spacing: 6)]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Photos").microLabel()
                Spacer()
                addButton
            }
            if !photos.isEmpty {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
                    ForEach(photos) { photo in
                        tile(photo).transition(.opacity)
                    }
                    addTile
                }
                .padding(.bottom, 8)
            }
        }
        .sensoryFeedback(.success, trigger: photos.count) { old, new in new > old }
        .task(id: "\(date)|\(app.revision)") { await load() }
        .sheet(isPresented: $choosing) {
            PhotoChooser(date: date)
        }
        #if os(iOS)
        .fullScreenCover(item: $viewing) { PhotoViewer(photo: $0) }
        #else
        .sheet(item: $viewing) { PhotoViewer(photo: $0).frame(minWidth: 640, minHeight: 480) }
        #endif
        .alert("Caption", isPresented: Binding(get: { captioning != nil }, set: { if !$0 { captioning = nil } })) {
            TextField("Caption", text: $caption)
            Button("Save") { saveCaption() }
            Button("Cancel", role: .cancel) { captioning = nil }
        }
    }

    private var addButton: some View {
        Button { choosing = true } label: {
            Label("Add photo", systemImage: "plus")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Color.muted)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var addTile: some View {
        Button { choosing = true } label: {
            // A square like the photo tiles; the bare symbol would size the
            // tile to its own height and leave a thin bar.
            Color.raised
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Color.muted)
                }
                .clipShape(RoundedRectangle(cornerRadius: Radius.control))
                .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
                .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("Add photo")
    }

    private func tile(_ photo: DayPhoto) -> some View {
        Button { viewing = photo } label: {
            VStack(alignment: .leading, spacing: 4) {
                PhotoTile(photo: photo, isPreview: photos.count > 1 && photo.id == previewID)
                if let c = photo.caption, !c.isEmpty {
                    Text(c)
                        .font(.system(size: 11))
                        .foregroundStyle(Color.muted)
                        .lineLimit(1)
                }
            }
        }
        .buttonStyle(.pressable)
        .contextMenu {
            Button {
                caption = photo.caption ?? ""
                captioning = photo
            } label: {
                Label(photo.caption?.isEmpty == false ? "Edit caption" : "Add caption", systemImage: "text.bubble")
            }
            let preview = DayPreview.actions(for: photo.id, preview: previewID)
            if preview.useAsPreview {
                Button { setPreview(.photo(id: photo.id), shown: photo.id) } label: {
                    Label("Use as preview", systemImage: "rectangle.stack")
                }
            }
            if preview.noPreview {
                Button { setPreview(.noPreview, shown: nil) } label: {
                    Label("No preview", systemImage: "rectangle.slash")
                }
            }
            Button(role: .destructive) { remove(photo) } label: {
                Label("Remove from this day", systemImage: "trash")
            }
        }
        .accessibilityLabel(photo.caption ?? String(localized: "Photo"))
    }

    private func saveCaption() {
        guard let photo = captioning else { return }
        let (id, text) = (photo.id, caption.trimmingCharacters(in: .whitespacesAndNewlines))
        captioning = nil
        Task { await app.write { try $0.setPhotoCaption(id: id, caption: text.isEmpty ? nil : text) } }
    }

    /// Picks the photo the journal shows for this day, for every device.
    private func setPreview(_ choice: PreviewChoice, shown: String?) {
        let day = date
        previewID = shown
        Task { await app.write { try $0.setDayPreview(journalId: nil, date: day, choice: choice) } }
    }

    private func remove(_ photo: DayPhoto) {
        let id = photo.id
        withMotion(Motion.snappy, reduce: reduceMotion) { photos.removeAll { $0.id == id } }
        Task { await app.write { try $0.removeDayPhoto(id: id) } }
    }

    private func load() async {
        let day = date
        guard let list = try? await app.run({ try $0.dayPhotos(journalId: nil, date: day) }) else { return }
        let preview = await DayPreview.load(app, dates: [day])[day]?.id
        withMotion(Motion.snappy, reduce: reduceMotion) {
            photos = list
            previewID = preview
        }
    }
}

/// One square tile: this device's preview, the user's own iCloud Photos
/// copy, or the blurred ThumbHash with a note that it lives elsewhere.
struct PhotoTile: View {
    let photo: DayPhoto
    /// Marked as the day's journal preview, when there's a choice to show.
    var isPreview = false
    @State private var image: Image?
    @State private var elsewhere = false

    var body: some View {
        Color.well
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    image
                        .resizable()
                        .interpolation(.high)
                        .scaledToFill()
                        .blur(radius: elsewhere ? 6 : 0, opaque: true)
                        .transition(.opacity)
                }
            }
            .overlay(alignment: .topLeading) {
                if isPreview {
                    scrimLabel("Preview")
                }
            }
            .overlay(alignment: .bottomLeading) {
                if elsewhere {
                    scrimLabel("On another device")
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
            .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
            .task(id: photo.id + (photo.previewPath ?? "") + (photo.sourceRef ?? "")) { await load() }
    }

    /// A micro-label on a photo. Text on a photo sits on a fixed scrim
    /// (house rule 5).
    private func scrimLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .textCase(.uppercase)
            .kerning(1.5)
            .foregroundStyle(.white)
            .lineLimit(2)
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
            .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: Radius.control))
            .padding(6)
    }

    private func load() async {
        let found = await PhotoSource.image(for: photo, side: 160, maxPixel: 512)
        withAnimation(Motion.gentle) {
            image = found.image
            elsewhere = found.elsewhere
        }
    }
}

/// Where a kept photo's pixels come from on this device (docs/photos.md).
enum PhotoSource {
    /// The best image this device has for `photo`; `elsewhere` when it's
    /// only the ThumbHash.
    static func image(for photo: DayPhoto, side: CGFloat, maxPixel: Int, original: Bool = false) async -> (image: Image?, elsewhere: Bool) {
        if original, let path = photo.originalPath, let image = await PhotoLibrary.image(path: path, maxPixel: maxPixel) {
            return (image, false)
        }
        if let path = photo.previewPath, let image = await PhotoLibrary.image(path: path, maxPixel: maxPixel) {
            return (image, false)
        }
        if let ref = photo.sourceRef, let local = PhotoLibrary.localIdentifier(forSourceRef: ref),
           let image = await PhotoLibrary.image(localIdentifier: local, side: side, fill: !original) {
            return (image, false)
        }
        return (photo.thumbhash.flatMap(PhotoLibrary.thumbhash), true)
    }
}
