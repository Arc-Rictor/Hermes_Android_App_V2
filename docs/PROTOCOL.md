# PROTOCOL.md — Hermes dashboard v0.21.6 wire spec

Source of truth for every field on the wire. Extracted from the Hermes Agent
repository at tag `v0.21.6` (the exact version on jarvis-vps) plus its generated
contract `apps/shared/src/gateway-contract.openrpc.json`.

**Rule: if the live server disagrees with this file, stop and report. Do not adapt silently.**
Citations are `path:line@tag v0.21.6`.

## 1. Transport

- Base URL `http://100.123.89.114:9119` over Tailscale (WireGuard-encrypted). Plain HTTP is
  allowed **only** for `100.123.89.114` and `*.ts.net` (network security config).
- Public (no auth): `GET /api/status`, `GET /api/auth/providers`
  (`hermes_cli/dashboard_auth/public_paths.py`, `routes.py:203-212@v0.21.6`).
- Authenticated REST: `Authorization: Bearer <access_token>` (scheme case-insensitive),
  works on every non-public route; no CSRF/Origin checks
  (`dashboard_auth/middleware.py:169-181`, `request_utils.py:29-34@v0.21.6`).
- `GET /api/auth/me` → `{user_id,email,display_name,org_id,provider,expires_at}`; use as token check
  (`routes.py:524-553@v0.21.6`).
- JSON bodies: must be JSON objects; pre-stringified bodies get 422
  (`native-auth-decisions.ts:10-13@v0.21.6`). Public `/auth/*` POST bodies over 64 KiB get
  413 (`dashboard_auth/body_limit.py:12,25-28@v0.21.6`); other routes are not capped by it.
- `GET /api/media?path=<server path>` (bearer) → `{"data_url":"data:image/…;base64,…"}`.
  Serves images under the gateway's `<home>/images`, `screenshots`, `cache` only (403
  otherwise), extensions png/jpg/jpeg/gif/webp/svg/bmp/ico (415 otherwise), ≤ 25 MiB (413)
  (`web_routers/files.py:48-52,307-350@v0.21.6`). Uploaded attachments land in
  `<home>/images` (`prompt_attachments.py:133-158@v0.21.6`), so `@image:<path>` refs in
  history are fetchable here. Cache results locally by path.
- `GET /api/sessions/{stored_id}/messages?limit≤500&offset&order=latest|oldest&inline_images=false`
  (bearer) → paged transcript `{…,"pagination":{…}}` (`web_routers/sessions.py:723-770@v0.21.6`).
  Use only as fallback when `session.resume` fails with 4130 (resume too large).

## 2. Login (native PKCE, headless — no browser, no listener)

PKCE: `verifier` = base64url(32 random bytes, no pad, 43 chars);
`challenge` = base64url(SHA-256(ASCII verifier), no pad); `state` = base64url(24 random bytes).
Server recomputes and constant-time compares (`native_flow.py:72-75,156@v0.21.6`,
`electron/native-oauth.ts:51-70@v0.21.6`).

**Step 1 — `GET /auth/native/authorize`** (`routes.py:289-335@v0.21.6`), with redirects DISABLED.
Query: `provider=basic&code_challenge=<c>&code_challenge_method=S256&redirect_uri=http://127.0.0.1/cb&state=<s>`.
- `redirect_uri` rules: scheme `http`, host exactly `127.0.0.1` or `[::1]`
  (`localhost` rejected), any port/path/query, no fragment/userinfo
  (`routes.py:242-276@v0.21.6`). Port is arbitrary; **nothing needs to listen** — the server
  only stores the string (`native_flow.py:116-118@v0.21.6`).
- Success: `302 Location: /login`. Capture `Set-Cookie: hermes_session_pkce=<opaque>`
  (`HttpOnly`, `Path=/`, `Max-Age=600`) from the 302 and send it back as a `Cookie` header
  next step. OkHttp's default jar is `NO_COOKIES` — manage this one header manually
  (`cookies.py:47-72,131-144@v0.21.6`).
- Errors: 400 bad challenge/redirect; 404 unknown provider; 503 pending store full
  (256 total / 8 per IP, entries live 600 s — abandoned attempts count for 10 min).

