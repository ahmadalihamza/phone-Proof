package pk.phoneproof.diagnostics

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic observation contracts; these cases do not claim a phone was inspected. */
class DiagnosticProbePlannerTest {
    private fun definition(handler: String, field: String, type: String? = null) = DiagnosticDefinition(
        "$handler.${type?.let { "$it." }.orEmpty()}$field", "Synthetic check", "sensors", handler = handler,
        mode = "GUIDED_AUTOMATIC", implementation = "ENABLED", parameters = mapOf("field" to field) +
            if (type == null) emptyMap() else mapOf("type" to type),
        passCriteria = "Observed criterion", failCriteria = "Observed error", notSupported = "Absent",
        notAccessible = "Blocked", evidenceType = "PLATFORM_MEASUREMENT",
    )
    private val sensor = listOf("response", "continuity", "variation").map { definition("sensor_sample", it, "1") }
    private val microphone = listOf("capture", "amplitude", "silence", "clipping").map { definition("microphone", it) }
    private val storage = listOf("create", "write", "read", "checksum", "overwrite", "delete").map { definition("storage", it) }
    private fun observed(d: DiagnosticDefinition, values: Map<String, String>): DiagnosticResult {
        val a = checkNotNull(ProbeObservations.assess(d.handler, d.parameters.getValue("field"), values))
        return DiagnosticResult(d.id, a.status, a.values, a.values, a.message,
            startedAt = "2026-10-27T08:00:00Z", completedAt = "2026-10-27T08:00:03Z", definitionVersion = d.version)
    }
    private fun micValues() = mapOf("probe_kind" to "builtin_pcm_capture", "read_error" to "0", "samples" to "32000",
        "rms" to "200", "clipped_samples" to "0", "audio_route_verified" to "true", "capture_window_complete" to "true")
    private suspend fun batch(defs: List<DiagnosticDefinition>, values: Map<String, String>, calls: MutableList<String> = mutableListOf()) =
        DiagnosticBatchExecutor.execute(ProbeBatch("test", defs), { null }) { d -> calls += d.id; observed(d, values) }

    @Test fun plannerPartitionsOnceWithoutMixingSensorsOrEligibility() {
        val other = sensor.map { it.copy(id = it.id.replace(".1.", ".4."), parameters = it.parameters + ("type" to "4")) }
        val permission = sensor.first().copy(id = "sensor.permission", permissions = listOf("extra.permission"))
        val version = sensor.first().copy(id = "sensor.version", version = 2)
        val timeout = sensor.first().copy(id = "sensor.timeout", timeoutSeconds = 30)
        val extra = sensor.first().copy(id = "sensor.parameter", parameters = sensor.first().parameters + ("sample_mode" to "different"))
        val input = storage + microphone + sensor + other + listOf(permission, version, timeout, extra)
        val planned = DiagnosticProbePlanner.batches(input)
        assertEquals(8, planned.size)
        assertEquals(input.map { it.id }.toSet(), planned.flatMap { it.definitions }.map { it.id }.toSet())
        assertEquals(input.size, planned.sumOf { it.definitions.size })
        assertEquals(2, planned.count { it.definitions.size == 3 })
    }

