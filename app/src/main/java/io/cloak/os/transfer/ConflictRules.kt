package io.cloak.os.transfer

import java.util.Locale

class ConflictRules(private val policy: ConflictPolicy) {
    /** Set when the user ticked "apply to all". It then overrides the policy. */
    var remembered: ConflictAction? = null
        private set

    /** The action if it is already decided, or null if the user has to be asked. */
    fun decided(): ConflictAction? = remembered ?: when (policy) {
        ConflictPolicy.ASK -> null
        ConflictPolicy.RENAME -> ConflictAction.RENAME
        ConflictPolicy.REPLACE -> ConflictAction.REPLACE
        ConflictPolicy.SKIP -> ConflictAction.SKIP
    }

    fun record(answer: ConflictAnswer) {
        if (answer.applyToAll) remembered = answer.action
    }

    companion object {
        /** Shared storage ignores case, so "Photo.JPG" and "photo.jpg" are the same file. */
        fun key(name: String): String = name.lowercase(Locale.ROOT)
    }
}