<!-- List the area(s) touched so reviewers know where to look.
Examples: hello, fileprocessing, spring, shadowing, calculation, bookingsaga, replaytests, common, README, build -->
**Which sample(s) or area?**


<!-- 1-2 line summary of WHAT changed technically.
- Link to a GitHub issue when applicable (encouraged for larger changes; optional for trivial doc/sample tweaks)
- Good: "Added HelloActivityRetry sample demonstrating retry options" or "Updated README run instructions for Java 21"
- Bad: "updated code" or "fixed stuff" -->
**What changed?**


<!-- Provide context and motivation (see https://cbea.ms/git-commit/#why-not-how). Focus on WHY, not how.
- Lighter than core Cadence repo: e.g. "improving clarity for new users," "fixing sample broken on Java 21," "aligning with cadence-docs"
- Still give enough rationale for reviewers to understand the goal
- Good: "HelloActivity didn't show retry behavior; this sample demonstrates RetryOptions so users can copy-paste a working example."
- Bad: "Improves samples" -->
**Why?**


<!-- Include concrete, copy-paste commands so another maintainer can reproduce your test steps.
- Prefer: `./gradlew build`, `./gradlew test` (or a targeted test if relevant)
- For runnable samples: `./gradlew -q execute -PmainClass=com.uber.cadence.samples.<area>.<Class>`
- Good: Full commands reviewers can copy-paste to verify
- Bad: "Tested locally" or "See tests" -->
**How did you test it?**


<!-- Often N/A for sample-only or doc-only changes. Call out when relevant:
- Dependency upgrades (e.g. cadence-client version)
- Behavior changes that could affect someone copying the sample
- Build or config changes
- If truly N/A, you can mark it as such -->
**Potential risks**


<!-- Optional for this repo. Use when the change is user-facing (e.g. new sample, notable README change).
- Can be N/A for internal refactors, tiny fixes, or incremental work -->
**Release notes**


<!-- Did you update the main README or a sample README (e.g. src/main/java/.../shadowing/README.md)?
- Any links to cadence or cadence-docs that need updating?
- Only mark N/A if you're certain no docs are affected -->
**Documentation Changes**

