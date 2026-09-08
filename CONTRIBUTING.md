# Contributing to Harless

Contributions are welcome under two rules, both non-negotiable.

**Licensing.** There is no Contributor License Agreement and there never will
be one. Contributions are accepted under the Developer Certificate of Origin
(https://developercertificate.org): sign your commits with `git commit -s`.
Code contributed to `harless-core` is licensed under Apache 2.0; code
contributed to `harless-app` is licensed under EUPL 1.2. By submitting a
change you agree your contribution is bound to the license of the module it
lands in, permanently. This keeps the project impossible to quietly
re-license, by anyone, including the author.

**Invariants.** The tests in `HarnessInvariantsTest` are the project's
constitution. A pull request that weakens an invariant (an agent running
without limits, a refusal that stops being audited, an unverifiable verdict
gating as a pass) will be closed regardless of what it improves elsewhere.
Everything else is negotiable.
