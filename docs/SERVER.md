# SERVER.md — operator tasks (jarvis-vps)

Hermes dashboard v0.21.6 is already running at `http://100.123.89.114:9119` with
`auth_providers: ["basic"]`. The app needs nothing installed server-side for MVP chat.
These items are ordered by when they block the app.

## 1. Sign-in survival (before Phase 1 device testing)

Set a stable signing secret so tokens survive restarts, otherwise every server restart
signs the phone out (refresh → 401 `session_expired`):
set `dashboard.basic_auth.secret` in `~/.hermes/config.yaml` (or env
`HERMES_DASHBOARD_BASIC_AUTH_SECRET`, which wins) to e.g. `openssl rand -base64 32`
(≥ 16 bytes), then restart the dashboard. Without it the key is random per process
(`plugins/dashboard_auth/basic/__init__.py:187-210@tag v0.21.6`). Optional: token lifetime
via `dashboard.basic_auth.session_ttl_seconds` (default 12 h).

## 2. PDF attachments (before Phase 4)

`pdf.attach` renders pages server-side with `pdftoppm`. Install poppler-utils on the
server, or PDFs fail with 5028.

## 3. Exposure check (any time, do early)

Confirm port 9119 is reachable **only** over Tailscale: it accepts logins over plain HTTP,
so it must not be visible on the VPS's public interface. From off-tailnet:
`curl -m 8 http://<public-ip>:9119/api/status` must fail.

## 4. Push notifications (before Phase 7)

- Self-host ntfy on the tailnet (private, credentialed) and enable Hermes' ntfy
  integration so the agent/cron can message the owner.
- Start the messaging gateway (`gateway_state` is currently `stopped`) — ntfy delivery
  and cron runs go through it, not the dashboard.

## 5. Password hygiene

The dashboard password was shared in a chat transcript. Rotate it once the app's login
works. Prefer storing `dashboard.basic_auth.password_hash` over a plaintext `password`
(the basic plugin's `hash_password()` produces it). The password is stored nowhere in this
repo (and must never be). The app keeps working across a password change — tokens stay
valid until they expire or the secret changes.

**Lost phone / revoke access:** tokens are stateless and refresh tokens roll forward
indefinitely (logout revokes nothing server-side). Changing the password does **not**
revoke the phone. The only revocation is rotating `dashboard.basic_auth.secret` and
restarting, which signs out every client.
