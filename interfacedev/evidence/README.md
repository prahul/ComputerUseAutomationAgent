# Evidence

Real runs produced by `cua discover` / `cua replay` against the built-in target app. Every run
directory contains:

- `run.jsonl` — one structured event per line (what happened and why), redacted;
- `steps/*.png` — a screenshot per step (discovery) or at key moments (replay);
- `result.json` — the structured result (replay) ;
- `artifact.json` — the emitted capability (discovery);
- `failure.png` + `failure-dom.html` — richer signal, on hard failure only.

The example capability artifacts are also copied to
[`example-artifacts/`](example-artifacts/) for convenience.

## Discovery runs (LLM in the loop, `claude-sonnet-5`)

| Directory | Goal | Result |
|---|---|---|
| `discovery-20260828T193225-37c74ff7` | "Sign in … look up member {memberId} and read their current savings balance" | 6-step artifact `lookup_member_savings_balance` v1.0.0; typed output `savingsBalance: MONEY` |
| `discovery-20260828T193255-e128b041` | "… open a new sub-account … reach the confirmation screen" | 9-step artifact `open_member_sub_account` v1.0.0. The **Review and Create** click is classified `IRREVERSIBLE` → the run **escalates for approval** (`escalation.raised` / `policy.approved` in `run.jsonl`), then the step is recorded with `policy: REQUIRE_APPROVAL` |

## Replay runs (NO LLM — deterministic)

| Directory | Inputs | Result | Shows |
|---|---|---|---|
| `replay-20260828T193359-a12aa374` | `memberId=10001` | `SUCCESS` — `{ "savingsBalance": 4210.55 }` | happy path + typed output extraction + transform |
| `replay-20260828T193412-6520c028` | `memberId=99999` | `BUSINESS_OUTCOME (MEMBER_NOT_FOUND)` | a legitimate answer, **not** a crash — the search checkpoint never comes true, the known-outcome detector fires instead |
| `replay-20260828T193435-5151a9fa` | `memberId=10003` (RESTRICTED) | `BUSINESS_OUTCOME (PERMISSION_DENIED)`, class `DENIED` | the environment refused; caller is told cleanly |
| `replay-20260828T193458-14867060` | `memberId=10001` `--inject session_timeout` | `BUSINESS_OUTCOME (SESSION_EXPIRED)`, class `DENIED` | an **injected runtime failure** detected and reported deliberately |
| `replay-20260828T193521-a72b9673` | `memberId=10002` `--tenant altcu` | `SUCCESS` — `{ "savingsBalance": 15230.0 }` | **cross-tenant reuse**: the same artifact runs against a different branding of the vendor product; `config/tenants/altcu.json` supplies the base URL + a per-step locator override ("Customer ID" not "Member ID") |
| `replay-20260828T193533-5a516727` | sub-account, `deposit=250`, `--operator auto` | `SUCCESS` | the `REQUIRE_APPROVAL` step is auto-approved by the unattended-operator fallback (`operator: "auto-operator"` in `run.jsonl`) |
| `replay-20260828T193615-bdcb8348` | sub-account, `deposit=-5` | `BUSINESS_OUTCOME (VALIDATION_REJECTED)` | the app rejects the submitted values after the gated step is approved — a business outcome, not a failure |
| `replay-20260828T193716-6813e1f2` | sub-account, `deposit=250`, `--operator manual` | `SUCCESS` | **human-in-the-loop handoff**: run blocks at the approval gate; an operator drives the *same* live session via `/operator` (`operator.action` / `human.action` events — corrects the deposit to 300), then hands control back with `RESUME`; the automation resumes at the same step and completes |

### Reading a run

```bash
# the structured log
cat evidence/replay-20260828T193412-6520c028/run.jsonl | jq

# the result contract
jq . evidence/replay-20260828T193412-6520c028/result.json
```
