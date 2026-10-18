import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// "Add photo" (docs/photos.md): the photos taken that day from the Photos
/// library, several at a time; any other photo through the system picker;
/// and on iPhone and iPad, the camera.
struct PhotoChooser: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let date: String

    @State private var status = PhotoLibrary.status
    @State private var identifiers: [String] = []
    @State private var chosen: [String] = []
    @State private var loaded = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var adding = false
    @State private var showCamera = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let columns = [GridItem(.adaptive(minimum: 96, maximum: 140), spacing: 4)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(Day.title(date)).microLabel().padding(.top, 8).padding(.bottom, 8)
                    content
                    actions.padding(.top, 20)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 24)
            }
            .background(Color.paper)
            .navigationTitle("Add photos")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if adding {
                        ProgressView()
                    } else {
                        Button(chosen.isEmpty ? String(localized: "Add") : String(localized: "Add \(chosen.count)")) {
                            Task { await add(identifiers: chosen) }
                        }
                        .disabled(chosen.isEmpty)
                    }
                }
            }
            .disabled(adding)
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in Task { await addTaken(image) } }
                .ignoresSafeArea()
        }
        #else
        .frame(minWidth: 520, minHeight: 480)
        #endif
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            Task { await add(items: items) }
        }
        .task {
            if status == .notDetermined { status = await PhotoLibrary.requestAccess() }
            let day = date
            let ids = PhotoLibrary.identifiers(takenOn: day)
            withMotion(Motion.gentle, reduce: reduceMotion) {
                identifiers = ids
                loaded = true
            }
        }
    }

    // MARK: That day's photos

    @ViewBuilder
    private var content: some View {
        switch status {
        case .denied, .restricted:
            note("LibreLog can't see your photo library, so it can't show the photos you took this day. You can allow it in Settings → Privacy → Photos, or choose a photo below.")
        default:
            if status == .limited {
                note("Showing only the photos you've shared with LibreLog.")
                    .padding(.bottom, 10)
            }
            if !loaded {
                ProgressView().frame(maxWidth: .infinity, minHeight: 120)
            } else if identifiers.isEmpty {
                note("No photos in your library were taken this day.")
            } else {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(identifiers, id: \.self) { id in
                        libraryTile(id)
                    }
                }
            }
        }
    }

    private func libraryTile(_ id: String) -> some View {
        let index = chosen.firstIndex(of: id)
        return Button {
            withMotion(Motion.snappy, reduce: reduceMotion) {
                if let index { chosen.remove(at: index) } else { chosen.append(id) }
            }
        } label: {
            LibraryThumb(localIdentifier: id)
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.control)
                        .strokeBorder(index != nil ? Color.primary : Color.hairline, lineWidth: index != nil ? 2 : 1)
                }
                .overlay(alignment: .topTrailing) {
                    if let index {
                        Text("\(index + 1)")
                            .font(Typeface.mono(11))
                            .foregroundStyle(Color.onPrimary)
                            .frame(width: 22, height: 22)
                            .background(Color.primary, in: RoundedRectangle(cornerRadius: Radius.control))
                            .padding(5)
                            .transition(.opacity)
                    }
                }
        }
        .buttonStyle(.pressable(scale: 0.95))
        .accessibilityAddTraits(index != nil ? .isSelected : [])
        .accessibilityLabel("Photo")
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Color.muted)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Other sources

    private var actions: some View {
        VStack(spacing: 8) {
            PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
                Label("Choose another photo", systemImage: "photo.on.rectangle")
            }
            .buttonStyle(ActionStyle())
            #if os(iOS)
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showCamera = true } label: {
                    Label("Take photo", systemImage: "camera")
                }
                .buttonStyle(ActionStyle())
            }
            #endif
        }
    }

    /// A full-width bordered button for the other ways to add a photo.
    private struct ActionStyle: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View {
            configuration.label
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Color.ink)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Color.raised, in: RoundedRectangle(cornerRadius: Radius.control))
                .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.inputBorder))
                .contentShape(Rectangle())
                .opacity(configuration.isPressed ? 0.8 : 1)
        }
    }

    // MARK: Adding

    private func add(identifiers ids: [String]) async {
        adding = true
        defer { adding = false }
        for id in ids {
            await keep { try await ImageWork.prepare(localIdentifier: id) }
        }
        dismiss()
    }

    private func add(items: [PhotosPickerItem]) async {
        adding = true
        defer { adding = false }
        for item in items {
            // A library photo keeps its identifier, so other Apple devices
            // can find it; anything else goes in as its bytes.
            if let id = item.itemIdentifier, PhotoLibrary.asset(id) != nil {
                await keep { try await ImageWork.prepare(localIdentifier: id) }
            } else {
                let type = item.supportedContentTypes.first
                await keep {
                    guard let data = try await item.loadTransferable(type: Data.self) else { throw ImageWork.Failure.unreadable }
                    return try await ImageWork.prepare(data: data, type: type)
                }
            }
        }
        picked = []
        dismiss()
    }

    #if os(iOS)
    /// Saves the shot to the Photos library first, so the Mac finds it
    /// through iCloud Photos, then keeps it from there.
    private func addTaken(_ image: UIImage) async {
        adding = true
        defer { adding = false }
        let saved = await CameraPicker.save(image)
        if let saved, PhotoLibrary.asset(saved) != nil {
            await keep { try await ImageWork.prepare(localIdentifier: saved) }
        } else if let data = image.jpegData(compressionQuality: 0.9) {
            await keep { try await ImageWork.prepare(data: data, type: .jpeg) }
        }
        dismiss()
    }
    #endif

    private func keep(_ prepare: () async throws -> NewPhoto) async {
        do {
            let photo = try await prepare()
            let day = date
            await app.write { _ = try $0.addDayPhoto(journalId: nil, date: day, photo: photo) }
        } catch {
            app.lastError = error.localizedDescription
        }
    }
}

/// A library photo's square thumbnail, by local identifier.
private struct LibraryThumb: View {
    let localIdentifier: String
    @State private var image: Image?

    var body: some View {
        Color.well
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    image.resizable().scaledToFill().transition(.opacity)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
            .task(id: localIdentifier) {
                let found = await PhotoLibrary.image(localIdentifier: localIdentifier, side: 120)
                withAnimation(Motion.gentle) { image = found }
            }
    }
}

#if os(iOS)
/// The system camera, for "Take photo".
struct CameraPicker: UIViewControllerRepresentable {
    let taken: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.taken(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }

    /// Adds the shot to the Photos library and returns its local identifier.
    static func save(_ image: UIImage) async -> String? {
        let placeholder = Placeholder()
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetChangeRequest.creationRequestForAsset(from: image)
                placeholder.id = request.placeholderForCreatedAsset?.localIdentifier
            }
        } catch {
            return nil
        }
        return placeholder.id
    }

    /// Carries the new asset's identifier out of PhotoKit's change block,
    /// which runs on its own queue and finishes before `performChanges` returns.
    private final class Placeholder: @unchecked Sendable {
        var id: String?
    }
}
#endif
