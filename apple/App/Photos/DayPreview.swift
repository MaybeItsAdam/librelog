import SwiftUI

/// The photo that stands for a day in the journal list (docs/photos.md):
/// the one chosen in the day's Photos section, else its first, or none.
/// The choice syncs; the pixels are whatever this device has.
enum DayPreview {
    /// The preview for each of `dates` that has one, by date. One core call
    /// for a whole page of the list.
    static func load(_ app: AppModel, dates: [String]) async -> [String: DayPhoto] {
        guard !dates.isEmpty,
              let photos = try? await app.run({ try $0.dayPreviews(journalId: nil, dates: dates) }) else { return [:] }
        return Dictionary(photos.map { ($0.date, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// What a photo's menu offers for the day's preview: "Use as preview"
    /// on any photo that isn't it, and "No preview" while there is one.
    static func actions(for photoID: String, preview previewID: String?) -> (useAsPreview: Bool, noPreview: Bool) {
        (previewID != photoID, previewID != nil)
    }
}

/// A small square of a day's preview photo, down the left of the journal.
/// Decoded small and kept in memory, so scrolling the list stays cheap.
struct DayThumb: View {
    let photo: DayPhoto
    var side: CGFloat = 52
    @State private var image: Image?

    var body: some View {
        Color.well
            .frame(width: side, height: side)
            .overlay {
                if let image {
                    image.resizable().interpolation(.high).scaledToFill().transition(.opacity)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
            .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
            .accessibilityHidden(true)
            .task(id: ThumbCache.key(photo, side: side)) {
                let found = await ThumbCache.image(for: photo, side: side)
                withAnimation(Motion.gentle) { image = found }
            }
    }
}

/// Journal thumbnails, decoded at three times their size and held in
/// memory only: nothing about a photo is written anywhere new.
@MainActor
enum ThumbCache {
    private final class Box {
        let image: Image
        init(_ image: Image) { self.image = image }
    }

    private static let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        c.countLimit = 200
        return c
    }()

    static func key(_ photo: DayPhoto, side: CGFloat) -> String {
        "\(photo.id)|\(photo.previewPath ?? "")|\(photo.sourceRef ?? "")@\(Int(side))"
    }

    static func image(for photo: DayPhoto, side: CGFloat) async -> Image? {
        let key = key(photo, side: side) as NSString
        if let hit = cache.object(forKey: key) { return hit.image }
        // A ThumbHash stand-in is blurred enough at this size to need no label.
        guard let image = await PhotoSource.image(for: photo, side: side, maxPixel: Int(side * 3)).image else { return nil }
        cache.setObject(Box(image), forKey: key)
        return image
    }
}
