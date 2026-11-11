package io.cloak.os.ui

import org.junit.Assert
import org.junit.Test

class OnboardingRulesTest {
    private val last = OnboardingRules.STEP_COUNT - 1

    @Test
    fun clampKeepsStepsInRange() {
        Assert.assertEquals(0, OnboardingRules.clamp(-5))
        Assert.assertEquals(2, OnboardingRules.clamp(2))
        Assert.assertEquals(last, OnboardingRules.clamp(99))
    }

    @Test
    fun nextStopsAtTheLastStep() {
        Assert.assertEquals(1, OnboardingRules.next(0))
        Assert.assertEquals(last, OnboardingRules.next(last))
    }

    @Test
    fun previousStopsAtTheFirstStep() {
        Assert.assertEquals(0, OnboardingRules.previous(0))
        Assert.assertEquals(1, OnboardingRules.previous(2))
    }

    @Test
    fun onlyTheLastStepIsLast() {
        Assert.assertTrue(OnboardingRules.isLast(last))
        Assert.assertTrue(OnboardingRules.isLast(last + 10))
        Assert.assertFalse(OnboardingRules.isLast(0))
    }

    @Test
    fun existingSpacesSkipOnboardingAndFreshInstallsDoNot() {
        Assert.assertTrue(OnboardingRules.initialDone(spaceExists = true))
        Assert.assertFalse(OnboardingRules.initialDone(spaceExists = false))
    }
}