**Step 2 — `POST /auth/password-login`** (`routes.py:439-493@v0.21.6`).
Headers: `Content-Type: application/json`, `Cookie: hermes_session_pkce=<v>`.
Body: `{"provider":"basic","username":"…","password":"…"}` (`next` ignored on native path — omit).
Response: `200 {"ok":true,"next":"http://127.0.0.1/cb?code=<gw_code>&state=<s>"}`.
No session cookies set; PKCE cookie cleared. **Verify `state` equals what was sent**
(`native-oauth.ts:162-187@v0.21.6`). Errors: 401 invalid credentials (pending login NOT
consumed — retry allowed), 429 after 10 tries/IP/60 s, 400 native login expired → restart at step 1.

**Step 3 — `POST /auth/native/token`** (`routes.py:569-586@v0.21.6`).
Body: `{"code":"…","code_verifier":"…"}`.
Response: `{"access_token","refresh_token","token_type":"Bearer","expires_at":<unix seconds>,
"provider":"basic","user_id":"<username>"}`. Code is single-use (consumed even on wrong
verifier — no retry, restart at step 1) and expires after 120 s (`native_flow.py:29,145-158@v0.21.6`).
All flow state is server memory: a server restart mid-flow means starting over.

**Refresh — `POST /auth/native/refresh`** (`routes.py:589-617@v0.21.6`).
Body: `{"refresh_token":"…","provider":"basic"}`. Same response shape; always store the new
pair. Refresh proactively at `now >= expires_at - 60` (`native-oauth.ts:242-253@v0.21.6`).
- 401 `{"error":"session_expired",…}` → delete tokens, back to login.
- 503 provider unreachable → keep tokens, backoff retry. Never treat as logout.
- One in-flight refresh per host; a second 401 reuses it instead of starting another.
- Access token default lifetime 12 h (`basic` plugin); refresh token 30 days, rolling
  (`plugins/dashboard_auth/basic/__init__.py@tag v0.21.6`).
- 401 on a REST call: refresh once, then retry **only** GETs and `POST /api/auth/ws-ticket`.
  Never auto-resend any other POST after a 401 (`oauth-rest-request.ts:94-127@v0.21.6`).
  Do not refresh on 403.
- Without `dashboard.basic_auth.secret` on the server, every server restart invalidates all
  tokens (refresh → 401) — the app must handle that as "sign in again", see SERVER.md §1.

## 3. WebSocket `/api/ws`

**Connect:** `POST /api/auth/ws-ticket` (bearer, no body) →
`{"ticket":"<urlsafe32>","ttl_seconds":30}` (`routes.py:556-564@v0.21.6`).
Mint a **fresh ticket for every connection attempt, including reconnects**.
URL: `ws://100.123.89.114:9119/api/ws?ticket=<urlencoded>`. (Alternative transports:
subprotocol `hermes-gateway-v1, hermes-gateway-ticket.<t>` or `?token=<access_token>` —
use `?ticket=` as primary; `web_server_chat.py:299-365@v0.21.6`.)
Omit the `Origin` header (sending one invites a check; absent skips it).
`Sec-WebSocket-Key` must be standard base64-with-padding of 16 random bytes — a base64url
unpadded key gets `400 Failed to open a WebSocket connection: invalid Sec-WebSocket-Key`.
OkHttp generates a valid key by default; only hand-rolled clients need care.
A rejected upgrade surfaces as HTTP 403 on the handshake; post-accept closes use
1011 (send deadline / fanout overflow) and 1000 (normal) (`ws.py:69,206-249,470@v0.21.6`).

**First frame (server):**
`{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{
"skin":{…},"change_events":true,"heartbeat":true,"replay_epoch":"<32hex>"}}}`.
No `session_id`, no `seq`, no version field (`ws.py:365-370`, `contracts/events.py:51-61@v0.21.6`).
Version compat number is `info.desktop_contract` = **8** inside session create/resume/activate
results (`tui_gateway/server.py:2280-2290@v0.21.6`). If it is not 8: continue, but show a
persistent "server protocol changed (contract N) — app built for 8" banner and log it in
Diagnostics. Do not hard-fail (a server update must not brick the app).

