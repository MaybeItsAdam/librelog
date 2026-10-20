package uk.co.maybeitssoftware.librelog

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import uk.co.maybeitssoftware.librelog.lock.LockText
import uk.co.maybeitssoftware.librelog.screens.EntryMeta
import uniffi.librelog_core.CoreException

class EntryMetaTest {
    private val saved = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.UK)

    @After fun restore() = Locale.setDefault(saved)

    @Test fun oneDay() {
        assertEquals("Wed 14 Oct · 09:30", EntryMeta.line("2026-10-14", "09:30", null, thisYear = 2026))
        assertEquals("Wed 14 Oct", EntryMeta.line("2026-10-14", null, null, thisYear = 2026))
        assertEquals("Tue 14 Oct 2025", EntryMeta.line("2025-10-14", null, null, thisYear = 2026))
    }

    @Test fun severalDaysSaysHowMany() {
        assertEquals("14 – 16 Oct · 09:30 · 3 days", EntryMeta.line("2026-10-14", "09:30", "2026-10-16", thisYear = 2026))
        assertEquals(1, EntryMeta.days("2026-10-14", "2026-10-14"))
        assertEquals(1, EntryMeta.days("2026-10-14", null))
    }

    @Test fun movingKeepsTheLength() {
        assertEquals("2026-10-22", EntryMeta.movedEnd("2026-10-14", "2026-10-16", "2026-10-20"))
        assertNull(EntryMeta.movedEnd("2026-10-14", null, "2026-10-20"))
    }

    @Test fun lockPasswords() {
        assertEquals("The password needs at least 4 characters", LockText.newPasswordProblem("abc", "abc"))
        assertEquals("The two passwords don't match", LockText.newPasswordProblem("abcd", "abce"))
        assertNull(LockText.newPasswordProblem("abcd", "abcd"))
        assertEquals(LockText.WRONG, LockText.openFailure(CoreException.Crypto("wrong password")))
        assertEquals(LockText.EARLIER, LockText.openFailure(CoreException.Crypto("this note was locked with an earlier password; enter that one")))
    }
}
