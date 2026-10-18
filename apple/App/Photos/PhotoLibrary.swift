import Foundation
import ImageIO
import Photos
import SwiftUI
import UniformTypeIdentifiers

#if canImport(UIKit)
import UIKit
typealias PlatformImage = UIImage
#else
import AppKit
typealias PlatformImage = NSImage
#endif

/// A value that crosses an actor boundary once, untouched on the way.
/// PhotoKit hands back images and assets on queues of its choosing.
struct Handoff<T>: @unchecked Sendable {
    let value: T
}

/// The device's Photos library, through PhotoKit (docs/photos.md).
enum PhotoLibrary {
    static var status: PHAuthorizationStatus { PHPhotoLibrary.authorizationStatus(for: .readWrite) }
    static var canRead: Bool { status == .authorized || status == .limited }

    static func requestAccess() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }

    /// Photos taken on `date` (the local day), oldest first, as local
    /// identifiers. With limited access, only the ones the user shared.
    static func identifiers(takenOn date: String) -> [String] {
        guard canRead, let start = Day.date(date),
              let end = Calendar.current.date(byAdding: .day, value: 1, to: start) else { return [] }
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "creationDate >= %@ AND creationDate < %@", start as NSDate, end as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        let result = PHAsset.fetchAssets(with: .image, options: options)
        var ids: [String] = []
        result.enumerateObjects { asset, _, _ in ids.append(asset.localIdentifier) }
        return ids
    }

    static func asset(_ localIdentifier: String) -> PHAsset? {
        guard canRead else { return nil }
        return PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject
    }

    /// `"icloud:<PHCloudIdentifier>"` for each local identifier that has one
    /// yet; a photo just taken has none until iCloud has uploaded it.
    static func cloudRefs(for localIdentifiers: [String]) -> [String: String] {
        guard canRead, !localIdentifiers.isEmpty else { return [:] }
        var out: [String: String] = [:]
        for (local, result) in PHPhotoLibrary.shared().cloudIdentifierMappings(forLocalIdentifiers: localIdentifiers) {
            if case .success(let cloud) = result { out[local] = "icloud:" + cloud.stringValue }
        }
        return out
    }

    /// This device's own identifier for a photo another Apple device kept,
    /// when both share an iCloud Photos library.
    static func localIdentifier(forSourceRef ref: String) -> String? {
        guard canRead, ref.hasPrefix("icloud:") else { return nil }
        let cloud = PHCloudIdentifier(stringValue: String(ref.dropFirst("icloud:".count)))
        guard case .success(let local)? = PHPhotoLibrary.shared().localIdentifierMappings(for: [cloud])[cloud] else { return nil }
        return local
    }

    /// The library's image for a photo, fitting `side` points square (or
    /// larger, for `fill`). Downloads from iCloud if it must.
    static func image(localIdentifier: String, side: CGFloat, fill: Bool = true) async -> Image? {
        guard let asset = asset(localIdentifier) else { return nil }
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let scale: CGFloat = 3
        let size = CGSize(width: side * scale, height: side * scale)
        let found: Handoff<PlatformImage?> = await withCheckedContinuation { done in
            PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: fill ? .aspectFill : .aspectFit, options: options) { image, _ in
                // High quality delivers once.
                done.resume(returning: Handoff(value: image))
            }
        }
        guard let image = found.value else { return nil }
        #if canImport(UIKit)
        return Image(uiImage: image)
        #else
        return Image(nsImage: image)
        #endif
    }

    /// A file's image, downsampled to `maxPixel` on its long side, upright.
    static func image(path: String, maxPixel: Int) async -> Image? {
        let decoded = await Task.detached(priority: .userInitiated) { () -> Handoff<CGImage?> in
            guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return Handoff(value: nil) }
            return Handoff(value: ImageWork.thumbnail(source, maxPixel: maxPixel))
        }.value
        return decoded.value.map { Image(decorative: $0, scale: 1) }
    }

    /// The blurred stand-in for a photo this device can't reach.
    static func thumbhash(_ hash: String) -> Image? {
        guard let t = try? thumbhashImage(thumbhash: hash), t.width > 0, t.height > 0,
              let provider = CGDataProvider(data: t.rgba as CFData),
              let cg = CGImage(width: Int(t.width), height: Int(t.height), bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: Int(t.width) * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        else { return nil }
        return Image(decorative: cg, scale: 1)
    }
}

/// The image work behind keeping a photo: original, preview, hash input,
/// date and place. Runs off the main actor.
enum ImageWork {
    enum Failure: LocalizedError {
        case unreadable
        var errorDescription: String? { String(localized: "That photo couldn't be read.") }
    }

    /// RFC 3339 with the device's own offset: "2026-10-18T09:12:30+01:00".
    private static func rfc3339(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = .current
        return f.string(from: date)
    }

