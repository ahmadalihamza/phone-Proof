package pk.phoneproof.diagnostics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An incomplete/unknown observation falls back to execution; it never becomes synthetic success. */
internal object DiagnosticBatchExecutor {
    suspend fun execute(batch: ProbeBatch,
        blocked: (DiagnosticDefinition) -> DiagnosticResult?,
        run: suspend (DiagnosticDefinition) -> DiagnosticResult,
    ): List<DiagnosticResult> {
        require(batch.definitions.map { it.id }.distinct().size == batch.definitions.size) { "Duplicate diagnostic definitions" }
        val results = mutableMapOf<String, DiagnosticResult>()
        val eligible = batch.definitions.filter { definition ->
            blocked(definition)?.let { result ->
                check(result.checkId == definition.id && result.definitionVersion == definition.version)
                results[definition.id] = result
                true
            } != true
        }
        // Recompute safe groups even if a caller manually constructed a mixed batch.
        for (group in DiagnosticProbePlanner.batches(eligible)) {
            currentCoroutineContext().ensureActive()
            val representative = DiagnosticProbePlanner.representative(group.definitions)
            val source = run(representative)
            check(source.checkId == representative.id && source.definitionVersion == representative.version)
            results[representative.id] = source
            for (definition in group.definitions.filter { it.id != representative.id }) {
                currentCoroutineContext().ensureActive()
                results[definition.id] = project(representative, source, definition) ?: run(definition).also {
                    check(it.checkId == definition.id && it.definitionVersion == definition.version)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        return batch.definitions.map { results.getValue(it.id) }
    }

    private fun project(from: DiagnosticDefinition, source: DiagnosticResult, target: DiagnosticDefinition): DiagnosticResult? {
        if (DiagnosticProbePlanner.key(from) != DiagnosticProbePlanner.key(target)) return null
        // A status label alone is never reusable evidence. Inconsistent, interrupted or
        // incomplete representative output must be executed again for each criterion.
        val original = ProbeObservations.assess(from.handler, from.parameters["field"] ?: return null, source.measuredValue) ?: return null
        if (original.status != source.status) return null
        val observation = ProbeObservations.assess(target.handler, target.parameters["field"] ?: return null, source.measuredValue) ?: return null
        return source.copy(checkId = target.id, definitionVersion = target.version, method = target.handler,
            expectedCondition = target.passCriteria, status = observation.status, measuredValue = observation.values,
            evidence = source.evidence + observation.values + ("shared_probe_source_id" to from.id), message = observation.message)
    }
}
