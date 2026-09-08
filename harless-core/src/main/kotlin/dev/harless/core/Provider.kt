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

import kotlinx.serialization.json.JsonObject

/** One message in the running transcript handed to the provider. */
data class Message(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

/**
 * What the model decided to do this turn. Escalation and refusal are
 * first-class decisions, not failure modes: a model that hands a task back
 * with its reasons intact is doing exactly what the contract asks of it.
 */
sealed interface ModelTurn {
    /** The model is done; [answer] is the final result of the run. */
    data class FinalAnswer(val answer: String) : ModelTurn

    /** The model wants a tool executed. The harness decides whether it may. */
    data class ToolRequest(val toolName: String, val args: JsonObject, val rationale: String) : ModelTurn

    /** The model concluded it cannot or should not finish, and says why. */
    data class Escalate(val reason: String) : ModelTurn
}

/**
 * The seam that keeps the harness model-independent.
 *
 * Everything above this interface is deterministic engineering: contracts,
 * limits, audit, evaluation. Everything below it is a vendor decision made in
 * configuration. Swapping providers must never change the safety properties
 * of a run, which is why the loop, not the provider, owns every limit.
 */
interface ModelProvider {
    val id: String

    suspend fun turn(transcript: List<Message>, tools: List<Tool>): ModelTurn
}

/**
 * A deterministic, scripted provider.
 *
 * This is not a mock hidden in the test tree; it is a shipped provider,
 * because determinism is a feature: golden evaluations, CI gates and
 * reproducible demos all run on it, with zero keys and zero network. The
 * probabilistic providers plug into the same interface; the harness cannot
 * tell the difference, which is precisely the design goal.
 */
class ScriptedProvider(
    override val id: String = "scripted",
    script: List<ModelTurn>,
) : ModelProvider {

    private val remaining = ArrayDeque(script)

    override suspend fun turn(transcript: List<Message>, tools: List<Tool>): ModelTurn =
        remaining.removeFirstOrNull()
            ?: ModelTurn.Escalate("script exhausted before a final answer: the run was under-specified")
}
