package pk.phoneproof.app.data

import org.junit.Assert.*
import org.junit.Test

class ApiErrorMessageTest {
    @Test fun serverFailuresNeverExposeInfrastructureDetails() {
        val result=apiErrorMessage(503,"""{"detail":"postgres://private:secret@internal-db failed"}""")
        assertEquals("The service is unavailable. Try again shortly.",result)
        assertFalse(result.contains("secret"))
    }
    @Test fun missingReportEvidenceHasAnActionableMessage() {
        assertEquals("Finish the remaining checks first.",apiErrorMessage(409,"""{"detail":{"reason":"Required diagnostics incomplete","checks":["display.white"]}}"""))
        assertEquals("Add and review all 12 photos first.",apiErrorMessage(409,"""{"detail":{"reason":"Cosmetic evidence incomplete","missing":["back"]}}"""))
    }
    @Test fun validationAndAuthenticationResponsesRemainReadable() {
        assertEquals("Check your entries and try again.",apiErrorMessage(422,"""{"detail":[{"type":"invalid_email"}]}"""))
        assertEquals("Email or password doesn't match.",apiErrorMessage(401,"""{"detail":"Invalid email or password"}"""))
        assertEquals("Sign in again to continue.",apiErrorMessage(401,"invalid json"))
    }
}
