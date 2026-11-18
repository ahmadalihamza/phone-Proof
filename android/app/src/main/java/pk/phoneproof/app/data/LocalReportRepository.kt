package pk.phoneproof.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pk.phoneproof.diagnostics.*
import java.security.MessageDigest
import java.time.Instant

/** A durable local snapshot, never represented as a signed server certificate. */
class LocalReportRepository(private val context:Context,private val db:PhoneProofDatabase) {
    suspend fun save(inspectionId:String):LocalReport=withContext(Dispatchers.IO) {
        val rules=ScoringRulebook.load(context)
        db.withTransaction {
            val dao=db.dao()
            val inspection=requireNotNull(dao.inspection(inspectionId)) { "Inspection is unavailable." }
            val previous=dao.report(inspectionId)
            val profile=DiagnosticRegistry.json.decodeFromString<CapabilityProfile>(inspection.profile)
            val definitions=inspection.definitionSnapshot?.let { DiagnosticRegistry.json.decodeFromString<List<DiagnosticDefinition>>(it) }
                ?:DiagnosticRegistry.expanded(context,profile)
            val attempts=dao.results(inspectionId).map { DiagnosticRegistry.json.decodeFromString<DiagnosticResult>(it.payload) }
            val latest=DiagnosticAttempts.latest(attempts)
            val enabled=definitions.filter { it.implementation=="ENABLED" }
            val weighted=enabled.filter { it.weight>0 }
            val now=Instant.now().toString()
            val score=rules.rules.firstOrNull { it.version==inspection.scoreRuleVersion }?.let { ScoringPreview.calculate(definitions,attempts,it) }
            val checks=JSONArray()
            enabled.forEach { d ->
                val result=latest[d.id]
                checks.put(JSONObject().put("id",d.id).put("title",d.title).put("category",d.category)
                    .put("definition_version",d.version).put("score_eligible",d.weight>0).put("status",result?.status?.name?:"NOT_TESTED")
                    .put("method",d.mode).put("completed_at",result?.completedAt?:JSONObject.NULL)
                    .put("measured_value",result?.let { JSONObject(it.measuredValue) }?:JSONObject())
                    .put("evidence",result?.let { JSONObject(it.evidence) }?:JSONObject())
                    .put("message",result?.message?:"Not performed"))
            }
            fun count(status:DiagnosticStatus)=weighted.count { latest[it.id]?.status==status }
            val device=JSONObject()
            listOf("manufacturer","model","release","api").forEach { key -> device.put(key,profile.device[key]?:"Unavailable") }
            val payload=JSONObject().put("format_version",1).put("report_type","LOCAL_INSPECTION_SUMMARY")
                .put("inspection_id",inspectionId).put("inspection_started_at",inspection.createdAt)
                .put("saved_at",now).put("device",device).put("server_verified",false)
                .put("score_rule_version",inspection.scoreRuleVersion).put("functional_score_preview",score?.score?:JSONObject.NULL)
                .put("tested_coverage_percent",score?.coveragePercent?:JSONObject.NULL)
                .put("diagnostic_count",enabled.size).put("observed_diagnostic_count",enabled.count { latest.containsKey(it.id) })
                .put("passed",count(DiagnosticStatus.PASS)).put("problems",count(DiagnosticStatus.FAIL))
                .put("unavailable",count(DiagnosticStatus.NOT_SUPPORTED)+count(DiagnosticStatus.NOT_ACCESSIBLE))
                .put("unverified",weighted.count { latest[it.id]?.status !in setOf(DiagnosticStatus.PASS,DiagnosticStatus.FAIL,DiagnosticStatus.NOT_SUPPORTED,DiagnosticStatus.NOT_ACCESSIBLE) })
                .put("checks",checks).put("notice","Local observations and human assessments. Not a server-signed or laboratory certificate. Untested and unavailable checks remain explicit.")
            val assessment = ExpertAnalysis.analyze(attempts, AnalysisPolicy.load(context))
            payload.put("analysis", JSONObject(DiagnosticRegistry.json.encodeToString(ExpertAssessment.serializer(), assessment)))
            val serialized=payload.toString()
            val hash=MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            LocalReport(inspectionId,previous?.createdAt?:now,now,serialized,hash,previous?.cloudReportJson).also { dao.putReport(it) }
        }
    }

    suspend fun cacheCloud(inspectionId:String,json:String) {
        // Authenticated caller owns scope validation. A cloud cache never invents or replaces local observations.
        if(db.dao().report(inspectionId)==null)save(inspectionId)
        db.withTransaction {
            db.dao().report(inspectionId)?.let { db.dao().putReport(it.copy(cloudReportJson=json)) }
        }
    }
}
