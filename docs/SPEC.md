# SPEC.md — Hermes Android App V2

## 1. What it is

A native Android chat client for the owner's remote Hermes Agent gateway
(dashboard v0.21.6 on jarvis-vps, reachable only over Tailscale at
`http://100.123.89.114:9119`). One user, one gateway, one phone (modern Pixel, Android 17+).

Non-goals: multi-user, Play Store release, FCM push, on-device inference,
managing the gateway itself (updates, providers, cron) beyond what chat needs.

## 2. Requirements

### R1 Connection & login
- R1.1 Server URL configurable (default `http://100.123.89.114:9119`), username + password login.
- R1.2 Login uses the native PKCE code flow (PROTOCOL.md §2); password is sent once, never stored.
- R1.3 Only the token set is persisted: one JSON blob AES-256-GCM-encrypted with a
  non-exportable AndroidKeyStore key, ciphertext stored in DataStore (no
  EncryptedSharedPreferences — deprecated). Access token refreshed proactively (60 s before
  `expires_at`); 401 `session_expired` on refresh → back to login with username pre-filled.
- R1.5 Logs and Diagnostics never contain the password, tokens, tickets or cookie values
  (redact to the first 6 chars + `…`).
- R1.4 Connection errors name the likely cause, including a "check Tailscale" hint when the
  host is unreachable on a `100.x` address. A Diagnostics screen shows each handshake step
  (status → authorize → login → token → me → ticket → socket → ready) with pass/fail.

### R2 Sessions
- R2.1 List sessions (stored id, title, preview, message count), create, resume/open, rename, delete.
- R2.2 Opening a session loads history and shows any in-flight turn and open approval/question
  cards exactly as the live turn would (`inflight`, `open_requests` from `session.resume`).
- R2.3 The app tracks both ids per open session but displays only the title.

### R3 Chat & agent activity (core)
- R3.1 Send text; stream the reply token-by-token; Stop button (`session.interrupt`).
  Send stays enabled while the agent is busy: it is a normal `prompt.submit` and the bubble is
  labelled from the result status (queued / steering / redirected). No separate steer UI.
- R3.2 An activity header always shows the true state. `Needs input` wins whenever an
  approval/question card is open. Otherwise the state follows the latest event
  (PROTOCOL.md §7):
  | Event | State |
  |---|---|
  | `message.start`, `status.update` | Working (show status text) |
  | `reasoning.delta`, `thinking.delta`, `reasoning.available` | Thinking |
  | `tool.generating`, `tool.start` (until its `tool.complete`) | Using tool: <name> |
  | `message.delta` | Responding |
  | `message.complete` | Idle (or Error/Stopped from its `status`) |
  While any tool is open, `Using tool` is held even if reasoning deltas interleave.
  A turn shows an elapsed timer while not Idle.
- R3.3 Thinking/reasoning renders in an expandable panel (never mixed into the reply text).
- R3.4 Tool calls render as a timeline: start (name + argument preview) → outcome
  (duration, result summary or error). Failed tools are visibly distinct.
- R3.5 Mid-turn commentary from the agent renders as interim notes, not as the reply.
- R3.6 Token usage for the turn is shown when the server reports it.

### R4 Attachments
- R4.1 Gallery pick (multiple images), instant camera capture (in-app CameraX sheet),
  generic file picker.
- R4.2 Images go via `image.attach_bytes` (every image re-encoded to JPEG q80, ≤1568 px
  longest side, filename `*.jpg`, EXIF orientation applied). PDFs via `pdf.attach` (first 25
  pages). Other files via `file.attach` with the returned `ref_text` appended to the message.
  Hard client limit 11 MB per file (WS frame ceiling); larger files are refused with a message.
- R4.3 Receiving shares from other apps (text, images, files) opens a session picker, then the
  composer pre-filled. Images in history render as thumbnails via `/api/media`; files as chips.

### R5 Approvals & questions
- R5.1 Approval cards show command + description and only the offered choices
  (`once`/`session`/`always`/`deny`); no answer on timeout — the card expires visibly.
- R5.2 Clarify cards render each question with its options (single/multi) or free text;
  one submit answers all; `clarify.lock` is not used (single submit per card).
- R5.3 `sudo`/`secret`/vault prompts use masked input. Unhandled request types are answered
  `-32601` and logged, never crash.

### R6 Background & reconnect
- R6.1 A turn survives leaving the app: while a turn is running and the app is backgrounded,
  a foreground service (`dataSync` type; Android caps this at 6 h/day, ample for chat) keeps the
  socket alive and posts an ongoing notification showing the activity state. The service
  stops on `message.complete` or after 60 s idle. When the app is backgrounded with no turn
  running, the socket closes (server keeps the session; reconnect per PROTOCOL.md §5).
- R6.4 A completed turn while backgrounded posts a "reply ready" notification that opens
  the session.
- R6.2 On reconnect: replay missed events (`session.events.since` with seq watermark + epoch
  check), re-deliver `open_requests`, fall back to history reload when replay is truncated.
- R6.3 Nothing is silently lost: if recovery fails, the transcript shows an explicit gap notice.

### R7 Push (later phase)
- R7.1 Agent-initiated messages arrive via the owner's private ntfy server; tapping one
  deep-links to the session (`hermes://session/<stored-id>`). Requires the messaging
  gateway running — see `docs/SERVER.md`. Not in the MVP.

## 3. Architecture

```
ui/            Compose screens (Login, Sessions, Chat, CameraSheet, Diagnostics)
               Render TurnState/SessionList only. No protocol logic.
gateway/       GatewayClient: OkHttp WS + JSON-RPC framing, request/response map,
               server→client request dispatcher, heartbeat, reconnect, replay.
               TokenManager: PKCE login, refresh, EncryptedDataStore.
turn/          TurnReducer: pure (TurnState, GatewayEvent) -> TurnState. JVM-tested.
data/          Room: sessions cache + last-seen transcript cache (shown instantly on open,
               replaced by the server snapshot). Seq watermarks stay in memory only —
               after an app kill, recovery uses session.resume.
media/         Downscale/convert (JPEG/PNG; HEIC→JPEG), PDF pass-through, base64 framing.
work/          Foreground service wrapper + notification/progress.
```

- One `GatewayClient` per app process; one active session socket binding at a time.
- Server is authoritative for sessions, transcript and turn state. Local Room is a cache;
  on conflict the server wins and the UI says so.
- Error taxonomy: `Auth` (re-login), `Network` (retry + Tailscale hint), `Protocol`
  (log + report, never silently adapt), `Server` (show message).

## 4. Acceptance (whole app)

- [ ] Fresh install → login → session list → open session → full history renders.
- [ ] Send "hello" → activity header walks Working → Responding → Idle; reply streams.
- [ ] Long tool-using turn → Using-tool timeline entries with durations; thinking panel fills.
- [ ] Approval command → card with correct choices; answer `once` → turn continues.
- [ ] Photo via camera sheet reaches the agent; PDF pages reach the agent; a .zip arrives
      as an `@file:` ref the agent can read; a 20 MB file is refused with a clear message.
- [ ] Kill the app mid-turn → reopen → transcript complete, no gap or an explicit gap notice.
- [ ] Airplane-mode mid-turn → reconnect replays without duplicate messages.
- [ ] Token survives 12 h+ via refresh; server restart with `basic_auth.secret` set keeps login.
