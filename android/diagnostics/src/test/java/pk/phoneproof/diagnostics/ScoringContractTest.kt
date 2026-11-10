package pk.phoneproof.diagnostics

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScoringContractTest(private val name:String,private val example:JsonObject) {
    companion object {
        private fun resource(path:String)=checkNotNull(ScoringContractTest::class.java.classLoader?.getResourceAsStream(path)).bufferedReader().use { it.readText() }
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun cases():List<Array<Any>> = Json.parseToJsonElement(resource("scoring-examples.json")).jsonArray.map {
            arrayOf<Any>(it.jsonObject.getValue("name").jsonPrimitive.content,it.jsonObject)
        }
        val rules=ScoringRulebook.parse(resource("scoring-rules.json"))
    }

    @Test fun androidPreviewMatchesServerAcceptanceExample() {
        val definitions=example.getValue("definitions").jsonArray.map { entry ->
            val d=entry.jsonObject
            DiagnosticDefinition(d.getValue("id").jsonPrimitive.content,"Synthetic fixture",d.getValue("category").jsonPrimitive.content,
                implementation="ENABLED",weight=d.getValue("weight").jsonPrimitive.double,critical=d["critical"]?.jsonPrimitive?.boolean?:false,
                passCriteria="Fixture",failCriteria="Fixture",notSupported="Fixture",notAccessible="Fixture",evidenceType="FIXTURE",handler="fixture")
        }
        val results=example.getValue("results").jsonArray.map { entry ->
            val r=entry.jsonObject
            DiagnosticResult(r.getValue("check_id").jsonPrimitive.content,DiagnosticStatus.valueOf(r.getValue("status").jsonPrimitive.content),
                completedAt=r["completed_at"]?.jsonPrimitive?.content?:"2026-10-18T00:00:00Z")
        }
        val expected=example.getValue("expected").jsonObject
        val actual=ScoringPreview.calculate(definitions,results,rules.rule(2))
        val value=expected.getValue("score").jsonPrimitive.doubleOrNull
        if(value==null)assertNull(name,actual.score) else assertEquals(name,value,actual.score!!,0.000001)
        assertEquals(name,expected.getValue("coverage").jsonPrimitive.double,actual.coveragePercent,0.000001)
        assertEquals(name,expected.getValue("grade").jsonPrimitive.contentOrNull,actual.grade)
        assertEquals(name,expected.getValue("critical_failures").jsonArray.map { it.jsonPrimitive.content },actual.criticalFailures)
        assertEquals(actual,ScoringPreview.calculate(definitions.reversed(),results.reversed(),rules.rule(2)))
    }
}
