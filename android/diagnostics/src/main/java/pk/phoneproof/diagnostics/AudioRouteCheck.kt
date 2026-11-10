package pk.phoneproof.diagnostics

import android.media.AudioRouting
import android.os.Build

data class ObservedAudioRoute(val id: Int, val type: Int)

/** Inspect actual routes only while the stream is active; a preference is not evidence. */
fun observedAudioRoutes(routing: AudioRouting): List<ObservedAudioRoute> =
    (if (Build.VERSION.SDK_INT >= 36) routing.routedDevices else listOfNotNull(routing.routedDevice))
        .map { ObservedAudioRoute(it.id, it.type) }

/** A changed, missing or external route invalidates the whole observation window. */
class BuiltInAudioRouteCheck(private val expectedType: Int) {
    private var initial: Set<ObservedAudioRoute>? = null
    private val observed = linkedSetOf<ObservedAudioRoute>()
    private var checks = 0
    private var failure: String? = null

    @Synchronized fun observe(routes: List<ObservedAudioRoute>): Boolean {
        if (failure != null) return false
        val current = routes.toSet()
        observed.addAll(current)
        if (current.isEmpty()) {
            if (initial != null) failure = "route_lost"
            return false
        }
        if (current.any { it.type != expectedType }) {
            failure = "external_route"
            return false
        }
        if (initial != null && current != initial) {
            failure = "route_changed"
            return false
        }
        initial = current
        checks++
        return true
    }

    @Synchronized fun verified(): Boolean = initial != null && failure == null
    @Synchronized fun invalid(): Boolean = failure != null
    @Synchronized fun evidence(): Map<String, String> = mapOf(
        "audio_route_ids" to observed.joinToString { it.id.toString() },
        "audio_route_types" to observed.joinToString { it.type.toString() },
        "audio_route_checks" to checks.toString(),
        "audio_route_verified" to verified().toString(),
        "audio_route_issue" to (failure ?: if (initial == null) "route_unavailable" else "none"),
    )
}
