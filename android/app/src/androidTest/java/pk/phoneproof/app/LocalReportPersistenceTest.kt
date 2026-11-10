package pk.phoneproof.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pk.phoneproof.app.data.*
import pk.phoneproof.diagnostics.*
import java.io.File
import java.util.UUID

/** Private fixture databases only; no real user inspections, network calls or fabricated device passes. */
class LocalReportPersistenceTest {
    @get:Rule val migrations=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),PhoneProofDatabase::class.java)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationFromVersionOneKeepsUnsyncedHistoryAndAddsReportsWithoutReset() {
        migrateFixture(1)
    }

    @Test fun migrationFromVersionTwoKeepsUnsyncedHistoryAndAddsReportsWithoutReset() {
        migrateFixture(2)
    }

    private fun migrateFixture(version:Int) {
        val name="local-report-migration-$version.db"
        context.deleteDatabase(name)
        migrations.createDatabase(name,version).apply {
            execSQL("INSERT INTO inspections (id,createdAt,profile,ownerId,serverCreated,paused,serverUrl) VALUES ('legacy','2026-01-01T00:00:00Z','{}','original-account',1,1,'https://original.invalid/api/v1/')")
            execSQL("INSERT INTO results VALUES ('attempt','legacy','touch.grid','2026-01-01T00:00:01Z','historic-evidence',0,'retry-required')")
            execSQL("INSERT INTO captures VALUES ('capture','legacy','front','/private/fixture.jpg','2026-01-01T00:00:02Z','scanner','nonce','original-account',0,NULL,'https://original.invalid/api/v1/')")
            close()
        }
        try {
            migrations.runMigrationsAndValidate(name,3,true,PhoneProofDatabase.MIGRATION_1_2,PhoneProofDatabase.MIGRATION_2_3).apply {
                query("SELECT ownerId,paused,serverUrl,scoreRuleVersion,serverFinalized,definitionSnapshot FROM inspections WHERE id='legacy'").use {
                    assertTrue(it.moveToFirst());assertEquals("original-account",it.getString(0));assertEquals(1,it.getInt(1))
                    assertEquals("https://original.invalid/api/v1/",it.getString(2));assertEquals(1,it.getInt(3))
                    assertEquals(0,it.getInt(4));assertTrue(it.isNull(5))
                }
                query("SELECT payload,synced,syncError FROM results WHERE id='attempt'").use {
                    assertTrue(it.moveToFirst());assertEquals("historic-evidence",it.getString(0));assertEquals(0,it.getInt(1));assertEquals("retry-required",it.getString(2))
                }
                query("SELECT path,synced FROM captures WHERE id='capture'").use {
                    assertTrue(it.moveToFirst());assertEquals("/private/fixture.jpg",it.getString(0));assertEquals(0,it.getInt(1))
                }
                query("SELECT COUNT(*) FROM local_reports").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun repeatedSnapshotsKeepOneReportAllAttemptsAndReopenOffline()=runBlocking {
        val name="local-report-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,PhoneProofDatabase::class.java,name).build()
        val id=UUID.randomUUID().toString()
        try {
            val definitions=DiagnosticRegistry.definitions(context).filter { it.implementation=="ENABLED"&&!it.id.contains('{') }
            val weighted=definitions.first { it.weight>0 }.copy(title="Pinned historical title",version=99)
            val information=definitions.first { it.weight==0.0 }
            val profile=CapabilityProfile(36,emptyMap(),emptyList(),emptyMap(),mapOf("manufacturer" to "Test fixture","model" to "Persistence fixture","release" to "16","api" to "36"))
            db.dao().create(LocalInspection(id,"2026-01-01T00:00:00Z",DiagnosticRegistry.json.encodeToString(profile),scoreRuleVersion=1,definitionSnapshot=DiagnosticRegistry.json.encodeToString(listOf(weighted,information))))
            fun attempt(status:DiagnosticStatus,time:String)=DiagnosticResult(weighted.id,status,message="Persistence fixture; no device-health assertion",completedAt=time,definitionVersion=99)
            val skipped=attempt(DiagnosticStatus.SKIPPED_BY_USER,"2026-01-01T00:00:01Z")
            db.dao().record(LocalResult("first",id,weighted.id,skipped.completedAt,DiagnosticRegistry.json.encodeToString(skipped)))
            val repository=LocalReportRepository(context,db)
            val first=repository.save(id)
            val initial=JSONObject(first.payload)
            assertFalse(initial.getBoolean("server_verified"));assertTrue(initial.isNull("functional_score_preview"))
            assertEquals(1,initial.getInt("unverified"));assertEquals(0,initial.getInt("passed"))
            val failed=attempt(DiagnosticStatus.FAIL,"2026-01-01T00:00:02Z")
            db.dao().record(LocalResult("second",id,weighted.id,failed.completedAt,DiagnosticRegistry.json.encodeToString(failed)))
            val second=repository.save(id)
            repository.cacheCloud(id,"{\"fixture_cloud_cache\":true}")
            val third=repository.save(id)
            assertEquals(first.createdAt,second.createdAt)
            assertEquals(1,db.dao().observeReports().first().size)
            assertEquals(2,db.dao().results(id).size)
            assertEquals(2,db.dao().pending(id).size)
            assertEquals("{\"fixture_cloud_cache\":true}",third.cloudReportJson)
            val payload=JSONObject(third.payload)
            assertEquals(1,payload.getInt("problems"));assertEquals(0,payload.getInt("passed"));assertEquals(2,payload.getInt("diagnostic_count"))
            val check=payload.getJSONArray("checks").getJSONObject(0)
            assertEquals("Pinned historical title",check.getString("title"));assertEquals(99,check.getInt("definition_version"));assertEquals("FAIL",check.getString("status"))
            assertFalse(payload.getJSONArray("checks").getJSONObject(1).getBoolean("score_eligible"))
            db.close();db=Room.databaseBuilder(context,PhoneProofDatabase::class.java,name).build()
            assertEquals(third.payload,db.dao().report(id)?.payload)
            assertEquals(2,db.dao().pending(id).size)
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun savedLocalSummaryExportsAsReadablePaginatedPdf()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,PhoneProofDatabase::class.java).build()
        val directory=File(context.cacheDir,"local-report-test-${UUID.randomUUID()}")
        try {
            val profile=CapabilityProfile(36,emptyMap(),emptyList(),emptyMap(),mapOf("model" to "PDF fixture"))
            val definitions=DiagnosticRegistry.definitions(context).filter { it.implementation=="ENABLED"&&it.weight>0&&!it.id.contains('{') }
            val id=UUID.randomUUID().toString()
            db.dao().create(LocalInspection(id,"2026-01-01T00:00:00Z",DiagnosticRegistry.json.encodeToString(profile),scoreRuleVersion=1,definitionSnapshot=DiagnosticRegistry.json.encodeToString(definitions)))
            val report=LocalReportRepository(context,db).save(id)
            val file=LocalReportPdf.write(report,directory)
            assertTrue(file.length()>1000)
            val preview=File(context.cacheDir,"ux-smoke").apply { mkdirs() }
            file.copyTo(File(preview,"local-report-preview.pdf"),overwrite=true)
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("Full checklist must paginate",renderer.pageCount>1)
                    for(index in 0 until renderer.pageCount)renderer.openPage(index).use { page ->
                        assertTrue(page.width>0&&page.height>0)
                        val bitmap=android.graphics.Bitmap.createBitmap(page.width,page.height,android.graphics.Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(preview,"local-report-page-${index+1}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        bitmap.recycle()
                    }
                }
            }
            assertEquals(0,JSONObject(report.payload).getInt("passed"))
            assertEquals(definitions.size,JSONObject(report.payload).getInt("unverified"))
        } finally { db.close();directory.listFiles()?.forEach { it.delete() };directory.delete() }
    }
}
