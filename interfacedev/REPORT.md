# Computer-Use Automation System — Design Write-up

A record-once / replay-many capability engine. An LLM discovers how to accomplish a goal by
driving a real UI; the successful run is distilled into a typed, versioned **capability artifact**;
that artifact is then replayed deterministically with no model in the loop, with an explicit
result contract, runtime-error handling, safety guardrails, and a human-in-the-loop handoff.

Implemented in Java 21. One concrete surface (a web app via Playwright/Chromium) against a
purpose-built stand-in for a legacy credit-union servicing console.

---

## 1. Architecture

```
                 ┌─────────────┐  goal + params
                 │     CLI     │────────────────┐
                 └─────────────┘                ▼
   discovery ─────────────────────────►  ┌──────────────┐        ┌───────────────┐
                                         │ DiscoveryAgent│◄──────►│  LlmClient    │ (Anthropic)
   observe → decide → act loop           └──────┬───────┘        └───────────────┘
                                                │ StepTrace[]
                                         ┌──────▼───────┐
                                         │ ArtifactBuilder│  synthesize checkpoints,
                                         └──────┬───────┘   lift params/outputs, attach
                                                │           domain outcomes + recoveries
                                         ┌──────▼───────┐
                                         │ ArtifactStore │  artifacts/<name>/<version>.json
                                         └──────┬───────┘
   replay ──────────────────────────────►┌──────▼───────┐        ┌───────────────┐
                                         │ ReplayEngine  │◄──────►│ DetectorEngine │
   deterministic, no LLM                 └──────┬───────┘        └───────────────┘
                                                │
      ┌───────────────┬─────────────────────────┼───────────────────────┐
      ▼               ▼                         ▼                       ▼
┌───────────┐  ┌─────────────┐          ┌──────────────┐        ┌───────────────┐
│PolicyEngine│  │  Redactor   │          │EvidenceRecord│        │ EscalationBroker│
│ allowlist  │  │ regulated   │          │ run.jsonl +  │        │ + SessionCtrl  │
│ + risk     │  │ data mask   │          │ screenshots  │        │ + Operator UI  │
└───────────┘  └─────────────┘          └──────────────┘        └───────────────┘
                        every action, discovery AND replay, passes PolicyEngine + Redactor
```

**The load-bearing seam is `Surface`** (`surface/Surface.java`). Everything above it — the agent
loop, the artifact schema, the replay engine, detectors, policy — is surface-agnostic. It exposes:

- `observe() → Observation`: a normalized element list (`role`, accessible `name`, `value`,
  geometry, per-element locator candidates) plus a screenshot and a flattened text digest.
- `act(Action)`: a small semantic vocabulary (`NAVIGATE / CLICK / TYPE / SELECT_OPTION /
  PRESS_KEY / EXTRACT / …`), not surface-specific calls.
- `probe(LocatorSpec) → {found, strategyUsed}` and `resolve(LocatorSpec)`: durable target
  resolution used by replay and by detectors.

`WebSurface` is the only implementation. It perceives via an accessibility-flavoured DOM walk
across **every frame** (role + accessible name + bounding box), *not* by assuming a clean DOM,
and it resolves targets through an ordered strategy chain ending in a coordinate click.

**Key decisions & trade-offs**

| Decision | Why | Trade-off |
|---|---|---|
| Single process, synchronous core | The assignment explicitly does not reward queue/cluster plumbing; a vertical slice is clearer to reason about | No horizontal scale; addressed as design only (§4) |
| Playwright/Chromium as the one surface | Mature waiting primitives, cross-frame a11y snapshot, screenshots, coordinate fallback — lets the same code express "clean DOM" and "coordinates only" | Ties the *implementation* (not the design) to a browser |
| Anthropic `claude-sonnet-5`, custom tool loop | Strong vision + tool use at low per-step cost/latency for a many-screenshot loop; a hand-written loop keeps the agent contract explicit and the artifact decoupled from the transcript | Not using a turnkey CUA SDK; we own the loop |
| Perceive by a11y tree + screenshot, act by role/name/text | This is the representation that survives to a legacy frameset and to a desktop app; test IDs essentially never exist in these apps | A11y-name computation is heuristic; mitigated by the fallback chain |
| Artifact = typed contract, not a step list | A calling agent and a human reviewer must understand inputs/outputs/outcomes without reading steps | More schema to design and version |
| Curated per-vendor **DomainLibrary** of known outcomes/recoveries | A happy-path discovery run never sees "record not found" or a session timeout; those must be declared, not inferred | Requires per-vendor curation (realistic — it lives with each integration) |

---

## 2. Artifact schema

