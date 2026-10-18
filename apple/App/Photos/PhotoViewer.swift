import SwiftUI

/// One photo, full screen: the original when this device has it. The
/// letterbox is black whatever the theme, since the photo isn't ours to
/// theme (house rule 5). Pinch or double-tap to zoom.
struct PhotoViewer: View {
    let photo: DayPhoto
    @Environment(\.dismiss) private var dismiss
    @State private var image: Image?
    @State private var elsewhere = false
    @State private var zoom: CGFloat = 1
    @State private var pinch: CGFloat = 1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                image
                    .resizable()
                    .scaledToFit()
                    .blur(radius: elsewhere ? 12 : 0, opaque: true)
                    .scaleEffect(zoom * pinch)
                    .gesture(
                        MagnifyGesture()
                            .onChanged { pinch = $0.magnification }
                            .onEnded { _ in
                                withMotion(Motion.snappy, reduce: reduceMotion) {
                                    zoom = min(max(zoom * pinch, 1), 4)
                                    pinch = 1
                                }
                            }
                    )
                    .onTapGesture(count: 2) {
                        withMotion(Motion.snappy, reduce: reduceMotion) { zoom = zoom > 1 ? 1 : 2 }
                    }
                    .transition(.opacity)
            } else {
                ProgressView().tint(.white)
            }
        }
        .overlay(alignment: .topTrailing) {
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: Radius.control))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable(scale: 0.9))
            .keyboardShortcut(.cancelAction)
            .accessibilityLabel("Close")
            .padding(16)
        }
        .overlay(alignment: .bottomLeading) {
            VStack(alignment: .leading, spacing: 4) {
                if elsewhere {
                    Text("On another device")
                        .font(.system(size: 10, weight: .bold))
                        .textCase(.uppercase)
                        .kerning(1.5)
                }
                if let c = photo.caption, !c.isEmpty {
                    Text(c).font(.system(size: 15))
                }
                if let taken = photo.takenAt, taken.count >= 16 {
                    Text("\(Day.short(String(taken.prefix(10)))) · \(String(taken.dropFirst(11).prefix(5)))")
                        .font(Typeface.mono(11))
                        .opacity(0.8)
                }
            }
            .foregroundStyle(.white)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.black.opacity(0.45))
        }
        .task {
            let found = await PhotoSource.image(for: photo, side: 1400, maxPixel: 4096, original: true)
            withAnimation(Motion.gentle) {
                image = found.image
                elsewhere = found.elsewhere
            }
        }
    }
}
