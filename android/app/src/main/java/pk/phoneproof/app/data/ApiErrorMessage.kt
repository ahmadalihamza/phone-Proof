package pk.phoneproof.app.data

import org.json.JSONObject
import org.json.JSONArray

/** Keep server evidence in ApiException.detail; show actionable copy in the interface. */
internal fun apiErrorMessage(status:Int,payload:String):String {
    val detail=runCatching { JSONObject(payload).opt("detail") }.getOrNull()
    val reason=when(detail) {
        is String -> detail
        is JSONObject -> detail.optString("reason")
        else -> ""
    }
    return when {
        status==422&&detail is JSONArray -> {
            val fields=(0 until detail.length()).mapNotNull { detail.optJSONObject(it)?.optJSONArray("loc") }.map { it.optString(it.length()-1) }
            when {
                "email" in fields -> "Enter a valid email address."
                "password" in fields -> "Use 12-128 characters for your password."
                "otp" in fields -> "Enter the six-digit authenticator code."
                else -> "Check your entries and try again."
            }
        }
        reason.contains("diagnostics incomplete",ignoreCase=true) -> "Finish the remaining checks first."
        reason.contains("Cosmetic evidence incomplete",ignoreCase=true) -> "Add and review all 12 photos first."
        reason.contains("invalid email or password",ignoreCase=true) -> "Email or password doesn't match."
        reason.contains("authenticator",ignoreCase=true) -> "Enter a fresh authenticator code."
        status==409&&reason.contains("account",ignoreCase=true) -> "Couldn't create this account. Try signing in or password recovery."
        status==401 -> "Sign in again to continue."
        status==402 -> "Choose a plan to create your report."
        status==403 -> "This action isn't available for this account."
        status==404 -> "This item isn't available for this account."
        status==413 -> "This photo is too large. Try a smaller capture."
        status==429 -> "Too many requests. Try again in a minute."
        status>=500 -> "The service is unavailable. Try again shortly."
        reason.isNotBlank() -> reason.take(180)
        status==422 -> "Check your entries and try again."
        else -> "Couldn't finish this action. Try again."
    }
}
