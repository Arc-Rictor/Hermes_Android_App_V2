# SERVER.md — operator tasks (jarvis-vps)

Hermes dashboard v0.21.6 is already running at `http://100.123.89.114:9119` with
`auth_providers: ["basic"]`. The app needs nothing installed server-side for MVP chat.
These items are ordered by when they block the app.

## 1. Sign-in survival ✅ DONE 2026-10-11

`HERMES_DASHBOARD_BASIC_AUTH_SECRET` is set in `/home/jarvis/.hermes/.env` (service
`hermes-dashboard.service`, user `jarvis`). Verified: full PKCE login → dashboard restart →
`POST /auth/native/refresh` with the pre-restart refresh token returned 200. The phone will
stay signed in across restarts. Do not remove that line. (Access tokens 12 h, refresh
rolling 30 d.)

## 2. PDF attachments (before Phase 4)

`pdf.attach` renders pages server-side with `pdftoppm`. Install poppler-utils on the
server, or PDFs fail with 5028.

## 3. Exposure check ✅ DONE 2026-10-11

Verified: 9119 listens **only** on `100.123.89.114` (the tailnet IP), never `0.0.0.0` —
nothing on the public interface can reach it even before the firewall. Traefik (ports
80/443) has no route to 9119. UFW default-drops everything except 80/443 and the
`tailscale0` interface. Plain-HTTP login is therefore tailnet-only. Re-check after any
networking change with `ss -tln | grep 9119` on the VPS.

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