`artifact/Artifact.java` — serialized as JSON, `schemaVersion` + semver `meta.version`.
Full examples: [`evidence/example-artifacts/`](evidence/example-artifacts/).

```
Artifact
├─ meta            id, name, version, title, description, sourceRunId,
│                  approval (DRAFT|APPROVED|DEPRECATED), replayConfidence
├─ surface         kind (web|legacy-web|desktop), engine, viewport
├─ target          appId, vendorProduct, entryUrl ("${tenant.baseUrl}/…"), allowlistId
├─ parameters[]    name, type, required, sensitive, description, example, pattern
├─ outputs[]       name, type, description, source{ stepId, transform, regex }
├─ steps[]         id, action, intent, target(LocatorSpec), value(ValueSpec),
│                  url, key, waitMs, checkpoint(Condition+timeout), risk, policy
├─ success         Condition
├─ knownOutcomes[] code, class (BUSINESS|DENIED), detect(Condition), terminal, message
├─ recoveries[]    code, detect(Condition), strategy (WAIT_RETRY|DISMISS|RELOAD),
│                  maxAttempts, backoffMs, dismissAction(Step)
└─ redaction       secretsNeverLogged, sensitiveParams, patterns[]
```

**Why it is shaped this way**

- **`LocatorSpec` = ordered strategy chain + rationale, per target.** `{ primary, fallbacks[],
  rationale }`. Strategies, most-durable first: `ROLE_NAME → LABEL → PLACEHOLDER → TEXT →
  TEST_ID → ANCHORED → CSS → XPATH → BBOX`. Replay tries them in order and records which one
  actually resolved. The human-readable `rationale` string is generated at record time
  (`LocatorBuilder`) and explains the ordering — this is the reviewability requirement. The
  `BBOX` (viewport-normalized centre) tail is what keeps replay alive on a surface with no
  usable DOM at all.
- **`ValueSpec` is a tagged union** — `literal | paramRef | secretRef`. A password typed during
  discovery is stored as `{secretRef: "credentials.password"}`; the artifact never contains the
  value. `paramRef` bindings are detected by matching typed text against the run's parameter
  values.
- **`Condition` is a small composable predicate language** (`TEXT_PRESENT/ABSENT`,
  `URL_MATCHES`, `ELEMENT_VISIBLE/ENABLED`, `VALUE_EQUALS`, `ALL_OF/ANY_OF`). One type powers
  checkpoints, the success condition, known-outcome detectors, and recovery triggers — so the
  replay engine has exactly one evaluator to get right (`DetectorEngine`).
- **`knownOutcomes` vs a failure is a first-class distinction in the schema**, because
  conflating "no such member" with a crash is the classic mistake. Each has a `class`
  (`BUSINESS` = a valid answer, `DENIED` = the environment refused) and a stable `code` the
  caller switches on.
- **Checkpoints are synthesized, not guessed loosely.** `ArtifactBuilder`: a navigation →
  `URL_MATCHES(path)`; a click that changes the path → `URL_MATCHES(new path)`; a click that
  reveals data on the same page → `ELEMENT_VISIBLE(next step's target)` (stable across
  parameter values); a `TYPE` → `VALUE_EQUALS` when the value is a literal.
- **Multi-tenant is a property of the schema, not an add-on** — the artifact is keyed by
  `vendorProduct + name + version`, `entryUrl` is templated, and a `TenantProfile` supplies the
  base URL plus per-`stepId` `LocatorSpec` overrides (§4).
- **`approval` gates unattended replay.** Discovered artifacts are `DRAFT`; policy can refuse to
  run a `DRAFT` capability without a human (`allowUnattendedDraft`).

---

## 3. Determinism & error handling

**Determinism** (`ReplayEngine`, no LLM anywhere in the path):

1. **Ordered locator resolution.** `probe()` walks the `LocatorSpec` chain and returns the first
   strategy that resolves to a *visible* element in *any* frame. Same artifact + same inputs →
   same resolution.
2. **Explicit waits + a checkpoint after every step.** `DetectorEngine.waitFor(condition,
   timeout)` polls; nothing "assumes the click worked".
3. **Typed value binding.** `paramRef`/`secretRef`/`literal` resolved from the `BindingContext`;
   a missing required parameter fails fast before any action.
4. **Outputs are re-read from the live DOM** at the end via each output's `source.stepId`
   locator, then `transform`ed (`money` → number, `digits` → long) and optionally regex-captured.

**Error & exceptional-state handling — the result contract** (`ReplayResult`):

| Status | Meaning | Fields |
|---|---|---|
| `SUCCESS` | Flow completed, success `Condition` verified | `outputs{}` |
| `BUSINESS_OUTCOME` | A legitimate answer the caller needs — not a crash | `outcomeCode`, `outcomeClass`, `outcomeMessage` |
| `FAILED` | Hard failure | `failure{ stepId, phase, expected, observed, evidenceRef }` |

