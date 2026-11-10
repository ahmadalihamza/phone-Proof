package pk.phoneproof.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import org.junit.Test
import org.junit.Rule
import androidx.room.testing.MigrationTestHelper
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import pk.phoneproof.app.data.*

class OfflinePersistenceTest {
    @get:Rule val migrations=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),PhoneProofDatabase::class.java)

    @Test fun scoringMigrationPreservesExistingInspectionAndUnsyncedEvidence() {
        val name="scoring-migration-test.db"
        migrations.createDatabase(name,1).apply {
            execSQL("INSERT INTO inspections VALUES ('legacy','2026-01-01T00:00:00Z','{}',NULL,0,0,NULL)")
            execSQL("INSERT INTO results VALUES ('attempt','legacy','touch.grid','2026-01-01T00:00:01Z','{}',0,NULL)")
            close()
        }
        migrations.runMigrationsAndValidate(name,2,true,PhoneProofDatabase.MIGRATION_1_2).apply {
            query("SELECT scoreRuleVersion FROM inspections WHERE id='legacy'").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
            query("SELECT synced FROM results WHERE id='attempt'").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            close()
        }
    }
    @Test fun unacknowledgedResultsSurviveDatabaseReopen()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="offline-test.db"
        context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,PhoneProofDatabase::class.java,name).build()
        try {
            db.dao().create(LocalInspection("inspection","2026-01-01T00:00:00Z","{}"))
            db.dao().record(LocalResult("attempt","inspection","touch.grid","2026-01-01T00:00:01Z","{}"))
            db.close()
            db=Room.databaseBuilder(context,PhoneProofDatabase::class.java,name).build()
            assertEquals(1,db.dao().pending("inspection").size)
            db.dao().acknowledge(listOf("attempt"))
            assertTrue(db.dao().pending("inspection").isEmpty())
            assertEquals(1,db.dao().results("inspection").size)
        } finally { db.close();context.deleteDatabase(name) }
    }
}
