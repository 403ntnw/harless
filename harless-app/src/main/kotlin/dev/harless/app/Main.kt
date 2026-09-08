/*
 * Copyright (C) 2026 Enrico-Antonio Busuioc
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the European Union Public Licence v. 1.2 (EUPL-1.2)
 * as published by the European Commission.
 */
package dev.harless.app

import dev.harless.core.AgentContract
import dev.harless.core.AgentLoop
import dev.harless.core.AuditLog
import dev.harless.core.EscalationPolicy
import dev.harless.core.EvalRunner
import dev.harless.core.ModelTurn
import dev.harless.core.Outcome
import dev.harless.core.ResourceLimits
import dev.harless.core.ScriptedProvider
import dev.harless.core.Tool
import dev.harless.core.ToolRegistry
import dev.harless.core.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The Harless demo application: a complete, deterministic, offline run of the
 * harness, compiled to a native executable with GraalVM. No keys, no network,
 * no cloud account: clone, build, run, read the audit chain.
 *
 * Commands:
 *   run    execute the demo agent under its contract and print outcome, trace and audit chain
 *   eval   run the golden evaluation set and exit non-zero if the gate is closed
 *   rogue  demonstrate the harness refusing an out-of-contract tool and capping a runaway loop
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "run" -> demoRun()
        "eval" -> exitProcess(evalRun())
        "rogue" -> rogueRun()
        else -> {
            println("harless: the harness your agent is missing")
            println("usage: harless <run|eval|rogue>")
            exitProcess(64)
        }
    }
}

private object ClockTool : Tool {
    override val name = "clock"
    override val description = "returns the current UTC instant"
    override val parameters = "none"
    override suspend fun invoke(args: JsonObject): ToolResult =
        ToolResult.Success(buildJsonObject { put("now", Instant.now().toString()) })
}

private object CalcTool : Tool {
    override val name = "calc"
    override val description = "adds two integers deterministically"
    override val parameters = "a: int, b: int"
    override suspend fun invoke(args: JsonObject): ToolResult {
        val a = args["a"]?.jsonPrimitive?.content?.toIntOrNull()
        val b = args["b"]?.jsonPrimitive?.content?.toIntOrNull()
        if (a == null || b == null) return ToolResult.Refused("calc requires integer arguments a and b")
        return ToolResult.Success(buildJsonObject { put("sum", a + b) })
    }
}

private fun demoContract() = AgentContract(
    agentId = "demo-agent",
    purpose = "demonstrate a contracted, audited, limited agent run",
    allowedTools = setOf("clock", "calc"),
    limits = ResourceLimits(maxTurns = 6, maxToolCalls = 4, wallClock = 30.seconds, toolTimeout = 5.seconds),
    escalation = EscalationPolicy.HANDOFF_TO_HUMAN,
)

private fun demoProvider() = ScriptedProvider(
    script = listOf(
        ModelTurn.ToolRequest("clock", buildJsonObject {}, "establish when this run happened"),
        ModelTurn.ToolRequest("calc", buildJsonObject { put("a", 19); put("b", 23) }, "compute the answer"),
        ModelTurn.FinalAnswer("The sum is 42, computed by a tool, not asserted by a model."),
    ),
)

private fun demoRun() = runBlocking {
    val audit = AuditLog()
    val json = Json { prettyPrint = false }
    val loop = AgentLoop(demoProvider(), ToolRegistry(listOf(ClockTool, CalcTool), audit)) { event ->
        println("trace ${json.encodeToString(event)}")
    }
    when (val outcome = loop.run(demoContract(), "What is 19 + 23, and when did you answer?")) {
        is Outcome.Completed -> println("outcome COMPLETED: ${outcome.answer}")
        is Outcome.Escalated -> println("outcome ESCALATED: ${outcome.reason}")
        is Outcome.Exhausted -> println("outcome EXHAUSTED at ${outcome.limit}")
        is Outcome.Failed -> println("outcome FAILED: ${outcome.reason}")
    }
    println("audit chain (${audit.size} entries, verify=${if (audit.verify() == null) "intact" else "TAMPERED"}):")
    println(audit.toJsonl())
}

private fun rogueRun() = runBlocking {
    println("A deliberately rogue script: asks for a forbidden tool, then loops forever.")
    val audit = AuditLog()
    val rogue = ScriptedProvider(
        script = listOf(ModelTurn.ToolRequest("shell", buildJsonObject { put("cmd", "rm -rf /") }, "escape")) +
            List(100) { ModelTurn.ToolRequest("clock", buildJsonObject {}, "burn budget") },
    )
    val loop = AgentLoop(rogue, ToolRegistry(listOf(ClockTool, CalcTool), audit))
    val outcome = loop.run(demoContract(), "do whatever it takes")
    println("outcome: $outcome")
    println("refusals recorded: ${audit.snapshot().count { it.action.startsWith("refused:") }}")
    println("tool calls executed: ${audit.snapshot().count { it.action.startsWith("tool:") }} (cap was 4)")
    println("The harness, not the model, decided both endings. That is the product.")
}

private fun evalRun(): Int = runBlocking {
    val golden = object {}.javaClass.getResource("/eval/golden.json")?.readText()
        ?: error("golden set missing from resources")
    val runner = EvalRunner(
        goldenJson = golden,
        runFactory = {
            val audit = AuditLog()
            AgentLoop(demoProvider(), ToolRegistry(listOf(ClockTool, CalcTool), audit)) to audit
        },
        contract = demoContract(),
    )
    val report = runner.run()
    println(report.render())
    report.exitCode
}
