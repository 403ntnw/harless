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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * The result of one tool invocation. Failure and refusal are ordinary results,
 * not exceptions: the loop reasons about them instead of crashing on them.
 */
sealed interface ToolResult {
    /** The tool did its work; [output] is a JSON value the model can read. */
    data class Success(val output: JsonObject) : ToolResult

    /** The tool could not do its work. [retryable] tells the loop whether trying again can help. */
    data class Failure(val reason: String, val retryable: Boolean) : ToolResult

    /** The harness refused to run the tool at all (outside contract, bad arguments, limits hit). */
    data class Refused(val reason: String) : ToolResult
}

/**
 * A typed side-effect boundary. Tools are the only way an agent touches the
 * world, which is exactly why they carry a schema and go through the registry:
 * consequential actions are selected from a typed menu, never composed as text.
 */
interface Tool {
    val name: String
    val description: String

    /** Human-readable parameter documentation surfaced to the model. */
    val parameters: String

    suspend fun invoke(args: JsonObject): ToolResult
}

/**
 * The registry is the enforcement point between an agent and its tools.
 *
 * Every invocation is checked against the contract's allow-list, bounded by
 * the per-call timeout, and written to the audit log before the result is
 * returned to the loop. A tool that is not in the contract does not error,
 * it is [ToolResult.Refused]: the distinction matters, because a refusal is
 * evidence of the harness working, and it is recorded as such.
 */
class ToolRegistry(tools: List<Tool>, private val audit: AuditLog) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names are not allowed" }
    }

    fun describeFor(contract: AgentContract): List<Tool> =
        byName.values.filter { it.name in contract.allowedTools }

    suspend fun invoke(contract: AgentContract, toolName: String, args: JsonObject): ToolResult {
        val tool = byName[toolName]
            ?: return refuse(contract, toolName, args, "unknown tool")
        if (toolName !in contract.allowedTools) {
            return refuse(contract, toolName, args, "tool not in contract allow-list")
        }
        val result = try {
            withTimeout(contract.limits.toolTimeout) { tool.invoke(args) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.Failure("timed out after ${contract.limits.toolTimeout}", retryable = true)
        } catch (e: IllegalArgumentException) {
            ToolResult.Refused("invalid arguments: ${e.message}")
        }
        audit.append(
            actor = contract.agentId,
            action = "tool:$toolName",
            detail = summarize(result),
        )
        return result
    }

    private fun refuse(contract: AgentContract, toolName: String, args: JsonObject, reason: String): ToolResult {
        audit.append(actor = contract.agentId, action = "refused:$toolName", detail = reason)
        return ToolResult.Refused(reason)
    }

    private fun summarize(result: ToolResult): String = when (result) {
        is ToolResult.Success -> "ok"
        is ToolResult.Failure -> "failure: ${result.reason}"
        is ToolResult.Refused -> "refused: ${result.reason}"
    }
}