**Immediately after ready, send:**
`{"jsonrpc":"2.0","id":"a1","method":"client.capabilities","params":{"server_requests":true}}`
→ result `{"server_requests":[<method names>],"declines_not_shown":bool}`
(`contracts/liveness.py:29-48@v0.21.6`). Observed live on 2026-10-11:
`approval, clarify, display.install.sudo, preview.act, preview.read, secret, setup_choose,
sudo, terminal.read, tour, …`.
**Without this, every approval/clarify/sudo fails fast for this client.** Params reject unknown keys (`extra="forbid"` → error 4000;
`contracts/base.py:16`, `registry.py:94-109@v0.21.6`).

**Heartbeat:** if `gateway.ready.payload.heartbeat===true`, send
`{"jsonrpc":"2.0","id":"a<n>","method":"gateway.ping","params":{}}` (same id counter as all
client requests; reply `{"ok":true}`) every 15 s;
drop the socket if nothing arrives within 45 s (any inbound frame counts)
(`json-rpc-channel.ts:154-155,526-572`, `json-rpc-gateway.ts:177-180,423-426@v0.21.6`).
Server also pings at protocol level every 20 s (OkHttp answers automatically).

## 4. JSON-RPC framing

One JSON object per text frame each way; never send binary frames (`ws.py:195-205@v0.21.6`).

- Client request: `{"jsonrpc":"2.0","id":"a<n>","method":"session.create","params":{…}}`.
  Client ids: `"a"` + monotonic counter, never `srq-` (server-owned).
- Success: `{"jsonrpc":"2.0","id":"a<n>","result":{…}}`.
- Error: `{"jsonrpc":"2.0","id":"a<n>","error":{"code":…,"message":"…","data?":…}}`.
- Event: `{"jsonrpc":"2.0","method":"event","params":{"type":"…","session_id":"<runtime>",
  "payload?":{…},"seq?":<int>}}`. `seq` is per-session monotonic
  (`server.py:701-704@v0.21.6`). message/reasoning/thinking deltas batch ~33 ms.
- Server→client request: `{"jsonrpc":"2.0","id":"srq-<12hex>","method":"approval",
  "params":{"session_id":"…",…}}`. Answer: `{"jsonrpc":"2.0","id":"<same>","result":{…}}`
  (no `method` key; server sends no reply). Unimplemented method:
  `{"jsonrpc":"2.0","id":"<same>","error":{"code":-32601,"message":"no handler for server request: <m>"}}`
  (`json-rpc-channel.ts:61-62,363-411@v0.21.6`).
- Error codes: -32700 parse, -32600 invalid request, -32601 unknown method,
  -32602 invalid params, -32603 internal, -32000 handler error, 4000 param schema,
  4001 runtime id unknown → resume stored id, 4007 stored session not found,
  4009 busy, 4010 steer unsupported, 4015–4019 attachment errors (missing data / bad
  extension / bad base64 / too large / PDF page range), 4021 bad title, 4023 delete refused
  while live, 4090 active-session slots, 4130 resume too large (→ REST paging, §1),
  5028 `pdftoppm` missing on server, 5035 backend retiring (reconnect).
  Any other code: show `message` verbatim and log it.

## 5. Sessions

**Two ids.** Runtime id: 8-hex, in-memory (`methods_session.py:65-67@v0.21.6`). Stored id:
durable state.db key. `session.resume` takes the **stored** id (or exact title) and returns
the runtime id; `session.delete` takes the stored id; **everything else takes the runtime id**.
Events carry the runtime id (exception: `session.title` payload's `session_id` is the stored
key). Persist the newest `session_key`/`stored_session_id` from every reply — compression can
rotate the stored key (`server.py:3086-3087@v0.21.6`).
Runtime ids die ~20 s after disconnect if idle (`server.py:139-154@v0.21.6`); a dead runtime
id returns 4001 → `session.resume(stored_id)`.

- `session.create` — all params optional (`profile?,source?,cwd?,title?,model?,provider?,
  reasoning_effort?,service_tier?,idempotency_key?,messages?`, bools `close_on_disconnect?,
  hidden?,room_plumbing?,follow_profile_config?`). **Omit `cols`, do not send null**
  (`int(params.get("cols",80))`). Result: `{session_id(runtime),stored_session_id,
  message_count,messages:[],info:{…desktop_contract:8…}}`. No DB row until first submit
  (`methods_session.py:338-494@v0.21.6`).
