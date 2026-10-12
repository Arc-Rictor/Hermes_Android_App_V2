---
version: 1
slug: "ava-com-arcrictor-hermes-ui-chatscreen-kt-97f34020"
primary_target: "app/src/main/java/com/arcrictor/hermes/ui/ChatScreen.kt"
related_targets: ["app/src/main/java/com/arcrictor/hermes/ui/SessionsScreen.kt","app/src/main/java/com/arcrictor/hermes/ui/HistoryScreen.kt"]
---

## Scope & mode

Hermes Android app, all screens, anchored on the Chat screen (`ui/ChatScreen.kt`, Phase 3).
Mode: **Operate**. Platform: Android, Material 3 governs structure and interaction.

## Audience, job, constraints

Two people sharing one self-hosted Hermes agent, mostly **daytime, on the move, one hand**,
split evenly between quick check-ins and long working sessions (PRODUCT.md). Job: see the
agent's true state at a glance, talk to it, approve its commands, hand it photos/files.
Constraints: light theme leads (daylight); dark theme first-class; 48 dp targets; system font
scaling; state never by colour alone.

## Chosen direction

The category standard, chosen on purpose (canon card, code-led). Peers and bar: Claude, Google
Gemini, ChatGPT for Android. One fixed Hermes colour. Critique reference comp:
`.impeccable/mocks/decision/canon.png` (decision comp; code-led, so no approved build comp).

Memorable moment: the top bar's activity line is always true — Thinking, Running terminal ·
0:14, Needs your approval — and the send button becomes Stop while Hermes works.

## Direction contract

THESIS: The standard modern AI chat app, executed to the Claude/Gemini/ChatGPT Android bar. It
owns one thing peers don't: an always-true live activity line for an agent running real tools.
It refuses costume, novelty chrome and decorative motifs.

OWN-WORLD: White surface, near-black #111827 text, one fixed Hermes blue #2563EB seeding a
Material 3 tonal scheme (no Dynamic Color). Roboto on the M3 type scale. User turns in filled
primary bubbles (20 dp radius); Hermes replies as unbubbled full-width text, as peers do for
long answers. Tool use as a collapsible "Used N tools" chip; approvals as outlined cards; pill
composer.

STORY: Open → session list with live dots → tap → true state in the top bar within a second →
type and send → watch the activity line and streaming reply → approve inline → pocket the
phone; return to an intact transcript.

FIRST VIEWPORT: Top app bar: back, session title, live activity subtitle. Transcript fills the
middle. A pending approval pins above the composer. Bottom: pill composer with paperclip left
and a circular send button right that morphs to Stop during a turn — the primary action.

FORM: Category standard (standing exit), outside the ordered list; seed key 9ad99622.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Unresolved

- Dark-scheme tuning and launcher icon: at the Phase 8 build.
- Session list detail (title, preview, relative time, live dot, swipe actions): Phase 8 build,
  inside the contract above.
