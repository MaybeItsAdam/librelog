import SwiftUI

/// What belongs to a day rather than to an entry: its calendar, photos and
/// locked note. Quiet by design: micro-label headers, hairlines between,
/// under the writing in the editor and under the day's entries in Calendar.
struct DayExtras: View {
    let date: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            rule
            DayCalendarSection(date: date)
                .padding(.bottom, 4)
            rule
            DayPhotosSection(date: date)
                .padding(.bottom, 4)
            LockedNoteSection(date: date)
        }
    }

    private var rule: some View {
        Rectangle().fill(Color.hairline).frame(height: 1)
    }
}