- `session.list` — params `{profile?,title?(exact),limit?(default 200),include_hidden?}`.
  **No pagination over RPC** (REST `GET /api/sessions?limit≤100&offset` has it).
  Result `{sessions:[{id(stored),title,preview,started_at(epoch s),message_count,source}]}` —
  no `last_active` field (`methods_session.py:549-573,118-156@v0.21.6`).
- Stored ids look like `20261011_233258_cf1f51` (opaque — never parse, only store).
- `session.resume` — params `{session_id(stored) REQ,profile?,source?,lazy?,defer_history?,
  omit_messages?,eager_build?,close_on_disconnect?}`. Set **`inline_images:false` on mobile**
  (image parts become `"[image]"` instead of data URIs). Result (LiveSessionSnapshot):
  `{session_id(runtime),message_count,messages,info,stored_session_id?,resumed?,
  inflight?{user,assistant(streamed so far),streaming,display_kind?,display_metadata?,
  error?…},queued?{user},pending_approval?,open_requests?[{id,method,params}],
  pending_connection?,todo_state?}`. Cold/deferred/lazy paths send `inflight:null` and no
  `open_requests` — only the reuse-live path fills them (`methods_session.py:778-972`,
  `server.py:3183-3197@v0.21.6`). Re-deliver `open_requests` to the request handlers.
- `session.activate` — `{session_id(runtime) REQ,…}` rebinds this socket to a live session.
- `session.close` — `{session_id(runtime) REQ}` tears down live state; stored row stays.
- `session.delete` — `{session_id(stored) REQ}`; 4023 if live (close first), 4007 if missing.
- `session.history` — `{session_id(runtime) REQ}` → `{count,messages}`; images always inline,
  no paging. Message shape §7.
- `session.title` — **omit `title` to read**; `title:null/""` → 4021.
- `session.interrupt` — → `{status:"interrupted"|"not_interrupted"}`.
- `session.steer` / `session.redirect` — `{session_id,text REQ}` →
  `{status:"queued"|"redirected"|"rejected",text}`; `rejected` while idle means "send as prompt".
- `session.usage` — ints `input,output,reasoning,prompt,completion,total,calls` +
  `context_*?,cache_*?,cost_usd?…` (`contracts/common.py:19-44@v0.21.6`).
- `session.events.since` — `{session_id(runtime) REQ,last_seen?:int=0}` →
  `{events,latest_seq,truncated,count,epoch,open_requests}` (all REQ). `epoch` ≠ adopted
  `replay_epoch` → server restarted: clear seq watermarks. `truncated:true` → jump watermark
  to `latest_seq`, reload via resume/history (`json-rpc-gateway.ts:555-670@v0.21.6`).
  Replay buffer: 512 events / 4 MiB per session (`event_replay.py@tag v0.21.6`).
  It does **not** re-attach the socket to the session; only activate/resume do.

**Reconnect procedure (normative for this app).** After a new socket is ready and
`client.capabilities` is answered, for the open session:
1. Start holding live events for it.
2. `session.activate {session_id: runtime}` → rebinds; result carries `open_requests`,
   `pending_approval`, `inflight`. On 4001/4007 or a prior `session.reclaimed`: go to step 5.
3. `session.events.since {session_id: runtime, last_seen: watermark}`; on epoch change clear
   the watermark; on `truncated:true` go to step 5.
4. Apply replayed events with `seq > watermark` in seq order, then the held live events
   (same filter), then release the hold. Done.
5. Fallback: `session.resume {session_id: stored, inline_images:false}` → replace the
   transcript from the snapshot, reset the watermark to 0 for the new runtime id, and if a
   turn was in flight show a "reconnected — earlier live steps not shown" gap notice.
This orders activate before replay so no frame can fall between the two calls (the reference
client replays first; seq dedup makes the stricter order safe).

## 6. Prompt & attachments

`prompt.submit` params: `{session_id(runtime) REQ, text:"…", display_kind?("hidden" only),
title_preview?, queued?, interrupted?…}`. **No attachments param.**
Result: `{status?:"streaming"|"queued"|"steered"|"redirected", user_row_id?…}`
(`contracts/prompt_voice.py:26-78`, `methods_prompt.py:681-832@v0.21.6`).
Busy sessions queue/steer/redirect rather than refuse — which one is the server's
`display.busy_input_mode` (default: redirect the active turn). The app sends a plain
`prompt.submit` whenever the user hits Send and labels the bubble from `status`
("queued" / "steering" / "redirected"). Truncation-while-busy → 4009 (not used by the MVP).

