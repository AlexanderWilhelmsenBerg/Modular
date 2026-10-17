# Codex takeover — updated 17 October 2026

## Refreshed source and evidence
Repository: https://github.com/AlexanderWilhelmsenBerg/Modular
PR #8 remains open, non-draft, unmerged, branch phase-1/slice-8-surface-home.
At takeover main/base is 48579b5b82579abde87d0bacdd7b2959b74adb55.
The previously validated PR HEAD is 7e24c1d8cdf3b172a1d03dbddf8070743aa9b041.
Android CI https://github.com/AlexanderWilhelmsenBerg/Modular/actions/runs/38759088657 is completed/success:
verify (production compilation, unit tests, lint, APK assembly) and ipc-smoke (syntax, cross-APK instrumentation) both succeeded; diagnostic uploads succeeded.
Slice 8's cancellation defect was fixed at 50ed456de68d9a5fa1f965ab59a734586c5379be. Exact-HEAD CI 38913780111 passed verification and device tests. PR #8 remains unmerged. The user subsequently instructed fixing Slice 8 and continuing through all Core product requirements, explicitly including MCP brokering. Follow-on implementation is in draft PR #9, based on #8; no merge approval was supplied. Technical validation does not constitute user acceptance.

## Verified tools
Both connections were actually called from this Codex chat:
- Repowise Modular get_overview returned Repository Overview: Modular, single_repo, indexed_commit=48579b5b8257, live_head=48579b5b8257 and index_behind=false. It has no exposed list_repos/reindex tool. Short prefix corresponds to main, but cannot certify full index SHA or PR content. Historical-only for Slice 8. Its mock embedder/absent semantic search does not require an API key.
- jCodemuncher menu and order("list_repos") returned AlexanderWilhelmsenBerg/Modular, git_head=48579b5b82579abde87d0bacdd7b2959b74adb55, root=/srv/jcodemunch/repos/Modular. Historical-only for PR #8. Other returned repositories must not be substituted.

Recheck revisions when the target changes. Tool presence in this chat is verified; inheritance into a fresh specialist or another installation still requires a bounded read-only smoke call. If unavailable, reconnect these exact plugins in Codex settings; do not fabricate server URLs or copy credentials into Git. No duplicate MCP server entries were added because no verified deployment URLs/auth setup were supplied, and connections already work here.

To refresh indexes, the index service owner must update the Modular server checkout to the intended GitHub revision and run its supported indexing workflow, then expose/verify the resulting full SHA. Repowise advertises a server-side "repowise reindex" command, but this chat has no executor for that service. A watcher claiming fresh relative to its main checkout is insufficient for PR HEAD.

## Runtime configuration and limits
Installed binary observed: codex-cli 0.163.0-alpha.10.2.
CLI --model, --config, --strict-config and debug models --bundled are supported.
Bundled catalog and this chat's spawn schema offer:
- gpt-6.1-sol, gpt-6-sol, gpt-6-astra, gpt-5.6-sol: low, medium, high, xhigh, max, ultra.
- gpt-6-luna, gpt-5.6-luna: low, medium, high, xhigh, max.
The bundled catalog also lists gpt-5.6-terra with low/medium/high/xhigh/max/ultra, but this chat's spawn schema does not offer it. Catalog presence is not proof of account availability.
Use gpt-6-luna/low for bounded specialists. Main preference is gpt-6.1-sol/high; xhigh is the extra-high reasoning option, not a distinct "Sol Extra" model ID. Main model is not changed by repository guidance. No live-main model switch tool is exposed.
Avoid ultra's automatic delegation under the sequential policy.

.codex/config.toml sets agents.max_concurrent_threads_per_session=1 (excluding primary) plus default_subagent_model and default_subagent_reasoning_effort. The legacy alias agents.max_threads is intentionally omitted.
These are documented configuration keys, not an override of this managed chat's four-slot runtime. Here sequential use is enforced by agent instructions, not a changed runtime hard cap.
The spawn interface permits explicit model/effort only for fork_turns="none"; inherited full-history forks cannot override them.
Soft brief/retrieval/output/tool budgets are in AGENTS.md. No hard per-subagent spend limit was exposed. Do not claim a smaller model_context_window imposes one.

Official reference: https://learn.chatgpt.com/docs/config-file/config-reference
Project configuration is loaded by compatible Codex clients for trusted projects; settings may need a new session. For a CLI launch with explicit overrides:
codex -C <trusted-checkout> --model gpt-6.1-sol -c model_reasoning_effort=high -c agents.max_concurrent_threads_per_session=1 -c agents.default_subagent_model=gpt-6-luna -c agents.default_subagent_reasoning_effort=low
Keep existing sandbox/approval settings.

## Verified checkout and next action
The actual checkout is the Modular subdirectory of the original ChatGPT project mirror. Its full path is C:/Users/co_abe/.codex/.chatgpt-projects/g-p-6ac945793164819182ef3e4afcaf3322/Modular. The mirror's sources and synced AGENTS.md remain untouched.

The user explicitly approved trusting this exact checkout after automatic approval review required authorization for the security-setting change. A strict-config app-server config/read probe with this cwd and no overrides verified that its project layer is enabled, agents.enabled=true, max_concurrent_threads_per_session=1, and default_subagent_model/reasoning_effort=gpt-6-luna/low. The user-level model default remained gpt-6-astra/low. A new compatible session loads these defaults; this running managed session uses sequential instructions. A bounded Luna specialist also successfully called both exact code-intelligence connections. No credentials or duplicate server definitions were added.

Continue PR #9 on phase-1/slice-9-drawer and reuse its exact-HEAD CI evidence. See [Core completion](../implementation/core-completion.md) for implementation scope, endpoint setup and validation limitations. Keep both PRs unmerged pending user acceptance/review and explicit merge approval; #9 contains unmerged #8 ancestry. Do not claim indexed helpers contain these changes until their full SHA is independently verified.
