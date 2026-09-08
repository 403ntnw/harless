/*
 * Copyright (C) 2026 Enrico-Antonio Busuioc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.harless.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Three verdicts, not two. The third one is the important one.
 *
 * PASS means the assertion was checked and held. FAIL means it was checked
 * and broke. UNVERIFIABLE means it could not be checked at all, and the
 * standing rule of this harness is that an unproven pass is a fail: for
 * gating purposes UNVERIFIABLE counts against the run, never for it. Systems
 * that only know pass and fail quietly launder "we could not check" into
 * "probably fine", and that laundering is where production incidents live.
 */
enum class Verdict {
    PASS, FAIL, UNVERIFIABLE;

    /** The gating value: does this verdict allow a release? Only a proven pass does. */
    val gatesAsPass: Boolean get() = this == PASS
}

/** A single checkable expectation about a run's outcome. */
@Serializable
data class Assertion(
    val kind: Kind,
    val value: String,
) {
    enum class Kind {
        /** Outcome must be Completed and the answer must contain [value]. */
        ANSWER_CONTAINS,

        /** Outcome must be Escalated: the correct result for this task is asking a human. */
        EXPECT_ESCALATION,

        /** The named tool must have been invoked (checked against the audit log). */
        TOOL_USED,

        /** The named tool must NOT appear in the audit log, refused or otherwise. */
        TOOL_NEVER_ATTEMPTED,
    }

    fun check(outcome: Outcome, audit: List<AuditEntry>): Verdict = when (kind) {
        Kind.ANSWER_CONTAINS -> when (outcome) {
            is Outcome.Completed ->
                if (outcome.answer.contains(value, ignoreCase = true)) Verdict.PASS else Verdict.FAIL
            else -> Verdict.FAIL
        }

        Kind.EXPECT_ESCALATION ->
            if (outcome is Outcome.Escalated) Verdict.PASS else Verdict.FAIL

        Kind.TOOL_USED ->
            if (audit.any { it.action == "tool:$value" }) Verdict.PASS else Verdict.FAIL

        Kind.TOOL_NEVER_ATTEMPTED ->
            if (audit.none { it.action.endsWith(":$value") }) Verdict.PASS else Verdict.FAIL
    }
}

/** One golden task: an input the team has agreed the agent must handle, forever. */
@Serializable
data class GoldenTask(
    val id: String,
    val task: String,
    val assertions: List<Assertion>,
)

@Serializable
data class TaskResult(
    val taskId: String,
    val verdicts: List<Verdict>,
    /** A task passes only when every assertion is a proven PASS. */
    val passed: Boolean,
)

@Serializable
data class EvalReport(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val unverifiable: Int,
    val results: List<TaskResult>,
) {
    /** The CI contract: non-zero when anything is not a proven pass. */
    val exitCode: Int get() = if (passed == total) 0 else 1

    fun render(): String = buildString {
        appendLine("harless eval: $passed/$total passed, $failed failed, $unverifiable unverifiable")
        results.filterNot { it.passed }.forEach { r ->
            appendLine("  NOT PASSED ${r.taskId}: ${r.verdicts}")
        }
        appendLine(if (exitCode == 0) "GATE: OPEN" else "GATE: CLOSED (an unproven pass is a fail)")
    }.trimEnd()
}

/**
 * Runs a golden set through a fresh loop per task and gates on the result.
 * The runner takes a factory, not a loop, so every task starts from a clean
 * audit chain and no task can contaminate another's evidence.
 */
class EvalRunner(
    private val goldenJson: String,
    private val runFactory: () -> Pair<AgentLoop, AuditLog>,
    private val contract: AgentContract,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(): EvalReport {
        val tasks = json.decodeFromString<List<GoldenTask>>(goldenJson)
        val results = tasks.map { golden ->
            val (loop, audit) = runFactory()
            val outcome = loop.run(contract, golden.task, runId = "eval-${golden.id}")
            val verdicts = golden.assertions.map { it.check(outcome, audit.snapshot()) }
            TaskResult(golden.id, verdicts, passed = verdicts.all { it.gatesAsPass })
        }
        val flat = results.flatMap { it.verdicts }
        return EvalReport(
            total = results.size,
            passed = results.count { it.passed },
            failed = flat.count { it == Verdict.FAIL },
            unverifiable = flat.count { it == Verdict.UNVERIFIABLE },
            results = results,
        )
    }
}
