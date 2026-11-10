package pk.phoneproof.diagnostics

import org.junit.Assert.*
import org.junit.Test

class InspectionWorkflowsTest {
    private fun definitions(): List<DiagnosticDefinition> = DiagnosticRegistry.json.decodeFromString(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("diagnostics.json")).bufferedReader().use { it.readText() },
    )

    @Test fun everyEnabledDefinitionIsAssignedExactlyOnceWithoutChangingItsContract() {
        val definitions = definitions()
        val before = definitions.toList()
        val assigned = InspectionWorkflows.manual(definitions).flatMap { it.definitions } +
            InspectionWorkflows.automatic(definitions) + InspectionWorkflows.advanced(definitions).flatMap { it.definitions }
        val enabled = definitions.filter { it.implementation == "ENABLED" }
        assertEquals(326, assigned.size)
        assertEquals(assigned.size, assigned.map { it.id }.distinct().size)
        assertEquals(enabled.toSet(), assigned.toSet())
        assertEquals(before, definitions)
        assertEquals(61, assigned.count { it.weight > 0 })
    }

    @Test fun oneTouchAndOneDisplayWorkflowRetainAllDistinctSubcriteria() {
        val workflows = InspectionWorkflows.manual(definitions())
        assertEquals(1, workflows.count { it.kind == "TOUCH" })
        assertEquals(1, workflows.count { it.kind == "DISPLAY" })
        assertEquals(14, workflows.single { it.kind == "TOUCH" }.definitions.size)
        assertEquals(17, workflows.single { it.kind == "DISPLAY" }.definitions.size)
        assertEquals(7, workflows.single { it.kind == "AUDIO" }.definitions.size)
        assertEquals(45, workflows.single { it.kind == "MOTION" }.definitions.size)
        assertTrue(workflows.single { it.kind == "TOUCH" }.definitions.any { it.id == "touch.multitouch" })
    }

    @Test fun cameraLensesStayIndependentAndMetadataDoesNotBecomeAWorkflowPass() {
        val definitions = definitions().flatMap { d -> if ("{camera_id}" !in d.id) listOf(d) else
            listOf("0", "1").map { DiagnosticRegistry.expandCamera(d, it, mapOf("camera.$it.flash" to true)) } }
        val workflows = InspectionWorkflows.manual(definitions)
        val cameras = workflows.filter { it.kind == "CAMERA" }
        assertEquals(2, cameras.size)
        assertTrue(cameras.all { it.definitions.size == 3 })
        assertEquals(243, InspectionWorkflows.automatic(definitions).size)
        assertEquals(64, definitions.count { it.implementation == "ENABLED" && it.weight > 0 })
        assertEquals(1, InspectionWorkflows.advanced(definitions).count { it.kind == "CONNECTION" })
        assertTrue(InspectionWorkflows.automatic(definitions).none { it.handler == "backend" })
    }

    @Test fun workflowOrderAndMembershipDoNotDependOnRegistryIterationOrder() {
        val definitions = definitions()
        assertEquals(InspectionWorkflows.manual(definitions), InspectionWorkflows.manual(definitions.reversed()))
        assertEquals(InspectionWorkflows.advanced(definitions), InspectionWorkflows.advanced(definitions.reversed()))
    }
}
