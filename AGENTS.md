# AGENTS.md — Hermes Android App V2

Native Android client for a remote Hermes Agent gateway (dashboard) over Tailscale.
Read `docs/SPEC.md`, then `docs/PROTOCOL.md`, then the phase file in `docs/PLAN.md` before writing code.

## Stack (fixed)

- Kotlin, Jetpack Compose (Material 3), single Gradle module, no DI framework.
- OkHttp 4.x (HTTP + WebSocket), kotlinx.serialization, Room, DataStore, CameraX, Coil 3.
- `minSdk 37`, `targetSdk 37`, `compileSdk 37` (Android 17 = API 37). Java 17.
  Use the latest **stable** AGP, Kotlin, Compose BOM and library versions at the time of
  Phase 1 that support compileSdk 37; pin them in `gradle/libs.versions.toml`. No alphas/betas
  except where no stable release supports API 37 (note it in the version catalog).
- Package: `com.arcrictor.hermes`. App name: Hermes.
- Server: Hermes dashboard **v0.21.6** at `http://100.123.89.114:9119` (Tailscale only).

## Rules

1. **PROTOCOL.md is the contract.** Every request/response field, error code and limit is
   specified there with citations to upstream `file:line@v0.21.6`. Never invent a field,
   event name or param. If the live server disagrees with PROTOCOL.md, stop and report it —
   do not silently adapt.
2. **Never commit secrets.** No passwords, tokens, keystores, `local.properties`,
   `google-services.json`. Debug keystore for CI signing comes from GitHub secrets only.
3. **No local Android SDK here — verify via CI.** Push to a feature branch and watch
   `.github/workflows/android.yml`. Never push to `main`; maintainer merges.
4. **Password is typed once, never stored.** Persist only the token set
   (access/refresh/provider/user_id/expires_at), encrypted via Android Keystore + DataStore.
5. **Cleartext HTTP only for the tailnet.** Network security config permits cleartext
   solely for `100.123.89.114` and `*.ts.net`. Everything else is HTTPS-only.
6. **Single source of truth for turn state:** the `TurnReducer` (pure function:
   `TurnState + GatewayEvent -> TurnState`). All event application goes through it and it
   has JVM unit tests replaying recorded frames. UI renders state; it never interprets events.
7. **Two session ids — keep them straight.** `storedSessionId` (durable, survives restarts,
   used for `session.resume`/`session.delete`) vs `runtimeSessionId` (8-hex, used for every
   other call, dies ~20 s after disconnect). Variable names must say which one.
8. **JSON-RPC ids:** client requests use `"a<n>"` counter ids. Never `srq-` (server-owned).
   Fail all pending calls on socket drop; ignore callbacks from a stale socket.
9. **`send client.capabilities {server_requests:true}` on every new socket**, right after
   `gateway.ready`. Without it the server auto-fails every approval/clarify request.
10. **Keep it small.** One screen per file group, no speculative abstractions, no features
    outside the current phase file. V1 lives at
    `github.com/Arc-Rictor/Hermes-Agent-Andriod-App` (private; local copy
    `../Arc-Rictor/hermes-agent-android-app`). Port only `data/Attachments.kt` (downscale)
    and `ui/components/MarkdownText.kt` (dependency-free markdown) when a phase needs them.
    Do not copy V1's networking (it is SSE/chat-completions; V2 is WebSocket JSON-RPC),
    ntfy stack, Compare mode or caveman mode.
11. **On-device checks belong to the owner.** CI cannot reach the tailnet. When a phase DoD
    needs a live check, finish everything else, then list the exact on-device steps in the
    PR description and stop. The owner installs the CI artifact and reports back.

## Build & check

```bash
./gradlew assembleDebug          # CI runs this; also lintDebug + testDebugUnitTest
```

- Unit tests are JVM-only (`src/test/`), replaying recorded gateway frames — no network, no emulator.
  Frame fixtures live in `app/src/test/resources/frames/*.jsonl`, built from the examples in
  PROTOCOL.md until real captures exist; the Diagnostics screen can export a redacted live
  capture to replace them.
- CI signing: the workflow decodes secret `DEBUG_KEYSTORE_B64` (+ `DEBUG_KEYSTORE_PASSWORD`,
  `DEBUG_KEY_ALIAS`, `DEBUG_KEY_PASSWORD`) so every build has the same signature and installs
  over the previous one. If the secrets are absent the build falls back to the runner's
  default debug key with a warning (that APK will not install over a secret-signed one). Artifact: `hermes-debug.apk`; owner fetches with `gh run download`.
- Each phase in `docs/PLAN.md` has a Definition of Done. Do not mark a phase complete
  until CI is green and every DoD box holds.
- Live-server verification (login → ready → resume → turn) happens on-device via the
  Diagnostics screen, never from CI (runners have no tailnet access).
