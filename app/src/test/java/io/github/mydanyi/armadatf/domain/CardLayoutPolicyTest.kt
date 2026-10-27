package io.github.mydanyi.armadatf.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for the live layout inference.
 *
 * The inference only counts the public/private volumes the platform reports on
 * the exact disk; it must never call a plain public card a completed Linux
 * allocation.
 */
class CardLayoutPolicyTest {

    @Test
    fun noVolumesMeansNoLayout() {
        assertEquals(CardLayout.NONE, CardLayoutPolicy.infer(0, 0))
    }

    @Test
    fun onePrivateAndNoPublicIsAnAndroidCard() {
        assertEquals(CardLayout.ANDROID_ONLY, CardLayoutPolicy.infer(0, 1))
    }

    @Test
    fun onePublicAndOnePrivateIsMixed() {
        assertEquals(CardLayout.MIXED, CardLayoutPolicy.infer(1, 1))
    }

    @Test
    fun onePublicAndNoPrivateIsPublicOnly() {
        assertEquals(CardLayout.PUBLIC_ONLY, CardLayoutPolicy.infer(1, 0))
    }

    @Test
    fun moreThanOneVolumeOfEitherKindIsUnsupported() {
        assertEquals(CardLayout.UNSUPPORTED, CardLayoutPolicy.infer(2, 0))
        assertEquals(CardLayout.UNSUPPORTED, CardLayoutPolicy.infer(0, 2))
        assertEquals(CardLayout.UNSUPPORTED, CardLayoutPolicy.infer(1, 2))
        assertEquals(CardLayout.UNSUPPORTED, CardLayoutPolicy.infer(2, 1))
    }

    @Test
    fun onlyPrivateLayoutsHaveAdoptedAndroidStorage() {
        assertTrue(CardLayoutPolicy.hasAdoptedVolume(CardLayout.ANDROID_ONLY))
        assertTrue(CardLayoutPolicy.hasAdoptedVolume(CardLayout.MIXED))
        assertFalse(CardLayoutPolicy.hasAdoptedVolume(CardLayout.PUBLIC_ONLY))
        assertFalse(CardLayoutPolicy.hasAdoptedVolume(CardLayout.NONE))
        assertFalse(CardLayoutPolicy.hasAdoptedVolume(CardLayout.UNSUPPORTED))
    }

    @Test
    fun publicOnlyIsNeverTreatedAsACompletedLinuxAllocation() {
        val layout = CardLayoutPolicy.infer(1, 0)
        assertEquals(CardLayout.PUBLIC_ONLY, layout)
        assertFalse(CardLayoutPolicy.hasAdoptedVolume(layout))
    }
}