    static func thumbnail(_ source: CGImageSource, maxPixel: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// Everything the core needs to keep a photo from the Photos library.
    static func prepare(localIdentifier: String) async throws -> NewPhoto {
        guard let asset = PhotoLibrary.asset(localIdentifier) else { throw Failure.unreadable }
        let options = PHImageRequestOptions()
        options.version = .current
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        let found: Handoff<(Data, String?)?> = await withCheckedContinuation { done in
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, uti, _, _ in
                done.resume(returning: Handoff(value: data.map { ($0, uti) }))
            }
        }
        guard let (data, uti) = found.value else { throw Failure.unreadable }
        let type = uti.flatMap(UTType.init) ?? .jpeg
        let coordinate = asset.location?.coordinate
        let sourceRef = PhotoLibrary.cloudRefs(for: [localIdentifier])[localIdentifier]
        let taken = asset.creationDate
        return try await Task.detached(priority: .userInitiated) {
            try build(data: data, type: type, takenAt: taken, lat: coordinate?.latitude, lng: coordinate?.longitude,
                      sourceRef: sourceRef, localAsset: "apple:" + localIdentifier)
        }.value
    }

    /// The same for a photo from outside the library: date and place from
    /// its own metadata, if it has any.
    static func prepare(data: Data, type: UTType?) async throws -> NewPhoto {
        try await Task.detached(priority: .userInitiated) {
            try build(data: data, type: type ?? .jpeg, takenAt: nil, lat: nil, lng: nil, sourceRef: nil, localAsset: nil)
        }.value
    }

    private static func build(data: Data, type: UTType, takenAt: Date?, lat: Double?, lng: Double?,
                              sourceRef: String?, localAsset: String?) throws -> NewPhoto {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let preview = thumbnail(source, maxPixel: 1024),
              let small = thumbnail(source, maxPixel: 100)
        else { throw Failure.unreadable }

        // Size as shown: orientations 5–8 turn the stored pixels a quarter.
        var width = props[kCGImagePropertyPixelWidth] as? Int ?? preview.width
        var height = props[kCGImagePropertyPixelHeight] as? Int ?? preview.height
        if let o = props[kCGImagePropertyOrientation] as? Int, (5...8).contains(o) { swap(&width, &height) }

        let dir = FileManager.default.temporaryDirectory
        let ext = type.preferredFilenameExtension ?? "jpg"
        let original = dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
        try data.write(to: original)
        let previewURL = dir.appendingPathComponent("\(UUID().uuidString).jpg")
        guard let dest = CGImageDestinationCreateWithURL(previewURL as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw Failure.unreadable
        }
        CGImageDestinationAddImage(dest, preview, [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw Failure.unreadable }

        let (rgba, hw, hh) = try pixels(small)
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        let gps = props[kCGImagePropertyGPSDictionary] as? [CFString: Any]
        let taken = takenAt ?? exifDate(exif)
        return NewPhoto(path: original.path, mime: type.preferredMIMEType ?? "image/jpeg",
                        width: UInt32(width), height: UInt32(height),
                        takenAt: taken.map(rfc3339),
                        lat: lat ?? coordinate(gps, kCGImagePropertyGPSLatitude, kCGImagePropertyGPSLatitudeRef, negative: "S"),
                        lng: lng ?? coordinate(gps, kCGImagePropertyGPSLongitude, kCGImagePropertyGPSLongitudeRef, negative: "W"),
                        previewPath: previewURL.path, hashRgba: rgba, hashWidth: UInt32(hw), hashHeight: UInt32(hh),
                        sourceRef: sourceRef, localAsset: localAsset)
    }

    /// RGBA8 bytes of an image that already fits 100 × 100.
    private static func pixels(_ image: CGImage) throws -> (Data, Int, Int) {
        let (w, h) = (image.width, image.height)
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { throw Failure.unreadable }
        return (Data(bytes), w, h)
    }

    /// EXIF's local "2026:10:18 09:12:30", with its offset when recorded.
    private static func exifDate(_ exif: [CFString: Any]?) -> Date? {
        guard let s = exif?[kCGImagePropertyExifDateTimeOriginal] as? String else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        if let offset = exif?[kCGImagePropertyExifOffsetTimeOriginal] as? String {
            f.dateFormat = "yyyy:MM:dd HH:mm:ssxxx"
            if let d = f.date(from: s + offset) { return d }
        }
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        f.timeZone = .current
        return f.date(from: s)
    }

    private static func coordinate(_ gps: [CFString: Any]?, _ key: CFString, _ refKey: CFString, negative: String) -> Double? {
        guard let v = gps?[key] as? Double else { return nil }
        return (gps?[refKey] as? String) == negative ? -v : v
    }
}
