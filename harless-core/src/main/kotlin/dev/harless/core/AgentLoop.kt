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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The terminal state of a run. Every variant carries enough context to act on without re-running. */
sealed interface Outcome {
    data class Completed(val answer: String) : Outcome

    /** The agent, or the harness on its behalf, handed the task to a human with full context. */
    data class Escalated(val reason: String, val transcript: List<Message>) : Outcome

    /** A resource limit stopped the run. [limit] names which one; that name is the whole point. */
    data class Exhausted(val limit: String, val transcript: List<Message>) : Outcome

    /** The run failed closed under [EscalationPolicy.FAIL_CLOSED]. */
    data class Failed(val reason: String) : Outcome
}

/** One structured trace event. Serialized as JSONL so any log pipeline can ingest it unchanged. */
@Serializable
data class TraceEvent(
    val runId: String,
    val seq: Int,
    val kind: String,
    val detail: String,
)

/**
 * The agent loop. Small on purpose: every safety property of the harness is
 * enforced in under a hundred lines you can audit in one sitting.
 *
 * The loop owns the limits, the tool boundary and the trace. The provider
 * proposes; the harness disposes. When anything runs out, the escalation
 * policy from the contract decides the ending, and the ending always carries
 * the transcript, because an escalation without context is just a louder
 * failure.
 */
class AgentLoop(
    private val provider: ModelProvider,
    private val registry: ToolRegistry,
    private val trace: (TraceEvent) -> Unit = {},
) {
    private val json = Json { prettyPrint = false }

    suspend fun run(contract: AgentContract, task: String, runId: String = "run-${System.nanoTime()}"): Outcome {
        val transcript = mutableListOf(
            Message(Message.Role.SYSTEM, "Purpose: ${contract.purpose}"),
            Message(Message.Role.USER, task),
        )
        var toolCalls = 0
        var seq = 0
        fun emit(kind: String, detail: String) = trace(TraceEvent(runId, seq++, kind, detail))

        emit("start", "agent=${contract.agentId} provider=${provider.id} task=$task")

        return try {
            withTimeout(contract.limits.wallClock) {
                repeat(contract.limits.maxTurns) {
                    when (val turn = provider.turn(transcript, registry.describeFor(contract))) {
                        is ModelTurn.FinalAnswer -> {
                            emit("final", turn.answer)
                            return@withTimeout Outcome.Completed(turn.answer)
                        }

                        is ModelTurn.Escalate -> {
                            emit("escalate", turn.reason)
                            return@withTimeout escalate(contract, turn.reason, transcript)
                        }

                        is ModelTurn.ToolRequest -> {
                            if (toolCalls >= contract.limits.maxToolCalls) {
                                emit("limit", "maxToolCalls=${contract.limits.maxToolCalls}")
                                return@withTimeout exhausted(contract, "maxToolCalls", transcript)
                            }
                            toolCalls++
                            emit("tool_request", "${turn.toolName} rationale=${turn.rationale}")
                            val result = registry.invoke(contract, turn.toolName, turn.args)
                            emit("tool_result", describe(result))
                            transcript += Message(Message.Role.ASSISTANT, "tool:${turn.toolName}(${turn.args})")
                            transcript += Message(Message.Role.TOOL, describe(result))
                        }
                    }
                }
                emit("limit", "maxTurns=${contract.limits.maxTurns}")
                exhausted(contract, "maxTurns", transcript)
            }
        } catch (e: TimeoutCancellationException) {
            emit("limit", "wallClock=${contract.limits.wallClock}")
            exhausted(contract, "wallClock", transcript)
        }
    }

    private fun escalate(contract: AgentContract, reason: String, transcript: List<Message>): Outcome =
        when (contract.escalation) {
            EscalationPolicy.HANDOFF_TO_HUMAN -> Outcome.Escalated(reason, transcript.toList())
            EscalationPolicy.FAIL_CLOSED -> Outcome.Failed(reason)
        }

    private fun exhausted(contract: AgentContract, limit: String, transcript: List<Message>): Outcome =
        when (contract.escalation) {
            EscalationPolicy.HANDOFF_TO_HUMAN -> Outcome.Exhausted(limit, transcript.toList())
            EscalationPolicy.FAIL_CLOSED -> Outcome.Failed("limit exhausted: $limit")
        }

    private fun describe(result: ToolResult): String = when (result) {
        is ToolResult.Success -> json.encodeToString(result.output)
        is ToolResult.Failure -> "FAILURE(${if (result.retryable) "retryable" else "terminal"}): ${result.reason}"
        is ToolResult.Refused -> "REFUSED: ${result.reason}"
    }
}
