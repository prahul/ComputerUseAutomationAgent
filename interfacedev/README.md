# cua-lab — Computer-Use Automation System

An LLM **discovers** how to accomplish a goal by driving a real UI, the successful run is saved
as a typed, versioned **capability artifact**, and that artifact is then **replayed
deterministically** with no model in the loop — with an explicit result contract, runtime-error
handling, safety guardrails, evidence, and a human-in-the-loop handoff.

Design write-up: [`REPORT.md`](REPORT.md). End-to-end demonstration: [`evidence/`](evidence/).

---

## What's in the box

| Piece | Where |
|---|---|
| Goal-driven observe→decide→act agent loop (Anthropic `claude-sonnet-5`) | `src/main/java/com/example/cua/discovery/` |
| Typed, versioned artifact schema + builder | `src/main/java/com/example/cua/artifact/`, `discovery/ArtifactBuilder.java` |
| Deterministic replay engine + result contract + error taxonomy | `src/main/java/com/example/cua/replay/` |
| Surface abstraction + Playwright/Chromium web surface | `src/main/java/com/example/cua/surface/` |
| Safety: allowlist + risk policy + regulated-data redaction | `src/main/java/com/example/cua/policy/` |
| Human escalation + live-session control transfer + mock operator console | `src/main/java/com/example/cua/escalation/`, `server/OperatorConsole.java` |
| Evidence recorder (structured log + screenshots + DOM on failure) | `src/main/java/com/example/cua/evidence/` |
| Stand-in target: legacy-flavoured credit-union servicing console (2 tenant brandings) | `src/main/java/com/example/cua/server/TargetApp.java` |
| Stretch: agent-facing capability catalog API | `src/main/java/com/example/cua/server/CapabilityApi.java` |

The target application is **built in**: `cua serve` / every command starts an embedded HTTP
server. No external site is automated. No real credentials, no real PII.

---

## Setup

**Requirements:** JDK 21+ and Maven 3.9+. First build downloads a Chromium build (~180 MB) via
Playwright.

```bash
# 1. build
mvn -q -DskipTests package

# 2. one-time: install the browser Playwright drives
mvn -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"

# 3. API key for the discovery run (only `discover` needs it; `replay` never calls the LLM)
export ANTHROPIC_API_KEY=sk-ant-...
```

Config (all optional, sensible defaults built in):

| File | Purpose |
|---|---|
| `config/allowlist.json` | permitted origins/patterns, action types, `writeActionPolicy`, `maxSteps`, `allowUnattendedDraft` |
| `config/secrets.json` | logical secret store, e.g. `credentials.username` / `credentials.password` (see `config/secrets.example.json`; git-ignored) |
| `config/tenants/<id>.json` | per-tenant base URL + per-step locator overrides |

Run everything below with the shaded jar (`java -jar target/cua-lab.jar …`) or via
`mvn exec:java -Dexec.args="…"`.

---

## Demo path

```bash
JAR=target/cua-lab.jar

# ── 1. DISCOVERY ─ LLM drives the live UI, emits a capability artifact ───────────────
java -jar $JAR discover \
  --goal "Sign in to the servicing console, look up member {memberId} and read their current savings balance" \
  --name lookup_member_savings_balance \
  --param memberId=10001

#   → artifacts/lookup_member_savings_balance/1.0.0.json
#   → evidence/discovery-<ts>/   (run.jsonl, per-step screenshots, artifact.json)

# ── 2. REPLAY ─ deterministic, no LLM.  --allow-draft runs an as-yet-unreviewed artifact ──
java -jar $JAR replay --capability lookup_member_savings_balance --param memberId=10001 --allow-draft
#   → RESULT: SUCCESS   outputs: { "savingsBalance": 4210.55 }

# ── 3. REPLAY hitting a business outcome (not a crash) ─────────────────────────────
java -jar $JAR replay --capability lookup_member_savings_balance --param memberId=99999 --allow-draft
#   → RESULT: BUSINESS_OUTCOME (MEMBER_NOT_FOUND)
java -jar $JAR replay --capability lookup_member_savings_balance --param memberId=10003 --allow-draft
#   → RESULT: BUSINESS_OUTCOME (PERMISSION_DENIED)

# ── 4. REPLAY hitting an injected runtime failure ─────────────────────────────────
java -jar $JAR replay --capability lookup_member_savings_balance --param memberId=10001 \
  --allow-draft --inject session_timeout --operator auto
#   → detects SESSION_EXPIRED (class DENIED), reports it as a structured outcome

# ── 5. REVIEW → APPROVE, so agents can invoke it unattended ───────────────────────
java -jar $JAR approve --capability lookup_member_savings_balance
java -jar $JAR replay  --capability lookup_member_savings_balance --param memberId=10001   # no --allow-draft needed now

# ── 6. A flow with an irreversible step → safety gate → human approval ─────────────
java -jar $JAR discover \
  --goal "Sign in, look up member {memberId}, open a new sub-account of type Holiday Club with an initial deposit of {deposit}, and reach the confirmation screen" \
  --name open_member_sub_account --param memberId=10001 --param deposit=250 --operator auto
#   the "Review and Create" click is classified IRREVERSIBLE → REQUIRE_APPROVAL → escalation

java -jar $JAR replay --capability open_member_sub_account \
  --param memberId=10001 --param deposit=250 --allow-draft --operator auto     # auto-approves the gated step
java -jar $JAR replay --capability open_member_sub_account \
  --param memberId=10001 --param deposit=-5 --allow-draft
#   → RESULT: BUSINESS_OUTCOME (VALIDATION_REJECTED)
```

