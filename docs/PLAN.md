# PLAN.md — build phases

Work phases in order. Each phase lists tasks, files, and a Definition of Done.
DoD is checked against CI (`lintDebug` + `testDebugUnitTest` green) plus the boxes below.
Protocol details live in `docs/PROTOCOL.md` — cited as §N below.

## Session status (2026-10-12 — session closing, work paused here)

- **Phase 1: done, merged to `main`.** Verified on-device (login → READY, 14 server-request
  types, heartbeat). APKs named `hermes-debug-build-<run number>`.
- **Phase 2: done, merged to `main`.** Verified on-device (auto-login, session open,
  rotate mid-session, readable UI after the Surface/insets fix).
- **Phase 3: code complete on branch `phase-3-live-chat`, CI green, NOT merged,
  NOT tested on-device.** Live chat: TurnReducer + unit tests, prompt.submit with
  queued/steering/redirected labels, Stop, §5 reconnect (activate → events.since →
  held frames, resume fallback, 1 s→30 s backoff), Chat screen (activity header +
  elapsed timer, thinking panel, tool timeline). HistoryScreen removed (ChatScreen
  absorbs it); one extra shared file (`ui/MessageRows.kt`) to avoid duplicating
  the transcript renderer.
- **Design direction: decided, parked for Phase 8.** Canon world (Claude/Gemini/ChatGPT bar),
  one fixed Hermes blue, light leads. Contract: `.impeccable/surfaces/*chatscreen*.md`;
  comps: `.impeccable/mocks/decision/`; product truth: `PRODUCT.md`.
- **Credentials:** server URL + username in `config/Defaults.kt`; password only in the
  `HERMES_PASSWORD` CI secret and baked into APKs — never in source. Rotate it per
  SERVER.md §5 when convenient (it appears in an old chat transcript).
- **Next:** owner installs latest `phase-3-live-chat` build, checks hello turn
  (Working → Responding → Idle, streamed text), a tool-using turn (timeline +
  thinking panel + usage), Stop mid-turn, and airplane-mode mid-turn reconnect
  without duplicates — then merge `phase-3-live-chat` → `main` and start Phase 4
  (attachments). SERVER.md §2 (poppler for PDFs) is due before Phase 4; §4 (ntfy +
  messaging gateway) before Phase 7; the 4 CI signing secrets are still pending
  (without them every build uses the runner's throwaway debug key).
- **Stack reality (see AGENTS.md):** compileSdk/targetSdk/minSdk 36 — Google has not
  published the android-37 platform. No Room (no KSP for Kotlin 2.4); OkHttp 4.12.0.

## Phase 0 — Specs & server prerequisites (architect + owner)

- [x] AGENTS.md, SPEC.md, PROTOCOL.md, PLAN.md, SERVER.md written and verified against
      Hermes v0.21.6 source (plus live against the server: login, WS handshake, restart-proof
      refresh, approval/contract versions).
- [x] Owner/server: SERVER.md §1 (signing secret — verified restart-proof) and §3
      (9119 tailnet-only, verified) done 2026-10-11. §2 due before Phase 4; §4 before Phase 7.
- [x] `HERMES_PASSWORD` CI secret set; auto-login working (password confirmed in APK).
- [ ] Owner: create the 4 CI signing secrets (AGENTS.md "CI signing").

## Phase 1 — Skeleton, CI, login

- Tasks: Gradle wrapper, version catalog, `app/` module (min/target/compile 36 —
  37 unpublished, see status block),
  network security config (cleartext only `100.123.89.114`, `*.ts.net`),
  `.github/workflows/android.yml` (assemble + lint + unit tests; debug signing via secrets),
  Login screen (URL default `http://100.123.89.114:9119`, username, password),
  `TokenManager` (PKCE flow §2, proactive refresh, Keystore-backed DataStore),
  `GatewayClient` skeleton (OkHttp, connect with `?ticket=`, ready + capabilities handshake §3),
  Diagnostics screen logging each handshake step (redacted per SPEC R1.5) with
  "Export capture" (redacted frames as `.txt` via the share sheet).
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

- Tasks: session list/create/rename/delete (close live session before delete — 4023;
  rename uses lazy resume and never closes: the runtime may be shared with Desktop);
  open = resume with `inline_images:false` (4130 → REST paged fallback, PROTOCOL §1);
  check `info.desktop_contract` (≠8 → banner, PROTOCOL §3);
  `@image:` thumbnails via `/api/media`;
  render history rows (§7) incl. `@image:`/`@file:` refs, reasoning sidecars, tool rows;
  `sessions.changed` → refetch; persist stored id per open session; Sessions screen.
- Files: `gateway/SessionsApi.kt`, `gateway/SessionModels.kt`, `gateway/Transcript.kt`,
  `data/SessionCache.kt` (JSON files, not Room — no KSP exists for Kotlin 2.4),
  `media/ServerImages.kt`, `ui/SessionsScreen.kt`, `ui/HistoryScreen.kt`.
- Tests: resume-JSON parsing incl. `inflight`/`open_requests`; history projection cases
  (tool-only row dropped, `@image:` line parsed incl. quoting, hidden/`[System:`/empty
  skipped, reasoning attached); `@ref` quoting variants; cache filename sanitising.
  (`SessionsApi` WS calls are covered live, not mocked.)
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

## Design direction (decided 2026-10-12, built in Phase 8)

The visual world is chosen and parked: the category-standard Material 3 AI chat app, peers
Claude / Gemini / ChatGPT for Android, one fixed Hermes blue (#2563EB seed, no Dynamic
Color), light theme leading. Direction contract: `.impeccable/surfaces/*chatscreen*.md`;
critique reference: `.impeccable/mocks/decision/canon.png`; product truth: `PRODUCT.md`.

**Phases 3–7 build the agreed structure with default Material 3 styling** so Phase 8 restyles
rather than re-lays-out:
- Chat: top app bar with session title + live activity subtitle (from TurnReducer);
  user turns in bubbles, Hermes replies unbubbled full-width; tool use grouped as a
  collapsible "Used N tools" row; pill composer with attach left, circular send right that
  becomes Stop during a turn; a pending approval pins above the composer.
- No ad-hoc colours, fonts or spacing in screens: use `MaterialTheme` roles and type scale
  only, so the Phase 8 theme swap reaches everything.

## Phase 8 — Design build + polish

- Tasks: implement the direction contract via the impeccable skill (code-led): Hermes
  theme (fixed blue scheme, light + dark), component restyle across all screens, motion
  (send↔Stop morph, activity-line crossfade, honouring Remove animations), session list
  detail, launcher icon; code blocks + tables in replies; haptics; predictive back;
  empty/loading/error states; release build check. Then the finish review on emulator/device
  screenshots (phone, light + dark, font scale 1.3) and the documenter writes DESIGN.md.
- DoD:
  - [ ] Finish review verdict recorded; DESIGN.md + `.impeccable/design.json` written.
  - [ ] SPEC.md §4 acceptance boxes all hold on the Pixel.
