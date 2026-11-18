package pk.phoneproof.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import pk.phoneproof.app.data.Api
import java.net.URI

/** Explicit local demo configuration leaves the phone ready for normal Account UI registration. */
class DemoConnectionSetup {
    @Test fun configureExplicitUsbDemoOrigin() = runBlocking(Dispatchers.IO) {
        val origin = InstrumentationRegistry.getArguments().getString("phoneproof_demo_origin")
        assumeTrue("Explicit demo origin required", origin != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".verification"))
        val uri = URI(requireNotNull(origin))
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 && uri.path == "/api/v1/" && uri.query == null && uri.fragment == null && uri.userInfo == null)
        val api = Api(context)
        check(api.vault.snapshot().tokens == null) { "Sign out in PhoneProof QA before changing the demo server." }
        api.baseUrl = origin
        check(api.probeConnection().connected) { "Start the real demo server and authorize USB forwarding first." }
    }
}
