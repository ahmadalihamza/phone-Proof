package pk.phoneproof.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ScoringPolicyTest {
    @Test fun bundledRulesCoverEveryWeightedRegistryCategory() {
        val rules=ScoringContractTest.rules
        assertEquals(2,rules.currentVersion)
        assertEquals("check_weighted",rules.rule(1).algorithm)
        assertEquals(100,rules.rule(2).categories.sumOf { it.weight })
        val json=checkNotNull(javaClass.classLoader?.getResourceAsStream("diagnostics.json")).bufferedReader().use { it.readText() }
        val definitions=DiagnosticRegistry.json.decodeFromString<List<DiagnosticDefinition>>(json)
        val members=rules.rule(2).categories.flatMap { it.members }
        assertEquals(members.toSet().size,members.size)
        assertTrue(definitions.filter { it.implementation=="ENABLED"&&it.weight>0 }.all { it.category in members })
    }
}
