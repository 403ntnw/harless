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

import kotlin.time.Duration

/**
 * Hard resource ceilings for one agent run.
 *
 * The founding rule of this harness: an agent without limits is not an agent,
 * it is an incident waiting for a timestamp. Every limit here is mandatory and
 * validated at construction. There is no "unlimited" value on purpose.
 */
data class ResourceLimits(
    /** Maximum reasoning turns (model calls) before the run is stopped. */
    val maxTurns: Int,
    /** Maximum tool invocations across the whole run. */
    val maxToolCalls: Int,
    /** Wall-clock budget for the whole run. */
    val wallClock: Duration,
    /** Per-tool-call timeout. */
    val toolTimeout: Duration,
) {
    init {
        require(maxTurns in 1..10_000) { "maxTurns must be positive and bounded, got $maxTurns" }
        require(maxToolCalls in 0..100_000) { "maxToolCalls must be bounded, got $maxToolCalls" }
        require(wallClock.isPositive() && wallClock.isFinite()) { "wallClock must be positive and finite" }
        require(toolTimeout.isPositive() && toolTimeout.isFinite()) { "toolTimeout must be positive and finite" }
        require(toolTimeout <= wallClock) { "a single tool call may not outlive the whole run" }
    }
}

/** What the harness does when the agent cannot finish within its contract. */
enum class EscalationPolicy {
    /** Hand the full context to a human and stop. The default, and deliberately so. */
    HANDOFF_TO_HUMAN,

    /** Fail the run loudly. For fully unattended pipelines where a human queue does not exist. */
    FAIL_CLOSED,
}

/**
 * The contract an agent runs under. Nothing executes without one.
 *
 * A contract binds: an identity (who is acting), a purpose (why), an allow-list
 * of tools (what it may touch), resource limits (how much), and an escalation
 * policy (what happens when it cannot finish). The harness enforces all five;
 * the model is never trusted to self-limit.
 */
data class AgentContract(
    /** Stable identifier of the agent. Recorded on every audit entry. */
    val agentId: String,
    /** One-sentence purpose. Recorded in traces so a reviewer knows why the run existed. */
    val purpose: String,
    /** Names of tools this agent may invoke. Anything else is refused, not forwarded. */
    val allowedTools: Set<String>,
    val limits: ResourceLimits,
    val escalation: EscalationPolicy = EscalationPolicy.HANDOFF_TO_HUMAN,
) {
    init {
        require(agentId.isNotBlank()) { "agentId is mandatory" }
        require(purpose.isNotBlank()) { "purpose is mandatory: an agent that cannot state why it runs does not run" }
    }
}