Before every step, after every failed locate, and after every failed checkpoint, the engine
evaluates `knownOutcomes`. So a `Search` whose result checkpoint (`ELEMENT_VISIBLE(savings
cell)`) never comes true, but where `"No member found"` is on screen, returns
`BUSINESS_OUTCOME / MEMBER_NOT_FOUND` — not a failure. `PERMISSION_DENIED`, `VALIDATION_REJECTED`
and `SESSION_EXPIRED` work the same way.

**Recoverable conditions** are handled autonomously and bounded, before escalating:
`TRANSIENT_LOAD` → `WAIT_RETRY` (≤3, backoff); `KNOWN_INTERSTITIAL` (a "Continue"/"OK" dialog
some tenants show) → `DISMISS` via a declared `dismissAction`. A single action failure also
triggers one recovery sweep + retry before it becomes a hard failure.

**Hard failures** capture `{ which step, what was expected, what was observed }` plus a
screenshot and a full DOM snapshot under `evidence/<runId>/`, and (by default) raise an
escalation.

**Drift** (secondary): every step records the strategy that actually resolved it; if it was not
the `primary`, a `DriftSignal { stepId, expectedStrategy, actualStrategy }` is emitted. A run
full of drift signals is the flag that an artifact needs re-review for a given tenant/version.

---

## 4. Heterogeneity & multi-tenant

**Surface abstraction → legacy web & desktop.** The seam is `Surface`: `Observation` (a list of
`{role, name, value, bounds, locatorCandidates}`) and the semantic `Action` vocabulary. The
recorded flow references *roles, accessible names and visible text* — never CSS or Chromium
APIs — so it is already surface-neutral.

- *Legacy web app* (framesets, table layout, no test IDs): the current `WebSurface` already
  walks every frame and already leans on role/name/text/coordinates rather than selectors. The
  stand-in app in this repo is deliberately built this way (an `<iframe>` member panel,
  table-based forms, no test IDs) and the discovered artifact binds cleanly to it.
- *Desktop app*: a new `Surface` implementation over the platform accessibility API
  (UI Automation / AX) emits the *same* `Observation`/`Action` types. `LocatorStrategy` kinds
  map over: `ROLE_NAME` → control type + Name property; `BBOX` → screen coordinates. The
  artifact schema does not change; only `surface.kind` and the adapter do.

**Multi-tenant reuse.** Artifacts are keyed by `vendorProduct + name + version`, not by tenant.
A `TenantProfile` (`tenant/TenantProfile.java`, `config/tenants/*.json`) binds one to a
specific institution:

```json
{ "tenantId": "altcu", "vendorProduct": "acme-servicing",
  "baseUrl": "http://localhost:8080/altcu",
  "stepLocatorOverrides": { "s4": { "primary": {"kind":"ROLE_NAME","role":"textbox","name":"Customer ID"} } } }
```

`replay --tenant altcu` substitutes `${tenant.baseUrl}` and, per step, uses the override
`LocatorSpec` if present else the artifact's own. This repo demonstrates it: the same
`acme-servicing` product is served under two brandings (`Riverbend CU` / `Summit Financial CU`,
"Member" vs "Customer", different accent, different route prefix) and one artifact recorded on
`base` replays against `altcu` with a handful of per-step overrides.

**Drift management across tenants/versions:** the per-step `strategyUsed` telemetry (§3) is the
detector. A canonicalization pass (concrete route → `/member/:id`) and a confidence score from
multi-run replays are designed but not built (§7).

---

## 5. Escalation & handoff

`escalation/Escalation.java` + `server/OperatorConsole.java`.

**Detect "stuck".** Discovery: the agent calls the `escalate` tool, or the loop hits
`max-steps`, or a policy gate returns `REQUIRE_APPROVAL`. Replay: a hard failure (with
`escalateOnHardFailure`), or a `REQUIRE_APPROVAL` step.

**Route.** An `InterventionRequest` is filed with the context an operator needs — capability,
trigger, current step, location, screenshot path, reason, and (optionally) a question — and
appended to the `EscalationBroker`. The automation thread then **blocks**.

**Take control of the *same* live session.** The control model is a single `SessionController`
with one `controlOwner` (`AUTOMATION | OPERATOR`) and a `SynchronousQueue` rendezvous. On
escalation, ownership flips to `OPERATOR` and the automation thread parks on the queue. The
`OperatorConsole` (served from the same JVM at `/operator`) shows the open requests, a live
screenshot of the very browser session the automation was driving, and controls to click / type
/ press keys **into that session** — plus, because the browser runs headed during an
escalation, the operator can act in the real window directly. Every manual action is recorded to
evidence as a `human.action` / `operator.action` event.

