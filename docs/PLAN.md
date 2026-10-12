# PLAN.md — build phases

Work phases in order. Each phase lists tasks, files, and a Definition of Done.
DoD is checked against CI (`lintDebug` + `testDebugUnitTest` green) plus the boxes below.
Protocol details live in `docs/PROTOCOL.md` — cited as §N below.

## Phase 0 — Specs & server prerequisites (architect + owner)

- [x] AGENTS.md, SPEC.md, PROTOCOL.md, PLAN.md, SERVER.md written and verified against
      Hermes v0.21.6 source.
- [ ] Owner: SERVER.md §1 (stable signing secret) and §3 (exposure check) before Phase 1
      on-device testing; §2 before Phase 4; §4 before Phase 7.
- [ ] Owner: create the 4 CI signing secrets (AGENTS.md "CI signing") before Phase 1 merges.

## Phase 1 — Skeleton, CI, login

- Tasks: Gradle wrapper, version catalog, `app/` module (min/target/compile 37),
  network security config (cleartext only `100.123.89.114`, `*.ts.net`),
  `.github/workflows/android.yml` (assemble + lint + unit tests; debug signing via secrets),
  Login screen (URL default `http://100.123.89.114:9119`, username, password),
  `TokenManager` (PKCE flow §2, proactive refresh, Keystore-backed DataStore),
  `GatewayClient` skeleton (OkHttp, connect with `?ticket=`, ready + capabilities handshake §3),
  Diagnostics screen logging each handshake step (redacted per SPEC R1.5) with
  "Export capture" (redacted frames as `.jsonl` via the share sheet).