### Human-in-the-loop, driven by a person

```bash
java -jar $JAR replay --capability open_member_sub_account \
  --param memberId=10001 --param deposit=250 --allow-draft --operator manual --headed
# When the run hits the REQUIRE_APPROVAL gate it blocks and prints the console URL.
# Open http://localhost:8080/operator  → see the intervention, the live screenshot,
#   drive the same browser session (CSS selector + Click/Type), then "Hand control back"
#   with RESUME / RESUME_SKIP_STEP / ABORT.
```

### Cross-tenant reuse

```bash
java -jar $JAR replay --capability lookup_member_savings_balance \
  --param memberId=10001 --tenant altcu --allow-draft
# same artifact, different branding of the same vendor product ("Customer" not "Member",
# /altcu route prefix); config/tenants/altcu.json supplies base URL + per-step locator overrides.
```

### Agent-facing capability catalog (stretch)

```bash
java -jar $JAR catalog                                   # list capabilities as JSON-Schema tool specs
java -jar $JAR invoke --capability lookup_member_savings_balance --param memberId=10002   # APPROVED only

java -jar $JAR serve                                     # then, over HTTP:
curl localhost:8080/capabilities                         # discover
curl -XPOST localhost:8080/capabilities/lookup_member_savings_balance/invoke \
  -d '{"args":{"memberId":"10002"}}'                     # invoke by name with typed args
```

`invoke` (CLI and HTTP) runs only `APPROVED` capabilities — the agent-facing contract. Use
`cua replay --allow-draft` to exercise an artifact still under review.

### Running without live services

`replay`, `catalog`, `invoke`, and `serve` never call the LLM and need no API key — they run
fully offline against the embedded target app. Only `discover` needs `ANTHROPIC_API_KEY`.
Pre-recorded artifacts + evidence are checked in under `artifacts/` and `evidence/`.

---

## Command reference

| Command | Purpose |
|---|---|
| `cua serve [--port]` | start target app + operator console + capability API, block |
| `cua discover --goal --name [--param k=v] [--sensitive k] [--entry] [--model] [--max-steps] [--headed] [--operator manual\|auto\|auto-abort]` | LLM discovery run → artifact |
| `cua replay (--capability \| --artifact) [--param k=v] [--tenant] [--inject none\|slow\|interstitial\|session_timeout\|error] [--allow-draft] [--operator] [--headed]` | deterministic replay |
| `cua approve --capability <name>` | promote a reviewed capability DRAFT → APPROVED |
| `cua catalog` | list saved capabilities as JSON-Schema tool specs |
| `cua invoke --capability [--param k=v] [--tenant]` | invoke an APPROVED capability (agent-facing; runs a replay) |

`--operator`: `manual` = block for a real operator at `/operator`; `auto` = auto-resume after a
short timeout (default); `auto-abort` = auto-abort on escalation.

## Tests

```bash
mvn test
```

Covers the schema round-trip, the detector/condition evaluator, locator-chain ranking, the
policy engine (allowlist + risk gating), and redaction.