**Hand back.** The operator picks a `Resolution` (`RESUME | RESUME_SKIP_STEP | ABORT`) and a
note; `resume()` puts the `HandoffResult` on the queue, ownership flips back to `AUTOMATION`,
and the blocked thread wakes exactly where it stopped and continues (discovery: re-observe and
carry on; replay: continue, or skip the current step). An unattended fallback
(`autoRespondAfter`) can auto-resume after a timeout so headless runs don't hang forever — used
for the demo runs, and clearly labelled `operator: "auto-operator"` in the evidence.

**What is mocked, deliberately:** a production co-browsing console (real-time video, cursor
sharing, richer operator queue with assignment/SLA). The *handoff mechanism and control-transfer
model are real* — pause, cede on the same session, capture human actions, signal resume — which
is what the brief asks for.

---

## 6. Safety

`policy/PolicyEngine.java` + `policy/Redactor.java`. **Every action, in discovery and in replay,
goes through `PolicyEngine.check()` before it runs.**

**Allowlist** (`config/allowlist.json`): permitted `allowedOrigins`, `allowedUrlPatterns`
(regex), `allowedActionTypes`, a `writeActionPolicy` (`ALLOW | REQUIRE_APPROVAL | BLOCK`),
`maxSteps`, `maxWallClockMs`, `allowUnattendedDraft`. A `NAVIGATE` outside the origin/pattern
allowlist is `BLOCK`ed; an action type not on the list is `BLOCK`ed.

**Risk classification.** `SAFE` (navigate, read, type into a field) proceeds. `SENSITIVE`
(a bare click that might submit) proceeds but is marked. `IRREVERSIBLE` (label matches
`submit|confirm|create|delete|transfer|pay|…`) is gated by `writeActionPolicy` — default
`REQUIRE_APPROVAL`, which raises an escalation and waits for a human. The discovery run for
"open a sub-account" hits this on the **Review and Create** button; the evidence shows the
escalation, the approval, and the resulting `policy: REQUIRE_APPROVAL` on that step in the
artifact.

**Regulated data.** `Redactor` runs on every evidence write and on artifact serialization:
(1) known secret / sensitive-parameter *values* registered for the run are replaced with a
stable token; (2) a configurable pattern sweep masks SSNs, PANs, bearer tokens and API keys
even when the value wasn't known up front. Secrets reach the model only as the sentinel
`__SECRET__:credentials.password`, which the harness substitutes and never logs.

**Limits (honest).** The risk classifier is lexical — a mislabeled destructive button
("Proceed") would be under-classified; a real deployment needs per-capability review of each
step's `risk`/`policy` (the schema supports it, `approval: APPROVED` is the gate). Redaction
patterns are best-effort. The allowlist is per-capability-family, not per-caller; no
per-invocation quota. Screenshots on sensitive screens are stored (redaction covers text, not
pixels) — a real system would blur known PII regions or drop the screenshot.

---

## 7. Cuts

**Deliberately not built (clean seam left):**

- **Real operator co-browsing console** — mocked at `/operator`; the control-transfer model is
  real (§5).
- **Desktop / legacy-frameset `Surface` implementations** — designed (§4), one `WebSurface`
  built. The stand-in app is legacy-flavoured (iframe, tables, no test IDs) to exercise the
  locator strategy honestly.
- **Multi-tenant at scale** — `TenantProfile` overrides are built and demoed on two brandings;
  route canonicalization (`/item/123` → `/item/:id`) and a tenant/version drift dashboard are
  not.
- **Persistence / services / queues** — filesystem `ArtifactStore`, single process, by design.
- **Assisted fallback on replay** (bounded, policy-checked single-step LLM recovery) — the
  hook (`escalateOnHardFailure`) is where it would attach.
- **Confidence scoring & multi-run stability** — `meta.replayConfidence` field exists; the
  N-run harness that populates it does not.

**What I'd build next, in order:** (1) multi-run stability → `replayConfidence` → gate
unattended replay on it; (2) route/value canonicalization for cross-tenant reuse; (3) a second
`Surface` (legacy frameset first, it reuses most of `WebSurface`) to prove the seam; (4)
bounded assisted fallback on replay failure, recorded as evidence.

**Stretch goals that *are* in:** the agent-facing **capability catalog** (`/capabilities` —
`GET` lists artifacts as JSON-Schema tool specs, `POST /{name}/invoke` runs a deterministic
replay with typed args; also `cua catalog` / `cua invoke`), and the **cross-tenant override**
demo.