    @Test fun unknownCriteriaAndDifferentOperationsAreNeverShared() {
        val unknown = definition("sensor_sample", "future_criterion", "1")
        val satellite = definition("location", "satellites")
        val fix = definition("location", "fix")
        assertEquals(3, DiagnosticProbePlanner.batches(listOf(unknown, satellite, fix)).size)
        assertNull(ProbeObservations.assess("sensor_sample", "future_criterion", emptyMap()))
        try { DiagnosticProbePlanner.batches(listOf(fix, fix)); fail("Duplicate input must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun oneCompleteStorageRoundTripRetainsAllSixOriginalResults() = runBlocking {
        val calls = mutableListOf<String>()
        val values = mapOf("probe_kind" to "private_storage_round_trip", "round_trip_complete" to "true",
            "bytes_verified" to "262144", "verified_operations" to "create,write,read,checksum,overwrite,delete")
        val results = batch(storage, values, calls)
        assertEquals(listOf("storage.checksum"), calls)
        assertEquals(storage.map { it.id }, results.map { it.checkId })
        assertTrue(results.all { it.status == DiagnosticStatus.PASS })
        results.zip(storage).forEach { (r, d) ->
            assertEquals(d.version, r.definitionVersion); assertEquals(d.parameters["field"], r.measuredValue["operation"])
            assertEquals("2026-10-27T08:00:03Z", r.completedAt)
        }
    }

    @Test fun genericPassedStatusWithoutCompletedProbeEvidenceDoesNotGetReused() = runBlocking {
        val calls = mutableListOf<String>()
        val results = DiagnosticBatchExecutor.execute(ProbeBatch("test", storage), { null }) { d ->
            calls += d.id; DiagnosticResult(d.id, DiagnosticStatus.PASS, mapOf("unrelated" to "value"))
        }
        assertEquals(6, calls.size)
        assertEquals(6, results.size)
        assertTrue(results.all { "shared_probe_source_id" !in it.evidence })
    }

    @Test fun partialStorageFailureCannotMakeUnobservedOperationsPass() = runBlocking {
        var calls = 0
        val results = DiagnosticBatchExecutor.execute(ProbeBatch("test", storage), { null }) { d ->
            calls++; DiagnosticResult(d.id, DiagnosticStatus.FAIL, mapOf("deleted" to "false"))
        }
        assertEquals(6, calls)
        assertTrue(results.none { it.status == DiagnosticStatus.PASS })
    }

    @Test fun aRepresentativeStatusThatDisagreesWithItsEvidenceIsNeverProjected() = runBlocking {
        for (status in listOf(DiagnosticStatus.INCONCLUSIVE, DiagnosticStatus.FAIL, DiagnosticStatus.NOT_ACCESSIBLE)) {
            val calls = mutableListOf<String>()
            val results = DiagnosticBatchExecutor.execute(ProbeBatch("inconsistent", microphone), { null }) { d ->
                calls += d.id
                DiagnosticResult(d.id, status, micValues(), message = "Synthetic inconsistent result")
            }
            assertEquals(microphone.size, calls.size)
            assertTrue(results.none { it.status == DiagnosticStatus.PASS })
            assertTrue(results.all { "shared_probe_source_id" !in it.evidence })
        }
    }

    @Test fun malformedVariationVectorsAreRejectedInsteadOfDroppingTheirInvalidAxes() {
        val values = SensorWindowAnalysis.values(listOf(TimedSensorSample(10, listOf(1f, 2f)), TimedSensorSample(20, listOf(2f, 3f))), 0, 30)
        for (variation in listOf("1.0,invalid", "invalid,1.0", "1.0,", "", "NaN,1.0", "-1.0,1.0")) {
            assertNull(ProbeObservations.assess("sensor_sample", "variation", values + ("variation" to variation)))
        }
    }

    @Test fun futureAndStaleFixesCannotBeProjectedAsAValidLocation() = runBlocking {
        val definitions = listOf(definition("location", "fix"), definition("location", "accuracy"))
        for(age in listOf("-1", "10000", "999999")) {
            val calls = mutableListOf<String>()
            val results = batch(definitions, mapOf("probe_kind" to "fresh_location_fix", "fix_observed" to "true", "age_ms" to age, "accuracy_m" to "1.0"), calls)
            assertEquals(1,calls.size)
            assertTrue(results.all { it.status == DiagnosticStatus.INCONCLUSIVE })
        }
    }

    @Test fun constantSensorWindowHasResponseAndContinuityButNoProvenVariation() = runBlocking {
        val values = SensorWindowAnalysis.values(listOf(TimedSensorSample(10, listOf(1f, 2f)), TimedSensorSample(20, listOf(1f, 2f))), 0, 30)
        val calls = mutableListOf<String>()
        val results = batch(sensor, values, calls).associateBy { it.checkId }
        assertEquals(1, calls.size)
        assertEquals(DiagnosticStatus.PASS, results.getValue("sensor_sample.1.response").status)
        assertEquals(DiagnosticStatus.PASS, results.getValue("sensor_sample.1.continuity").status)
        assertEquals(DiagnosticStatus.INCONCLUSIVE, results.getValue("sensor_sample.1.variation").status)
    }

    @Test fun oneFreshSampleDoesNotProveContinuityAndStaleSamplesDoNotProveResponse() = runBlocking {
        val single = SensorWindowAnalysis.values(listOf(TimedSensorSample(15, listOf(1f))), 10, 20)
        assertEquals(DiagnosticStatus.INCONCLUSIVE, batch(sensor, single).first { it.checkId.endsWith("continuity") }.status)
        val stale = SensorWindowAnalysis.values(listOf(TimedSensorSample(5, listOf(1f)), TimedSensorSample(25, listOf(2f))), 10, 20)
        val calls = mutableListOf<String>()
        val results = batch(sensor, stale, calls)
        assertEquals(1, calls.size)
        assertTrue(results.all { it.status == DiagnosticStatus.INCONCLUSIVE })
        assertEquals("2", results.first().measuredValue["outside_window_samples"])
    }

    @Test fun malformedOrNonMonotonicSensorStreamNeverBecomesPassed() = runBlocking {
        for (samples in listOf(
            listOf(TimedSensorSample(20, listOf(1f)), TimedSensorSample(10, listOf(2f))),
            listOf(TimedSensorSample(10, listOf(Float.NaN))),
            listOf(TimedSensorSample(10, listOf(1f)), TimedSensorSample(20, listOf(1f, 2f))),
        )) {
            val results = batch(sensor, SensorWindowAnalysis.values(samples, 0, 30))
            assertTrue(results.all { it.status == DiagnosticStatus.FAIL })
        }
    }

    @Test fun quietOrClippedAudioCanProveCaptureWithoutPassingSignalIndicators() = runBlocking {
        for (values in listOf(micValues() + ("rms" to "0"), micValues() + ("clipped_samples" to "400"))) {
            val calls = mutableListOf<String>()
            val results = batch(microphone, values, calls)
            assertEquals(1, calls.size)
            assertEquals(DiagnosticStatus.PASS, results.first { it.checkId.endsWith("capture") }.status)
            assertTrue(results.filterNot { it.checkId.endsWith("capture") }.all { it.status == DiagnosticStatus.INCONCLUSIVE })
        }
    }

    @Test fun unstableOrInterruptedMicrophoneWindowCannotPassAnyCriterion() = runBlocking {
        for (values in listOf(micValues() + ("audio_route_verified" to "false"), micValues() + ("capture_window_complete" to "false"))) {
            assertTrue(batch(microphone, values).all { it.status == DiagnosticStatus.INCONCLUSIVE })
        }
    }

    @Test fun freshFixWithoutAccuracyDoesNotInventAnAccuracyMeasurement() = runBlocking {
        val definitions = listOf(definition("location", "fix"), definition("location", "accuracy"))
        val calls = mutableListOf<String>()
        val results = batch(definitions, mapOf("probe_kind" to "fresh_location_fix", "fix_observed" to "true",
            "age_ms" to "10", "accuracy_m" to "unavailable"), calls)
        assertEquals(1, calls.size)
        assertEquals(DiagnosticStatus.PASS, results[0].status)
        assertEquals(DiagnosticStatus.NOT_ACCESSIBLE, results[1].status)
    }

    @Test fun eachDefinitionKeepsItsOwnPermissionGate() = runBlocking {
        val calls = mutableListOf<String>()
        val results = DiagnosticBatchExecutor.execute(ProbeBatch("mixed", microphone), { d ->
            if (d.id.endsWith("silence")) DiagnosticResult(d.id, DiagnosticStatus.NOT_ACCESSIBLE, message = "Permission changed") else null
        }) { d -> calls += d.id; observed(d, micValues()) }
        assertEquals(1, calls.size)
        assertEquals(DiagnosticStatus.NOT_ACCESSIBLE, results.first { it.checkId.endsWith("silence") }.status)
        assertEquals(3, results.count { it.status == DiagnosticStatus.PASS })
    }

    @Test fun cancellationReleasesTheProbeAndPublishesNoPartialSuccessfulBatch() = runBlocking {
        var released = false
        var returned = false
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            DiagnosticBatchExecutor.execute(ProbeBatch("cancel", sensor), { null }) {
                calls++
                try { awaitCancellation() } finally { released = true }
            }
            returned = true
        }
        job.cancelAndJoin()
        assertEquals(1, calls); assertTrue(released); assertFalse(returned)
    }
}
