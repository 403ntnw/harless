# Harless

**The harness your agent is missing.** A Kotlin agent harness that compiles to a native binary with GraalVM, built on one conviction: the model proposes, the harness disposes.

Harless is deliberately small. Its entire safety surface, contracts, limits, tool boundary, audit chain, evaluation gate, fits in a few files you can read in one sitting. That is the product: not another framework that orchestrates everything, but the enforcement layer that makes any orchestration accountable.

## The five rules

1. **No contract, no run.** Every agent executes under an `AgentContract`: identity, stated purpose, an allow-list of tools, resource limits and an escalation policy. There is no default contract and no way to construct an unlimited one; the constructor rejects it.

2. **Limits are mandatory, not advisory.** Max turns, max tool calls, wall-clock budget, per-tool timeout. The loop enforces them, the provider never sees them, and when one is hit the outcome names exactly which one. An agent without limits is not an agent, it is an incident waiting for a timestamp.

3. **Tools are the only hands.** Consequential actions are selected from a typed menu and executed by the registry, never composed as text by the model. A tool outside the contract is not an error, it is a `Refused` result, and the refusal is recorded, because a refusal is evidence of the harness working.

4. **Every action is chained.** The audit log is append-only and hash-chained: each entry commits to the one before it, so a single edited record breaks verification for everything after it. `verify()` returns the first broken link. Who did what, when, and what came back, provably unedited.

5. **An unproven pass is a fail.** Evaluation has three verdicts: `PASS`, `FAIL`, `UNVERIFIABLE`. Only a proven pass opens the gate. Systems that only know pass and fail quietly launder "we could not check" into "probably fine", and that is where production incidents live. The golden set runs in CI and a closed gate fails the build.

## Quickstart

```bash
# JVM
gradle build                          # compile + run the invariant tests
gradle :harless-app:run --args="run"   # a full contracted, audited demo run
gradle :harless-app:run --args="rogue" # watch the harness stop a rogue script
gradle :harless-app:run --args="eval"  # golden evaluation gate, exit code for CI

# Native (requires GraalVM 21+)
gradle :harless-app:nativeCompile
./harless-app/build/native/nativeCompile/harless run
```

The demo is deterministic and fully offline: the shipped `ScriptedProvider` implements the same `ModelProvider` interface a probabilistic backend would, with zero keys and zero network. Determinism is a feature, not a test double: golden evaluations, CI gates and reproducible demos all depend on it. Wiring a real LLM backend means implementing one interface with one method; every safety property above stays exactly where it is, in the loop, which is the point.

## What a run looks like

```
trace {"runId":"run-…","seq":0,"kind":"start","detail":"agent=demo-agent provider=scripted task=…"}
trace {"runId":"run-…","seq":1,"kind":"tool_request","detail":"clock rationale=establish when this run happened"}
trace {"runId":"run-…","seq":3,"kind":"tool_request","detail":"calc rationale=compute the answer"}
trace {"runId":"run-…","seq":5,"kind":"final","detail":"The sum is 42, computed by a tool, not asserted by a model."}
outcome COMPLETED: The sum is 42, computed by a tool, not asserted by a model.
audit chain (2 entries, verify=intact):
{"seq":0,"timestamp":"…","actor":"demo-agent","action":"tool:clock","detail":"ok","prevHash":"genesis","hash":"…"}
{"seq":1,"timestamp":"…","actor":"demo-agent","action":"tool:calc","detail":"ok","prevHash":"…","hash":"…"}
```

And the `rogue` command shows the other half: a script that first requests a forbidden tool, then tries to loop forever. The forbidden call is refused and audited; the loop dies at the tool-call cap. The harness, not the model, decides both endings.

## Architecture

```
harless-core   (Apache-2.0)          harless-app   (EUPL-1.2)
┌──────────────────────────────┐     ┌──────────────────────────────┐
│ AgentContract  ResourceLimits │     │ CLI: run · eval · rogue      │
│ AgentLoop      ToolRegistry   │◄────│ Demo tools (clock, calc)     │
│ AuditLog (hash chain)         │     │ Golden set (resources/eval)  │
│ ModelProvider  ScriptedProvider│    │ GraalVM native-image build   │
│ EvalRunner (3-state verdicts) │     └──────────────────────────────┘
└──────────────────────────────┘
```

The split is also the licensing boundary: the core engine is permissive Apache 2.0 so anyone can embed it, the application layer is EUPL 1.2 copyleft. `NOTICE` has the details; every file carries its module's header.

## Escalation is a feature

The default escalation policy is `HANDOFF_TO_HUMAN`, and an escalated outcome carries the full transcript, because an escalation without context is just a louder failure. A model that hands a task back with its reasons intact is doing exactly what its contract asks. `FAIL_CLOSED` exists for unattended pipelines where a human queue does not.

## Status

v0.1.0, the groundwork release: the enforcement core, the invariant test suite, the deterministic demo, the evaluation gate and the native build. On the roadmap, in order: an Anthropic and an OpenAI `ModelProvider`, persistent audit sinks, an MCP tool bridge so registry tools can be served to any MCP client, and latency benchmarks of the native binary against the JVM.

## License

Dual-licensed by module: `harless-core` under [Apache 2.0](LICENSE-APACHE.txt), `harless-app` under [EUPL 1.2](LICENSE-EUPL.txt). Contributions under DCO only, no CLA; see [CONTRIBUTING.md](CONTRIBUTING.md).