**Upload size ceiling (client-side, applies to every attach call).** OkHttp refuses any
single outgoing WS message over 16 MiB (`RealWebSocket.MAX_QUEUE_SIZE`; `send()` returns
false). Base64 adds ⅓, so the **raw file limit is 11 MiB** for `image.attach_bytes`,
`pdf.attach` and `file.attach` alike. Check before encoding; over the limit, refuse with
"File too large to send (max 11 MB)". `send()` returning false is a bug, not a retry case.

- `image.attach_bytes` — `{session_id, content_base64? (or `data?`), filename?, ext?}`.
  Base64 may be bare or `data:image/<x>;base64,…` (strict decode). Cap **25 MiB decoded**.
  Accepted by extension: `.png .jpg .jpeg .gif .webp .bmp` — **filename suffix is checked
  first** (magic-byte fallback knows only PNG/JPEG/GIF/BMP — not WebP), **so always send a
  filename ending `.jpg`** after client-side conversion to JPEG (`prompt_attachments.py:20-23,64-79@v0.21.6`).
  Result includes `{attached,path,count,text,bytes,width?,height?,token_estimate?}`.
  Images queue server-side for the next submit automatically
  (`prompt_attachments.py:16,30-79`, `methods_prompt.py:887-915@v0.21.6`).
- `image.attach` — same but `{path}` for a gateway-visible file. Not usable from the phone.
- `image.detach` — `{session_id,path}` removes a queued image before submit.
- `pdf.attach` — `{session_id, content_base64?/data?, filename?, first_page?, last_page?}`.
  Cap **50 MiB, 25 pages/call**; requires `%PDF-` magic; server renders 150 dpi PNGs via
  `pdftoppm` (missing → 5028; needs installing per SERVER.md). Result lists rendered pages.
  PDFs longer than 25 pages: attach pages 1–25 and tell the user; do not loop.
- `file.attach` — `{session_id, path?/data_url?(any MIME or bare base64), name?}` → all-REQ
  `{attached,name,path,ref_path,ref_text,uploaded}`. **Queues nothing: the client must insert
  `ref_text` (`@file:<path>`) into the prompt text itself** (append on its own line).
  No server cap beyond WS frame limits; the 11 MiB client ceiling above governs.
- Attach calls happen when the user taps Send, immediately before `prompt.submit`, in order
  images/PDFs → files → submit. If any attach fails, do not submit; show the error and keep
  the draft and attachments.
- `request.answer {id,result}` → `{status:"ok"|"expired"}`: fallback RPC to answer a
  server request the client never received as a frame.

## 7. Events (notifications)

Envelope §4. Payloads (`contracts/events.py@v0.21.6`):

| type | payload → UI mapping |
|---|---|
| `gateway.ready` | handshake (§3) |
| `message.start` | no payload → state **Working** |
| `message.delta {text,rendered?,verbose?}` | append to reply → **Responding** |
| `message.interim {text,already_streamed}` | interim note; if `already_streamed:true` and deltas rendered, skip |
| `message.complete {text?,usage?,status?,reasoning?,error?,recoverable?,partial?,persisted_turn?}` | finalize reply; `persisted_turn.row_ids` for rewind |
| `thinking.delta` / `reasoning.delta` / `reasoning.available {text,…}` | append to thinking panel. `thinking.delta` is legacy; treat all three as reasoning and de-duplicate |
| `tool.generating {name}` | **Using tool: name** (no id yet) |
| `tool.start {tool_id,name,context?,args?,args_text?,preview?}` | timeline entry opened; pair by `tool_id` |
| `tool.complete {tool_id,name,duration_s?,result?,summary?,result_text?…}` | timeline entry closed (result truncated display) |
| `tool.output_risk {tool_id,name,risk,findings[],redacted}` | warning badge on the entry |
| `status.update {kind,text}` | kinds: status/lifecycle/compacting/goal/loop/heartbeat/process → **Working** line |
| `todo.updated {todos[],revision}` | task list panel |
| `session.info` | SessionLiveInfo (model/provider/title/usage/…); refresh header |
| `session.title {session_id(stored!),title}` | rename row |
| `session.usage {usage}` | mid-turn usage tick |
| `sessions.changed {}` | refetch list |
| `notification.show {text,level,kind,ttl_ms?,key?,id?}` / `notification.clear {key}` | toast/stack; withdraw on clear |
| `notice {message}` / `error {message}` | banner |
| `request.cancel {id,method,reason}` | withdraw the matching card; reasons incl. timeout/interrupted/shutdown/resolved/session_closed |
| `approval.cancelled {session_id,stored_session_id,reason,cancelled_count,request_ids[]}` | broadcast: withdraw listed cards |
| `background.complete {task_id,text}` | background result notice |
| `session.reclaimed {session_id,stored_session_id,reason}` | runtime id dead → resume |