- Files: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`,
  `AndroidManifest.xml`, `network_security_config.xml`,
  `ui/LoginScreen.kt`, `ui/DiagnosticsScreen.kt`,
  `gateway/TokenManager.kt`, `gateway/GatewayClient.kt`, `gateway/JsonRpc.kt`.
- Tests: PKCE challenge vector; `next`-URL code/state parsing (+ state mismatch rejected);
  token JSON parsing; refresh decision (expiry-60 s, 401 → login, 503 → keep);
  frame classifier (request/response/event/server-request) on recorded strings; redaction.
- DoD:
  - [x] CI green on a feature branch.
  - [x] Owner on-device 2026-10-12: login succeeds, `Connection: READY`,
        `contract=14 server-request types, heartbeat=true`, Diagnostics green.
  - [ ] App restart stays signed in (verify on next install).

## Phase 2 — Sessions

- Tasks: session list/create/rename/delete (close live session before delete — 4023);
  open = resume with `inline_images:false` (4130 → REST paged fallback, PROTOCOL §1);
  check `info.desktop_contract` (≠8 → banner, PROTOCOL §3);
  `@image:` thumbnails via `/api/media`;
  render history rows (§7) incl. `@image:`/`@file:` refs, reasoning sidecars, tool rows;
  `sessions.changed` → refetch; persist stored id per open session; Sessions screen.
- Files: `gateway/SessionsApi.kt`, `data/SessionStore.kt` (Room),
  `ui/SessionsScreen.kt`, `ui/HistoryList.kt`.
- Tests: resume-JSON parsing incl. `inflight`/`open_requests`; history projection cases
  (tool-only row dropped, `@image:` line parsed).
- DoD:
  - [ ] List, create, rename, delete round-trip against live server.
  - [ ] A session with past tool calls renders transcript + tool rows correctly.

## Phase 3 — Live chat

- Tasks: `TurnReducer` (SPEC R3.2 state table); composer; streaming reply;
  Stop (`session.interrupt`); send-while-busy labelled from `prompt.submit` status;
  reconnect procedure exactly as PROTOCOL §5 (activate → events.since → held frames,
  resume fallback); `gateway.ping` 15 s/45 s watchdog; reconnect backoff 1 s→30 s;
  fail pending calls on drop; Chat screen with activity header and elapsed timer.
- Files: `turn/TurnState.kt`, `turn/TurnReducer.kt`, `turn/GatewayEvent.kt`,
  `gateway/Replay.kt`, `ui/ChatScreen.kt`, `ui/ActivityHeader.kt`,
  `ui/ThinkingPanel.kt`, `ui/ToolTimeline.kt`.
- Tests: reducer unit tests replaying a recorded turn (start→thinking→tool→delta→complete);
  replay filter (seq ≤ watermark dropped, epoch change clears, held frames ordered);
  tool held across interleaved reasoning; `message.complete` status → Error/Stopped.
- DoD:
  - [ ] "hello" turn walks Working → Responding → Idle with streamed text.
  - [ ] Tool-using turn fills timeline with durations; thinking panel fills; usage shows.
  - [ ] Stop halts the turn; airplane-mode mid-turn reconnects without duplicates.

## Phase 4 — Attachments

- Tasks: gallery pick (multi), CameraX capture sheet, generic file picker, share-receive;
  downscale (≤1568 px, JPEG q80), HEIC→JPEG; `image.attach_bytes`, `pdf.attach`,
  `file.attach` (+ append `ref_text` to message); inline rendering in history;
  refuse files > 11 MB raw (PROTOCOL §6 ceiling); attach-then-submit ordering and
  abort-on-attach-failure per PROTOCOL §6.
- Files: `media/Attachments.kt`, `media/CameraSheet.kt`, `ui/AttachBar.kt`.
- Tests: downscale math; always-`.jpg` filename; 11 MB boundary; `ref_text` appending;
  `@image:`/`@file:` parsing incl. each quote style.
- DoD:
  - [ ] Camera photo, gallery image, a PDF and a .zip each reach the agent and are
        acknowledged in the turn (image queued / pages listed / `@file:` ref read back).

## Phase 5 — Approvals & questions

- Tasks: approval cards (offered choices only, expiry on `request.cancel`/timeout);
  clarify cards (options + free text, single submit); masked sudo/secret/vault inputs;
  `-32601` + log for the rest; `open_requests` rehydrated as cards on activate/resume;
  `pending_approval`-only cards answered via `approval.respond` (PROTOCOL §8);
  `approval.cancelled` withdraws listed cards.
- Files: `ui/ApprovalCard.kt`, `ui/ClarifyCard.kt`, `ui/SecretField.kt`,
  `gateway/ServerRequests.kt`.
- Tests: choice-list rendering (session/always omitted cases); clarify answer JSON;
  cancel-by-id withdraws only the matching card.
- DoD:
  - [ ] Live approval answered `once` continues the turn; expired card states expiry.
  - [ ] Clarify with options + free text submits one answer set.

## Phase 6 — Background & reconnect

- Tasks: foreground service per SPEC R6.1 (start on background-with-running-turn,
  stop on `message.complete`/60 s idle); ongoing notification with activity state;
  "reply ready" notification (R6.4); after app kill: open → `session.resume(stored)` →
  snapshot (inflight + open_requests) with gap notice if a turn was running;
  runtime-id death (4001/`session.reclaimed`) → resume path.
- Files: `work/TurnService.kt`, `gateway/Recovery.kt`.
- Tests: recovery decision table (epoch change / truncated / 4001 / open_requests).
- DoD:
  - [ ] Leave app mid-turn → notification shows progress; return → live state.
  - [ ] Kill app mid-turn → reopen → complete transcript or explicit gap notice.

## Phase 7 — Push via ntfy (needs SERVER.md items 4–5 done first)

- Tasks: ntfy subscription config (private server, topic, credential); notification →
  `hermes://session/<stored-id>` deep link opening the session; battery-friendly
  (prefer the official ntfy app as subscriber if its deep-link support suffices —
  verify before building an in-app subscriber).
- DoD:
  - [ ] Cron/agent message arrives with app closed; tap opens the right session.

## Phase 8 — Polish

- Tasks: code blocks + tables in reply rendering; haptics on state changes;
  predictive back; empty/loading/error states per screen; app icon; release build check.
- DoD:
  - [ ] SPEC.md §4 acceptance boxes all hold on the Pixel.
