package pk.phoneproof.diagnostics

import org.junit.Assert.*
import org.junit.Test

class AudioRouteCheckTest {
    private val speaker = ObservedAudioRoute(10, 2)
    private val headphones = ObservedAudioRoute(20, 3)

    @Test fun noRouteIsNeverVerified() {
        val check = BuiltInAudioRouteCheck(2)
        assertFalse(check.observe(emptyList()))
        assertFalse(check.verified())
    }

    @Test fun stableBuiltInRouteHasEvidence() {
        val check = BuiltInAudioRouteCheck(2)
        assertFalse(check.observe(emptyList())) // Routing can settle just after play starts.
        repeat(3) { assertTrue(check.observe(listOf(speaker))) }
        assertTrue(check.verified())
        assertEquals("10", check.evidence()["audio_route_ids"])
        assertEquals("3", check.evidence()["audio_route_checks"])
    }

    @Test fun externalAndMixedRoutesCannotPass() {
        for (routes in listOf(listOf(headphones), listOf(speaker, headphones))) {
            val check = BuiltInAudioRouteCheck(2)
            assertFalse(check.observe(routes))
            assertTrue(check.invalid())
            assertFalse(check.observe(listOf(speaker)))
            assertFalse(check.verified())
        }
    }

    @Test fun routeChangeAndLossInvalidateTheAttempt() {
        for (routes in listOf(emptyList(), listOf(ObservedAudioRoute(11, 2)), listOf(headphones))) {
            val check = BuiltInAudioRouteCheck(2)
            assertTrue(check.observe(listOf(speaker)))
            assertFalse(check.observe(routes))
            assertFalse(check.verified())
        }
    }

    @Test fun microphoneCannotPassUsingHeadsetInput() {
        val check = BuiltInAudioRouteCheck(15)
        assertFalse(check.observe(listOf(ObservedAudioRoute(30, 7))))
        assertFalse(check.verified())
    }
}