Assistant streaming has no message id: sequence per session is `message.start` → deltas/interim
→ `message.complete`. History rows (`session_history.py:285-364@v0.21.6`): roles user/assistant/
tool/system as `{role,text,timestamp?,row_id?,display_kind?,display_metadata?}`; assistant rows
may carry `reasoning*` sidecars but **no `tool_calls`** (textless tool-only rows dropped);
tool rows carry only name/context preview (`content` only for write_file/patch/skill_manage);
images flattened to data URI or `[image]`; user-attached images appear as trailing
`@image:<path>` lines — the client parses `@image:`/`@file:` refs itself
(`prompt_attachments.py:25-26,162-169@v0.21.6`). Quoting rule: a value containing whitespace
or any of ``()[]{}<>"'` `` is wrapped in the first of `` ` ``, `"`, `'` it does not contain;
otherwise it is bare. Parse `@image:`/`@file:` followed by either a quoted run or a
non-whitespace run. Render `@image:` refs as thumbnails fetched lazily via
`GET /api/media?path=` (§1); render `@file:` refs as a file chip (name only, no download in MVP).

## 8. Server→client requests

- `approval` params: `{request_id REQ, command?="", description?="", choices?[],
  allow_permanent?, allow_session?, smart_denied?, tool_name?…}` (extra keys allowed).
  Result: `{choice:"once"|"session"|"always"|"deny" REQ, all?}`. **Show only listed choices**
  (server computes: always once…deny; session unless smart_denied; always only with session).
  Missing/empty choice = deny. `all:true` resolves every pending approval. Timeout default
  300 s (`approvals.timeout`); expiry arrives as `request.cancel`. -32601 → queue entry
  withdrawn, not denied (`server.py:765-845,1398@v0.21.6`,
  `contracts/server_requests.py:100-130,252-267@v0.21.6`).
  **Two answer paths:** a card created from a live `approval` frame or an `open_requests`
  entry is answered with the JSON-RPC response to that `srq-` id. A card created only from
  a snapshot's `pending_approval` (no matching open request) is answered with the RPC
  `approval.respond {session_id(runtime), choice, request_id}` → `{resolved:int}`, using a
  300 s client timeout (`contract ApprovalRespondParams`; desktop `store/prompts.ts:343-388@v0.21.6`).
  If both exist for the same `request_id`, use the `srq-` path.
- `clarify` params: `{questions:[{qid,question,choices?|null,multi_select=false}]}`.
  Result: `{answers:{qid: str|null}}`; **no `answers` key cancels all**. `choices==null` =
  free text (multi-select answer encoding unconfirmed — send one submit with all answers;
  do not use `clarify.lock`). Timeout: `clarify timeout` else 300 s; ≤0 = none.
  Undelivered (no capabilities) → tool sees `outcome:"undelivered"`.
- `sudo {command?}`, `secret {env_var REQ,prompt REQ,metadata?}`,
  `vault.unlock_prompt {backend,display_name}`, `vault.code {site?,hint?}` →
  `{value:"…"}` (`""` = skipped/declined). Masked input in UI.
- `terminal.read/preview.read {start?,count?}`, `window.read {}`, `preview.act`,
  `tour`, `display.install.sudo`, `setup_choose`, vault.save_login, `connection.request`
  (an **event**, answered via `connection.respond` RPC): answer -32601 on the phone
  (server falls back gracefully), except implement `connection.respond` only if cheap.
- `open_requests` on resume/activate/events.since replays have the same ids as live frames —
  process each as a live request, dedupe by id; answering twice is harmless
  (`json-rpc-channel.ts:413-482@v0.21.6`).
