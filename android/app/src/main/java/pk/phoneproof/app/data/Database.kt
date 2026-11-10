package pk.phoneproof.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="inspections")
data class LocalInspection(@PrimaryKey val id:String,val createdAt:String,val profile:String,val ownerId:String?=null,val serverCreated:Boolean=false,val paused:Boolean=false,val serverUrl:String?=null,
    @ColumnInfo(defaultValue="1") val scoreRuleVersion:Int=2,
    @ColumnInfo(defaultValue="0") val serverFinalized:Boolean=false,val cloudReportId:String?=null,val cloudVerificationUrl:String?=null,
    val syncError:String?=null,val definitionSnapshot:String?=null)

@Entity(tableName="local_reports",foreignKeys=[ForeignKey(entity=LocalInspection::class,parentColumns=["id"],childColumns=["inspectionId"],onDelete=ForeignKey.CASCADE)])
data class LocalReport(@PrimaryKey val inspectionId:String,val createdAt:String,val updatedAt:String,val payload:String,val payloadHash:String,val cloudReportJson:String?=null)

@Entity(tableName="results",foreignKeys=[ForeignKey(entity=LocalInspection::class,parentColumns=["id"],childColumns=["inspectionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("inspectionId")])
data class LocalResult(@PrimaryKey val id:String,val inspectionId:String,val checkId:String,val completedAt:String,val payload:String,val synced:Boolean=false,val syncError:String?=null)

@Entity(tableName="captures",indices=[Index("inspectionId")])
data class LocalCapture(@PrimaryKey val id:String,val inspectionId:String,val view:String,val path:String,val capturedAt:String,val scannerId:String,val nonce:String,val ownerId:String,val synced:Boolean=false,val error:String?=null,val serverUrl:String="")

@Dao
interface InspectionDao {
    @Query("SELECT * FROM inspections ORDER BY createdAt DESC") fun observeInspections():Flow<List<LocalInspection>>
    @Query("SELECT * FROM inspections ORDER BY createdAt DESC") suspend fun inspections():List<LocalInspection>
    @Query("SELECT * FROM inspections WHERE id=:id") suspend fun inspection(id:String):LocalInspection?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun create(item:LocalInspection)
    @Update suspend fun update(item:LocalInspection)
    @Query("UPDATE inspections SET serverFinalized=1,cloudReportId=:reportId,cloudVerificationUrl=:url WHERE id=:id") suspend fun markFinalized(id:String,reportId:String,url:String)
    @Query("SELECT * FROM results WHERE inspectionId=:id ORDER BY completedAt") fun observeResults(id:String):Flow<List<LocalResult>>
    @Query("SELECT * FROM results WHERE inspectionId=:id ORDER BY completedAt") suspend fun results(id:String):List<LocalResult>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun record(item:LocalResult)
    @Query("SELECT * FROM results WHERE inspectionId=:id AND synced=0 AND syncError IS NULL ORDER BY completedAt LIMIT 100") suspend fun pending(id:String):List<LocalResult>
    @Query("UPDATE results SET synced=1, syncError=NULL WHERE id IN (:ids)") suspend fun acknowledge(ids:List<String>)
    @Query("UPDATE results SET syncError=:error WHERE id IN (:ids)") suspend fun reject(ids:List<String>,error:String)
    @Query("UPDATE results SET syncError=NULL WHERE inspectionId=:id AND synced=0") suspend fun retry(id:String)
    @Insert suspend fun capture(item:LocalCapture)
    @Query("SELECT * FROM captures WHERE synced=0 AND error IS NULL AND ownerId=:owner ORDER BY capturedAt") suspend fun pendingCaptures(owner:String):List<LocalCapture>
    @Query("SELECT * FROM captures ORDER BY capturedAt DESC") fun observeCaptures():Flow<List<LocalCapture>>
    @Query("SELECT * FROM captures ORDER BY capturedAt DESC") suspend fun captures():List<LocalCapture>
    @Query("SELECT * FROM local_reports ORDER BY updatedAt DESC") fun observeReports():Flow<List<LocalReport>>
    @Query("SELECT * FROM local_reports WHERE inspectionId=:id") suspend fun report(id:String):LocalReport?
    @Upsert suspend fun putReport(report:LocalReport)
    @Query("SELECT EXISTS(SELECT 1 FROM inspections WHERE serverCreated=0) OR EXISTS(SELECT 1 FROM results WHERE synced=0 AND syncError IS NULL) OR EXISTS(SELECT 1 FROM captures WHERE synced=0 AND error IS NULL)") suspend fun hasPendingSync():Boolean
    @Query("UPDATE captures SET synced=:synced,error=:error WHERE id=:id") suspend fun captureStatus(id:String,synced:Boolean,error:String?)
}

@Database(entities=[LocalInspection::class,LocalResult::class,LocalCapture::class,LocalReport::class],version=3,exportSchema=true)
abstract class PhoneProofDatabase:RoomDatabase() {
    abstract fun dao():InspectionDao
    companion object {
        val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inspections ADD COLUMN scoreRuleVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
        val MIGRATION_2_3=object:Migration(2,3) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inspections ADD COLUMN serverFinalized INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE inspections ADD COLUMN cloudReportId TEXT")
                db.execSQL("ALTER TABLE inspections ADD COLUMN cloudVerificationUrl TEXT")
                db.execSQL("ALTER TABLE inspections ADD COLUMN syncError TEXT")
                db.execSQL("ALTER TABLE inspections ADD COLUMN definitionSnapshot TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_reports (inspectionId TEXT NOT NULL,createdAt TEXT NOT NULL,updatedAt TEXT NOT NULL,payload TEXT NOT NULL,payloadHash TEXT NOT NULL,cloudReportJson TEXT,PRIMARY KEY(inspectionId),FOREIGN KEY(inspectionId) REFERENCES inspections(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        @Volatile private var instance:PhoneProofDatabase?=null
        fun get(context:Context):PhoneProofDatabase=instance?:synchronized(this) {
            instance?:Room.databaseBuilder(context.applicationContext,PhoneProofDatabase::class.java,"phoneproof.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build().also { instance=it }
        }
    }
}
