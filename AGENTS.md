# Modular Launcher — Codex agent guidance

## Authority and scope
- GitHub repository AlexanderWilhelmsenBerg/Modular is the sole source authority.
- Use the GitHub connector first; GitHub MCP only for missing/failed operations. Never use Forgejo.
- Refresh main and PR #8 metadata before development. Continue the existing PR branch; avoid duplicate PRs and rebuilding implemented features.
- Do not merge or enable auto-merge without explicit user approval. Do not start Slice 9 until the user accepts Slice 8. Green CI is not acceptance.
- Preserve the microkernel: Core owns discovery, grants, routing, instances, contexts, validation/rendering and recovery. External APKs own Home functionality and state through versioned AIDL/Binder. No external DEX/Compose loading, module-specific Home in Core, or automatic authority grants.

## Code intelligence: callable connections, not authority
- Discover and use only the Repowise Modular connection (mcp__codex_apps__repowise_modular_*). Never substitute generic infrastructure, BookWave or Loopbound workspaces.
- First get_overview once to establish identity and inspect _meta.indexed_commit, live_head, index_behind and scope. This connection is single_repo and does not expose list_repos or a reindex operation. Verify title/repository identity; stop using it if identity is ambiguous.
- Independently compare indexed SHA against fresh GitHub main and the exact target PR HEAD. A short SHA is only a prefix check, not full-SHA proof. Server live_head/index_behind describes its own checkout and cannot prove GitHub freshness.
- A mismatched, absent, ambiguous or unverified SHA makes results historical-only. Verify all current claims against GitHub files pinned to target SHA. Missing symbols in stale indexes do not establish missing implementation.
- For useful historical architecture use get_answer, search_codebase, get_context, get_why or get_risk selectively. Do not use stale get_change_risk as current PR validation.
- jCodemuncher is callable through menu / route / order. Run order("list_repos") once and select exactly AlexanderWilhelmsenBerg/Modular; inspect git_head and source root. Compare full git_head with authoritative target SHA before trusting indexed source.
- Discover actions with menu; prefer order("get_ranked_context", {repo:"AlexanderWilhelmsenBerg/Modular", query:..., token_budget:2000, compress:true}) for focused navigation; inspect returned revision/provenance. Use symbol-specific retrieval only as needed.
- No helper writes authoritative repository files. Do not invent endpoints, credentials, refresh capabilities or paid-provider requirements. If freshness cannot be established, proceed using GitHub source and record the limitation.

## Sequential orchestration and cost
- At most ONE delegated specialist may be active at a time, across the whole task tree. Zero when delegation adds no value. Never parallel subagents, nested delegation, automatic fan-out or separate user-owned chats as worker substitutes.
- Coordinator alone delegates. Await completion or interrupt and confirm stopped before starting the next specialist. Reuse one specialist when possible. Coordinator must not duplicate the delegated investigation.
- Where spawn selectors are supported use model="gpt-6-luna", reasoning_effort="low", fork_turns="none". Give only a compact brief, target SHA, relevant paths, authority/index constraints and exact deliverable. Escalate reasoning only for demonstrated complexity.
- Specialist brief: one question or bounded change; initial context <=4000 tokens; retrieved context <=2000 tokens per query; <=6 focused tool calls; final <=500 words. These are instruction budgets, not hard runtime token caps. Report unresolved work when the budget is reached; coordinator decides continuation.
- No supported hard per-specialist token budget is exposed in this session. Do not misuse create_goal or model_context_window as a token-spend cap.
- Main may use gpt-6.1-sol with high or xhigh when offered; do not invent a model ID "Sol Extra". If selectors are absent, retain the current model, disclose that cost settings cannot be enforced, and do bounded work directly.
- Repository .codex/config.toml requests one open subagent thread and economical defaults on compatible trusted Codex installations. It cannot reconfigure an already-running managed session. AGENTS rules remain the fallback.

## CI efficiency and completion
- Reuse completed evidence for its exact SHA. Poll once meaningfully; if pending, do independent analysis and wait minutes before checking again. No tight polling.
- Do not manually dispatch/rerun Actions without authorization. Batch related changes into one coherent commit. A new commit may trigger automatic CI.
- Preserve scripts/ci/run-ipc-smoke.sh and existing verify / ipc-smoke structure unless a demonstrated defect warrants change.
- For Android changes inspect relevant tests and exact final-HEAD checks. Retain JUnit and diagnostics; distinguish code, test, emulator infrastructure and installation failures.
- Never label a new HEAD green using an earlier commit's run. For docs/config-only changes, validate content/config and explicitly state whether new CI has completed.
- See docs/development/codex-handoff.md for setup evidence and next action.
