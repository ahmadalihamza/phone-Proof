package pk.phoneproof.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json

object DiagnosticRegistry {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun definitions(context: Context): List<DiagnosticDefinition> =
        json.decodeFromString(context.assets.open("diagnostics.json").bufferedReader().use { it.readText() })

    fun expanded(context: Context, profile: CapabilityProfile): List<DiagnosticDefinition> = definitions(context).flatMap { d ->
        if (!d.id.contains("{camera_id}")) listOf(d)
        else profile.cameras.map { id -> expandCamera(d, id, profile.features) }
    }

    internal fun expandCamera(d: DiagnosticDefinition, id: String, features: Map<String, Boolean>): DiagnosticDefinition {
        val flash = "camera.$id.flash"
        val legacyTorch = d.handler == "torch" && flash !in features
        return d.copy(
            id = d.id.replace("{camera_id}", id), title = d.title.replace("{camera_id}", id),
            parameters = d.parameters + ("camera_id" to id),
            capabilities = d.capabilities.map { it.replace("{camera_id}", id) }.filterNot { legacyTorch && it == flash },
            version = if (legacyTorch) 1 else d.version,
        )
    }

    fun permissions(d: DiagnosticDefinition): List<String> = d.permissions.flatMap {
        when {
            Build.VERSION.SDK_INT < 31 && it == "android.permission.BLUETOOTH_CONNECT" -> emptyList()
            Build.VERSION.SDK_INT < 31 && it == "android.permission.BLUETOOTH_SCAN" -> listOf("android.permission.ACCESS_FINE_LOCATION")
            else -> listOf(it)
        }
    }
}
