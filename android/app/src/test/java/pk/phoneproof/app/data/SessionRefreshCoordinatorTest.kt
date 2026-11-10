package pk.phoneproof.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionRefreshCoordinatorTest {
    private fun tokens(value:String)=SessionTokens("access-$value","refresh-$value",value)

    private class MemoryStore(initial:SessionTokens):SessionStore {
        private var state=SessionSnapshot(1,initial)
        @Synchronized override fun snapshot()=state
        @Synchronized override fun replaceIfCurrent(expected:SessionSnapshot,replacement:SessionTokens?):Boolean {
            if(state!=expected)return false
            state=SessionSnapshot(state.generation+if(replacement==null)1 else 0,replacement)
            return true
        }
        @Synchronized fun login(tokens:SessionTokens) { state=SessionSnapshot(state.generation+1,tokens) }
        @Synchronized fun logout() { state=SessionSnapshot(state.generation+1,null) }
    }

    private fun observeSecondClient(store:SessionStore,observed:CountDownLatch):SessionStore {
        val reads=AtomicInteger()
        return object:SessionStore {
            override fun snapshot()=store.snapshot().also { if(reads.incrementAndGet()==2)observed.countDown() }
            override fun replaceIfCurrent(expected:SessionSnapshot,replacement:SessionTokens?)=store.replaceIfCurrent(expected,replacement)
        }
    }

    @Test fun concurrentClientsShareOneRefresh() {
        val old=tokens("old");val fresh=tokens("fresh")
        val store=MemoryStore(old);val stale=store.snapshot();val coordinator=SessionRefreshCoordinator()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val secondObserved=CountDownLatch(1)
        val clients=observeSecondClient(store,secondObserved)
        val calls=AtomicInteger();val executor=Executors.newFixedThreadPool(2)
        val renew:(String)->SessionTokens?={ token ->
            assertEquals(old.refreshToken,token);calls.incrementAndGet();entered.countDown()
            assertTrue(release.await(3,TimeUnit.SECONDS));fresh
        }
        try {
            val first=executor.submit<SessionTokens?> { coordinator.refresh(stale,clients,renew) }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val second=executor.submit<SessionTokens?> { coordinator.refresh(stale,clients,renew) }
            assertTrue(secondObserved.await(3,TimeUnit.SECONDS));assertFalse(second.isDone)
            release.countDown()
            assertEquals(fresh,first.get(3,TimeUnit.SECONDS));assertEquals(fresh,second.get(3,TimeUnit.SECONDS))
            assertEquals(1,calls.get());assertEquals(SessionSnapshot(stale.generation,fresh),store.snapshot())
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun aLateUnauthorizedResponseUsesTheAlreadyRotatedSession() {
        val fresh=tokens("fresh");val store=MemoryStore(tokens("old"));val stale=store.snapshot()
        val coordinator=SessionRefreshCoordinator()
        assertEquals(fresh,coordinator.refresh(stale,store) { fresh })
        assertEquals(fresh,coordinator.refresh(stale,store) { fail("Must reuse the completed rotation");null })
    }

    @Test fun rejectedRefreshClearsOnlyTheCurrentSession() {
        val store=MemoryStore(tokens("old"));val stale=store.snapshot()
        assertNull(SessionRefreshCoordinator().refresh(stale,store) { null })
        assertEquals(SessionSnapshot(stale.generation+1,null),store.snapshot())
    }

    @Test fun temporaryFailureKeepsCredentialsAndIsSharedByWaitingClients() {
        val store=MemoryStore(tokens("old"));val stale=store.snapshot();val coordinator=SessionRefreshCoordinator()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val secondObserved=CountDownLatch(1)
        val clients=observeSecondClient(store,secondObserved)
        val calls=AtomicInteger();val executor=Executors.newFixedThreadPool(2)
        val unavailable=IOException("Temporary server or network failure")
        val renew:(String)->SessionTokens?={
            calls.incrementAndGet();entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));throw unavailable
        }
        try {
            val first=executor.submit<SessionTokens?> { coordinator.refresh(stale,clients,renew) }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val second=executor.submit<SessionTokens?> { coordinator.refresh(stale,clients,renew) }
            assertTrue(secondObserved.await(3,TimeUnit.SECONDS));assertFalse(second.isDone)
            release.countDown()
            listOf(first,second).forEach { future ->
                try { future.get(3,TimeUnit.SECONDS);fail("The transport failure must reach the caller") }
                catch(failure:ExecutionException) { assertSame(unavailable,failure.cause) }
            }
            assertEquals(1,calls.get());assertEquals(stale,store.snapshot())
            assertEquals(tokens("retry"),coordinator.refresh(stale,store) { tokens("retry") })
        } finally { release.countDown();executor.shutdownNow() }
    }

    private fun assertSessionChangeDuringRefresh(replacement:SessionTokens?,reject:Boolean=false) {
        val original=tokens("old");val store=MemoryStore(original);val stale=store.snapshot()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<SessionTokens?> { SessionRefreshCoordinator().refresh(stale,store) {
                entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));if(reject)null else tokens("rotated")
            } }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            if(replacement==null)store.logout() else store.login(replacement)
            val expected=store.snapshot();release.countDown()
            assertNull(result.get(3,TimeUnit.SECONDS));assertEquals(expected,store.snapshot())
        } finally { release.countDown();executor.shutdownNow() }
    }

    @Test fun refreshCannotOverwriteANewLogin() { assertSessionChangeDuringRefresh(tokens("new-login")) }
    @Test fun refreshCannotResurrectALoggedOutSession() { assertSessionChangeDuringRefresh(null) }
    @Test fun refreshCannotOverwriteRestoredIdenticalTokens() { assertSessionChangeDuringRefresh(tokens("old")) }
    @Test fun rejectedOldRefreshCannotClearANewLogin() { assertSessionChangeDuringRefresh(tokens("new-login"),reject=true) }

    @Test fun staleRequestDoesNotRefreshANewUsersSession() {
        val store=MemoryStore(tokens("old"));val stale=store.snapshot();store.login(tokens("another-user"))
        assertNull(SessionRefreshCoordinator().refresh(stale,store) { fail("Must not refresh a different session");null })
    }
}
