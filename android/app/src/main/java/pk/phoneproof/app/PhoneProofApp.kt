package pk.phoneproof.app
import android.app.Application
class PhoneProofApp:Application() {
    override fun onCreate() {
        super.onCreate()
        pk.phoneproof.app.data.SyncWorker.initialize(this)
    }
}
