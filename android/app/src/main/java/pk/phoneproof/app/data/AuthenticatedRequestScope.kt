package pk.phoneproof.app.data

import java.io.IOException

class SessionScopeChangedException:IOException("Account or server changed. Restart synchronization for the current account.")

/** A workflow may rotate its tokens, but must keep the account and server it started with. */
data class AuthenticatedRequestScope(val generation:Long,val origin:String) {
    fun requireSession(current:SessionSnapshot,currentOrigin:String):SessionSnapshot {
        if(current.generation!=generation||current.tokens==null||currentOrigin!=origin)throw SessionScopeChangedException()
        return current
    }
}
