package uk.co.maybeitssoftware.librelog.lock

import uniffi.librelog_core.CoreException

/** Words and checks for locked notes (docs/locked-notes.md). */
object LockText {
    /** The core's minimum, checked here too so the sheet can say so first. */
    const val MIN_LENGTH = 4

    const val WRONG = "That's not the lock password"
    const val EARLIER = "This note was locked with an earlier password. Enter that one."

    /** Why a new password can't be used yet, or null when it can. */
    fun newPasswordProblem(new: String, again: String): String? = when {
        new.length < MIN_LENGTH -> "The password needs at least $MIN_LENGTH characters"
        new != again -> "The two passwords don't match"
        else -> null
    }

    /** A wrong password comes back from the core as a crypto error. */
    fun isWrongPassword(e: Throwable): Boolean = e is CoreException.Crypto

    /** What to say under the field when the core won't open a note. */
    fun openFailure(e: Throwable): String = when {
        e !is CoreException.Crypto -> e.message ?: "Couldn't open the note"
        e.message.orEmpty().contains("earlier password") -> EARLIER
        else -> WRONG
    }
}
