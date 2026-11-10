package pk.phoneproof.app.data

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Generation changes on login/logout, and stays constant when a session rotates its tokens. */
data class SessionTokens(val accessToken:String,val refreshToken:String,val serialized:String)
data class SessionSnapshot(val generation:Long,val tokens:SessionTokens?)

interface SessionStore {
    fun snapshot():SessionSnapshot
    fun replaceIfCurrent(expected:SessionSnapshot,replacement:SessionTokens?):Boolean
}

/** Shares one in-flight renewal across API clients without blocking login or logout. */
class SessionRefreshCoordinator {
    private val lock=Any()
    private val inFlight=mutableMapOf<SessionSnapshot,CompletableFuture<SessionTokens?>>()

    fun refresh(stale:SessionSnapshot,store:SessionStore,renew:(String)->SessionTokens?):SessionTokens? {
        var owner=false
        val pending=synchronized(lock) {
            val current=store.snapshot()
            if(current.generation!=stale.generation||current.tokens==null)return null
            if(current.tokens!=stale.tokens)return current.tokens
            inFlight[current]?:CompletableFuture<SessionTokens?>().also { inFlight[current]=it;owner=true }
        }
        if(owner) {
            try {
                val replacement=renew(stale.tokens!!.refreshToken)
                val result=if(store.replaceIfCurrent(stale,replacement))replacement
                    else store.snapshot().takeIf { it.generation==stale.generation }?.tokens
                pending.complete(result)
            } catch(failure:Throwable) {
                // Transport and temporary server errors leave the existing session intact.
                pending.completeExceptionally(failure)
            } finally {
                synchronized(lock) { inFlight.remove(stale,pending) }
            }
        }
        try { return pending.get() }
        catch(failure:ExecutionException) { throw failure.cause?:failure }
        catch(failure:InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Session refresh interrupted",failure)
        }
    }
}
