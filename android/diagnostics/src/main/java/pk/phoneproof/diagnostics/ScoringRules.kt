package pk.phoneproof.diagnostics

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

@Serializable
data class ScoreCategoryRule(val id:String,val title:String,val weight:Int,val members:List<String>)

@Serializable
data class FailureCapRule(val prefix:String,val suffix:String,val maximum:Int,val reason:String)

@Serializable
data class GradeBand(val minimum:Int,val grade:String)

@Serializable
data class ScoringRule(
    val version:Int,
    val algorithm:String,
    val categories:List<ScoreCategoryRule> = emptyList(),
    @SerialName("default_critical_cap") val defaultCriticalCap:Int = 79,
    @SerialName("failure_caps") val failureCaps:List<FailureCapRule> = emptyList(),
    @SerialName("grade_bands") val gradeBands:List<GradeBand> = listOf(GradeBand(90,"A"),GradeBand(80,"B"),GradeBand(70,"C"),GradeBand(55,"D"),GradeBand(0,"E")),
) {
    fun grade(score:Double?):String? = score?.let { value -> gradeBands.first { value>=it.minimum }.grade }
    fun cap(definition:DiagnosticDefinition):ScoreCap? {
        val specific=failureCaps.filter {
            if(it.suffix.isEmpty())definition.id==it.prefix else definition.id.startsWith(it.prefix)&&definition.id.endsWith(it.suffix)
        }.minByOrNull { it.maximum }
        if(specific!=null)return ScoreCap(definition.id,specific.maximum,specific.reason)
        return if(definition.critical)ScoreCap(definition.id,defaultCriticalCap,"Critical functional failure") else null
    }
    companion object { fun legacy()=ScoringRule(1,"check_weighted") }
}

@Serializable
data class ScoringRulebook(@SerialName("current_version") val currentVersion:Int,val rules:List<ScoringRule>) {
    fun rule(version:Int):ScoringRule = rules.first { it.version==version }
    companion object {
        private val json=Json { ignoreUnknownKeys=true }
        fun parse(value:String):ScoringRulebook = json.decodeFromString(value)
        fun load(context:Context):ScoringRulebook = parse(context.assets.open("scoring-rules.json").bufferedReader().use { it.readText() })
    }
}

data class CategoryScore(val id:String,val title:String,val weightPercent:Int,val score:Double?,val coveragePercent:Double?,val applicableWeight:Double,val measuredWeight:Double)
data class ScoreCap(val checkId:String,val maximumScore:Int,val reason:String)

object DiagnosticAttempts {
    /** ISO timestamps must be compared as instants, including offset timestamps. Ties retain the last attempt. */
    fun latest(results:List<DiagnosticResult>):Map<String,DiagnosticResult> {
        val selected=linkedMapOf<String,DiagnosticResult>()
        for(result in results) {
            val previous=selected[result.checkId]
            if(previous==null||!Instant.parse(result.completedAt).isBefore(Instant.parse(previous.completedAt)))selected[result.checkId]=result
        }
        return selected
    }
}

internal object CategoryScoring {
    private val math=MathContext.DECIMAL128
    private val zero=BigDecimal.ZERO
    private val hundred=BigDecimal(100)
    private fun decimal(value:Double)=BigDecimal.valueOf(value)
    private fun rounded(value:BigDecimal)=value.setScale(2,RoundingMode.HALF_UP).toDouble()
    private fun ratio(a:BigDecimal,b:BigDecimal)=a.divide(b,math)

    fun calculate(definitions:List<DiagnosticDefinition>,results:List<DiagnosticResult>,rule:ScoringRule):ScorePreview {
        val latest=DiagnosticAttempts.latest(results)
        val weighted=definitions.filter { it.implementation=="ENABLED"&&it.weight>0 }.sortedBy { it.id }
        val members=rule.categories.flatMap { it.members }.toSet()
        require(weighted.all { it.category in members }) { "Scored category is not covered by the scoring policy" }
        val applicable=weighted.filter { latest[it.id]?.status!=DiagnosticStatus.NOT_SUPPORTED }
        val measured=applicable.filter { latest[it.id]?.status in setOf(DiagnosticStatus.PASS,DiagnosticStatus.FAIL) }
        val measuredIds=measured.map { it.id }.toSet()
        val caps=measured.filter { latest[it.id]?.status==DiagnosticStatus.FAIL }.mapNotNull { rule.cap(it) }
        var applicableShare=zero
        var measuredShare=zero
        var earnedShare=zero
        var coverageShare=zero
        val categories=rule.categories.map { category ->
            val checks=applicable.filter { it.category in category.members }
            val completed=checks.filter { it.id in measuredIds }
            val possible=checks.fold(zero) { total,d -> total+decimal(d.weight) }
            val tested=completed.fold(zero) { total,d -> total+decimal(d.weight) }
            val passed=completed.filter { latest[it.id]?.status==DiagnosticStatus.PASS }.fold(zero) { total,d -> total+decimal(d.weight) }
            val share=BigDecimal(category.weight)
            if(possible>zero) {
                applicableShare+=share
                coverageShare+=ratio(share*tested,possible)
            }
            if(tested>zero) {
                measuredShare+=share
                earnedShare+=ratio(share*passed,tested)
            }
            CategoryScore(category.id,category.title,category.weight,
                if(tested>zero)rounded(ratio(hundred*passed,tested)) else null,
                if(possible>zero)rounded(ratio(hundred*tested,possible)) else null,possible.toDouble(),tested.toDouble())
        }
        val uncapped=if(measuredShare>zero)rounded(ratio(hundred*earnedShare,measuredShare)) else null
        val value=uncapped?.let { minOf(it,caps.minOfOrNull { cap -> cap.maximumScore.toDouble() }?:100.0) }
        return ScorePreview(value,measured.sumOf { it.weight },applicable.sumOf { it.weight },
            applicable.filter { it.id !in measuredIds }.map { it.id },caps.map { it.checkId },rule.version,
            if(applicableShare>zero)rounded(ratio(hundred*coverageShare,applicableShare)) else 0.0,categories,caps,rule.grade(value))
    }
}
