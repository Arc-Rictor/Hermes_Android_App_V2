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

## 4. Push notifications ✅ DONE 2026-10-12

- ntfy 2.30.0 is installed as a system service at
  `https://jarvis-vps.tail0b8136.ts.net:2586`, bound only to `100.123.89.114`.
  It uses a Tailscale HTTPS certificate; a systemd timer renews it before expiry.
- Access control defaults to `deny-all`. The `hermes-gateway` account has read/write
  access to the private topic; `hermes-phone` is read-only. Credentials are in the
  server's `/home/jarvis/.hermes/.env` and the owner's local system keyring, never here.
- Hermes ntfy is configured as the default profile's home channel. The persistent
  `hermes-gateway` user service is active (systemd linger enabled).
- Anonymous read was verified as 403, phone read as 200, phone publish as 403, and
  gateway read as 200. The phone app still needs to be subscribed and tested.

## 5. Password hygiene ✅ DONE 2026-10-12

The dashboard password was rotated after the app's login was working. The server now uses
`dashboard.basic_auth.password_hash`; the plaintext password entry was removed from `.env`
and the local config backups. The `HERMES_PASSWORD` GitHub Actions secret was updated to
the same new password; it is not stored in this repo. Existing app tokens remain valid
across the password change. Future CI APKs carry the rotated fallback password.

**Lost phone / revoke access:** tokens are stateless and refresh tokens roll forward
indefinitely (logout revokes nothing server-side). Changing the password does **not**
revoke the phone. The only revocation is rotating `dashboard.basic_auth.secret` and
restarting, which signs out every client.
