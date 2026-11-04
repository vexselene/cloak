package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpaceProfileTest {
    private var asked = false
    private fun filter(result: Boolean): () -> Boolean = { asked = true; result }

    @Test fun noCloakInsideIsForeignEvenWithASecret() {
        assertEquals(SpaceOwner.FOREIGN, SpaceProfile.classify(false, true, filter(true)))
        assertFalse(asked)
    }

    @Test fun secretPresentIsCloakWithoutAskingTheFilter() {
        assertEquals(SpaceOwner.CLOAK, SpaceProfile.classify(true, true, filter(false)))
        assertFalse(asked)
    }

    @Test fun missingSecretWithResolvingFilterIsCloak() {
        assertEquals(SpaceOwner.CLOAK, SpaceProfile.classify(true, false, filter(true)))
    }

    @Test fun missingSecretWithoutFilterIsForeign() {
        assertEquals(SpaceOwner.FOREIGN, SpaceProfile.classify(true, false, filter(false)))
    }
}