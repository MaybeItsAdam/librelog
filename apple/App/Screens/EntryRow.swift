import SwiftUI

/// One entry in Today, Timeline, Search and On This Day.
struct EntryRow: View {
    let entry: EntrySummary
    var showDate = true
    /// Search snippet with matches wrapped in U+E000…U+E001 (see SearchHit).
    var snippet: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                if showDate {
                    Text(Day.short(entry.date)).microLabel()
                }
                if let time = entry.time {
                    Text(time).font(Typeface.mono(11)).foregroundStyle(Color.muted)
                }
                Spacer()
                if entry.isFavorite {
                    Image(systemName: "star.fill").font(.system(size: 10)).foregroundStyle(Color.warning)
                }
                Stars(rating: entry.rating, size: 10)
            }
            if let title = entry.title, !title.isEmpty {
                Text(title).font(Typeface.headline).foregroundStyle(Color.ink).lineLimit(2)
            }
            Group {
                if let snippet {
                    Text(Self.highlighted(snippet))
                } else if !entry.excerpt.isEmpty {
                    Text(entry.excerpt)
                } else {
                    Text("Empty entry").foregroundStyle(Color.dim)
                }
            }
            .font(Typeface.serif(15))
            .foregroundStyle(Color.ink.opacity(0.85))
            .lineLimit(3)

            if !entry.tagNames.isEmpty {
                HStack(spacing: 4) {
                    ForEach(entry.tagNames.prefix(4), id: \.self) { TagChip(name: $0) }
                }
            }
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    static func highlighted(_ s: String) -> AttributedString {
        var out = AttributedString()
        var inMatch = false
        var run = ""
        func flush() {
            var a = AttributedString(run)
            if inMatch {
                a.backgroundColor = Color.warning.opacity(0.3)
                a.foregroundColor = Color.ink
            }
            out += a
            run = ""
        }
        for ch in s {
            if ch == "\u{E000}" { flush(); inMatch = true }
            else if ch == "\u{E001}" { flush(); inMatch = false }
            else { run.append(ch) }
        }
        flush()
        return out
    }
}

/// Section eyebrow with a hairline, used down the left of every list.
struct SectionHeader: View {
    let title: String
    var trailing: String?

    var body: some View {
        HStack {
            Text(title).microLabel()
            Spacer()
            if let trailing { Text(trailing).font(Typeface.mono(10)).foregroundStyle(Color.muted) }
        }
        .padding(.top, 20)
        .padding(.bottom, 6)
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hairline).frame(height: 1) }
    }
}
