# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Two people — the owner and Kirsty — who share one Hermes agent running on their own server
(jarvis-vps). Each may use the app on their own phone (modern Google Pixel, Android 17+).
Kirsty also reaches the same agent through Hermes Desktop, so a session can be live on the
phone and the desktop at once.

Use is split evenly between two modes:
- **Quick check-ins** — a short ask, a glance at what the agent is doing, phone back in the
  pocket while the agent keeps working.
- **Long working sessions** — extended back-and-forth, watching tools run, reading long
  replies, approving commands.

## Product Purpose

A private, native phone window onto their own Hermes agent: talk to it, see truthfully what it
is doing (working, thinking, running a tool, waiting for approval), hand it photos, camera
shots and files, and answer its questions — from anywhere on their Tailscale network.
Success: either user can pick up the phone, see the agent's real state in under a second, and
act without friction; nothing the agent does is hidden or lost.

## Positioning

Not a generic AI chat app. It is the remote control and window for one specific, capable,
self-hosted agent that runs real tools on a real machine. Its distinguishing job is
**legibility of agent activity** — honest, live visibility into thinking, tool use and
pending approvals — which consumer chat apps don't provide.

## Operating Context

- Connection is always over Tailscale to the Hermes dashboard on jarvis-vps (v0.21.6).
- The agent works for minutes at a time; the phone may lock or switch networks mid-turn.
- Sessions are shared server-side and visible from other surfaces (Hermes Desktop).
- Approvals gate dangerous commands; an unanswered approval stalls the agent.

## Capabilities and Constraints

- Chat with streaming replies; live activity states; reasoning; tool timeline; stop/redirect.
- Sessions: list, create, open, rename, delete; transcript history with images and file refs.
- Attachments: gallery photos, instant camera capture, any file (≤ 11 MB each).
- Approvals and clarifying questions from the agent, answered in-app.
- Credentials are built into the app (owner decision); sign-in is automatic.
- Terminology: the agent is **Hermes**; a conversation is a **session**; agent tool calls are
  **tools**; gated commands are **approvals**.
- Undecided: push notifications for agent-initiated messages (later phase, via ntfy).

## Brand Commitments

- Name: **Hermes** (the agent, from Nous Research's Hermes Agent, and the app).
- Agent voice (from its SOUL.md): direct, no filler, plain claims, short reports. App copy
  should match: terse, factual, never chirpy.
- **Visual standing preference (2026-10-12):** the category standard, played straight — a
  modern Material 3 AI chat app. Peers and quality bar: Claude, Google Gemini and ChatGPT for
  Android. One fixed Hermes colour on every phone (no wallpaper-derived Dynamic Color).
  Differentiation comes from agent-activity legibility, never from visual quirk.

## Evidence on Hand

- Live server and real sessions; no marketing assets, logo files or illustrations exist.
  The current launcher icon is a placeholder.

## Product Principles

1. **State is truth.** The app never shows an agent state the server didn't report, and never
   hides one it did.
2. **Glanceable first, deep on demand.** The current state and latest output are readable in a
   glance; reasoning and tool detail expand when wanted.
3. **Never lose work.** A dropped connection, locked phone or killed app must not lose a
   message, a reply or a pending approval.
4. **Agent's tempo, user's control.** Long-running work proceeds in the background; stop,
   redirect and approve are always one tap away.

## Accessibility & Inclusion

No specific needs reported. Baseline: system font scaling, TalkBack labels, 48 dp touch
targets, contrast meeting WCAG AA, and state never conveyed by colour alone.
