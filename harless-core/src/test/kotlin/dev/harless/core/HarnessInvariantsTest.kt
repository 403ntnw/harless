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

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * These tests are the contract of the harness, written as executable claims.
 * Each test name states an invariant; if one of these ever goes red, the
 * project has lost its reason to exist.
 */
class HarnessInvariantsTest {

    private fun limits(maxTurns: Int = 5, maxToolCalls: Int = 5) = ResourceLimits(
        maxTurns = maxTurns,
        maxToolCalls = maxToolCalls,
        wallClock = 10.seconds,
        toolTimeout = 2.seconds,
    )

    private fun contract(tools: Set<String> = setOf("echo")) = AgentContract(
        agentId = "test-agent",
        purpose = "exercise the harness invariants",
        allowedTools = tools,
        limits = limits(),
    )

    private val echoTool = object : Tool {
        override val name = "echo"
        override val description = "echoes its input"
        override val parameters = "text: string"
        override suspend fun invoke(args: JsonObject): ToolResult =
            ToolResult.Success(buildJsonObject { put("echo", args.toString()) })
    }

    @Test
    fun `a contract without limits cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> {
            ResourceLimits(maxTurns = 0, maxToolCalls = 5, wallClock = 10.seconds, toolTimeout = 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceLimits(maxTurns = 5, maxToolCalls = -1, wallClock = 10.seconds, toolTimeout = 1.seconds)
        }
    }

    @Test
    fun `a tool outside the contract is refused and the refusal is audited`() = runTest {
        val audit = AuditLog()
        val registry = ToolRegistry(listOf(echoTool), audit)
        val result = registry.invoke(contract(tools = emptySet()), "echo", buildJsonObject {})
        assertIs<ToolResult.Refused>(result)
        assertTrue(audit.snapshot().single().action.startsWith("refused:"))
    }

    @Test
    fun `the loop stops at maxTurns and names the limit it hit`() = runTest {
        val provider = ScriptedProvider(script = List(50) {
            ModelTurn.ToolRequest("echo", buildJsonObject { put("i", it) }, "loop forever")
        })
        val audit = AuditLog()
        val loop = AgentLoop(provider, ToolRegistry(listOf(echoTool), audit))
        val c = AgentContract("looper", "spin", setOf("echo"), ResourceLimits(3, 100, 10.seconds, 2.seconds))
        val outcome = loop.run(c, "spin please")
        assertIs<Outcome.Exhausted>(outcome)
        assertEquals("maxTurns", outcome.limit)
    }

    @Test
    fun `the loop stops at maxToolCalls before the provider can spend more`() = runTest {
        val provider = ScriptedProvider(script = List(50) {
            ModelTurn.ToolRequest("echo", buildJsonObject {}, "spend")
        })
        val audit = AuditLog()
        val loop = AgentLoop(provider, ToolRegistry(listOf(echoTool), audit))
        val c = AgentContract("spender", "spend", setOf("echo"), ResourceLimits(50, 2, 10.seconds, 2.seconds))
        val outcome = loop.run(c, "spend")
        assertIs<Outcome.Exhausted>(outcome)
        assertEquals("maxToolCalls", outcome.limit)
        assertEquals(2, audit.snapshot().count { it.action == "tool:echo" })
    }

    @Test
    fun `escalation carries the transcript so a human can act without re-running`() = runTest {
        val provider = ScriptedProvider(script = listOf(ModelTurn.Escalate("ambiguous requirement")))
        val loop = AgentLoop(provider, ToolRegistry(listOf(echoTool), AuditLog()))
        val outcome = loop.run(contract(), "do something underspecified")
        assertIs<Outcome.Escalated>(outcome)
        assertEquals("ambiguous requirement", outcome.reason)
        assertTrue(outcome.transcript.isNotEmpty())
    }

    @Test
    fun `the audit chain detects a single tampered entry`() {
        val audit = AuditLog()
        audit.append("a", "tool:echo", "ok")
        audit.append("a", "tool:echo", "ok")
        audit.append("a", "tool:echo", "ok")
        assertNull(audit.verify(), "an untouched chain must verify")

        val tampered = audit.snapshot().toMutableList()
        tampered[1] = tampered[1].copy(detail = "rewritten history")
        assertNotNull(audit.verify(tampered), "a tampered chain must be detected")
        assertEquals(1L, audit.verify(tampered))
    }

    @Test
    fun `an unverifiable verdict never gates as a pass`() {
        assertTrue(Verdict.PASS.gatesAsPass)
        assertTrue(!Verdict.FAIL.gatesAsPass)
        assertTrue(!Verdict.UNVERIFIABLE.gatesAsPass, "an unproven pass is a fail")
    }
}
