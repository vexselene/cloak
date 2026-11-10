package io.cloak.os.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictRulesTest {
    private fun answer(action: ConflictAction, all: Boolean) = ConflictAnswer(1, action, all)

    @Test fun askPolicyLeavesTheDecisionToTheUser() {
        assertNull(ConflictRules(ConflictPolicy.ASK).decided())
    }

    @Test fun fixedPoliciesDecideWithoutAsking() {
        assertEquals(ConflictAction.RENAME, ConflictRules(ConflictPolicy.RENAME).decided())
        assertEquals(ConflictAction.REPLACE, ConflictRules(ConflictPolicy.REPLACE).decided())
        assertEquals(ConflictAction.SKIP, ConflictRules(ConflictPolicy.SKIP).decided())
    }

    @Test fun applyToAllIsRememberedAndStopsFurtherQuestions() {
        val rules = ConflictRules(ConflictPolicy.ASK)
        rules.record(answer(ConflictAction.REPLACE, all = true))
        assertEquals(ConflictAction.REPLACE, rules.decided())
        assertEquals(ConflictAction.REPLACE, rules.remembered)
    }

    @Test fun anAnswerWithoutApplyToAllIsNotRemembered() {
        val rules = ConflictRules(ConflictPolicy.ASK)
        rules.record(answer(ConflictAction.SKIP, all = false))
        assertNull(rules.decided())
        assertNull(rules.remembered)
    }

    @Test fun aRememberedChoiceOverridesTheStartingPolicy() {
        val rules = ConflictRules(ConflictPolicy.RENAME)
        rules.record(answer(ConflictAction.SKIP, all = true))
        assertEquals(ConflictAction.SKIP, rules.decided())
    }

    @Test fun laterApplyToAllAnswersReplaceEarlierOnes() {
        val rules = ConflictRules(ConflictPolicy.ASK)
        rules.record(answer(ConflictAction.SKIP, all = true))
        rules.record(answer(ConflictAction.REPLACE, all = true))
        assertEquals(ConflictAction.REPLACE, rules.decided())
    }

    @Test fun namesAreComparedIgnoringCase() {
        assertEquals(ConflictRules.key("Photo.JPG"), ConflictRules.key("photo.jpg"))
        assertNotEquals(ConflictRules.key("photo.jpg"), ConflictRules.key("photo.png"))
    }

    @Test fun caseFoldingIgnoresTheDeviceLocale() {
        assertEquals("title", ConflictRules.key("TITLE"))
        assertEquals("i", ConflictRules.key("I"))
    }
}