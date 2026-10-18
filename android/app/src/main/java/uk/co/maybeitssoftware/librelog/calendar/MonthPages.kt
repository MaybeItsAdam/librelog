package uk.co.maybeitssoftware.librelog.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import uk.co.maybeitssoftware.librelog.Day

/**
 * The calendar's months as pager pages. The last page is [latest] (this
 * month: nothing's written in the future), and the pages run back [count]
 * months before it, far enough that nobody reaches the start.
 */
class MonthPages(val latest: YearMonth, val count: Int = 12 * 200) {
    init {
        require(count > 0)
    }

    val lastPage: Int get() = count - 1

    /** The month on [page]; pages outside the range clamp to its ends. */
    fun month(page: Int): YearMonth = latest.minusMonths((lastPage - page.coerceIn(0, lastPage)).toLong())

    /** The page showing [month]; months outside the range clamp to its ends. */
    fun page(month: YearMonth): Int {
        val back = (latest.year - month.year) * 12L + (latest.monthValue - month.monthValue)
        return (lastPage - back).coerceIn(0L, lastPage.toLong()).toInt()
    }

    /** The first month the pages reach. */
    val earliest: YearMonth get() = month(0)

    fun contains(month: YearMonth): Boolean = month in earliest..latest

    companion object {
        /** Every month grid is six weeks tall, so pages don't jump in height. */
        const val WEEKS = 6

        /**
         * Leading blanks, then each day of [month] as `YYYY-MM-DD`, then
         * trailing blanks to fill [WEEKS] whole weeks starting on [firstDay].
         */
        fun cells(month: YearMonth, firstDay: DayOfWeek): List<String?> {
            val first = month.atDay(1)
            val lead = Math.floorMod(first.dayOfWeek.value - firstDay.value, 7)
            val days = (1..month.lengthOfMonth()).map { Day.string(LocalDate.of(month.year, month.month, it)) }
            val all = List(lead) { null } + days
            return all + List(WEEKS * 7 - all.size) { null }
        }
    }
}
