# Family Chat — Design Guideline

The rules the Android client is built on, so anything added later matches what is
already there. Originally written against **v3.0.30**; check the current implementation when applying these rules to newer features.

This is intended to describe the implemented UI, not an aspiration. If you
change the code, update this file alongside it.

The short version: **one primitive per job.** The app used to feel chaotic
because the same job was solved several different ways — two row components in
settings and a third in the conversation list, three dialog chassis across 32
dialogs, 13 corner radii, no rule separating one button style from another.
Repetition is what reads as calm.

---

## 1. Foundations

All tokens live in `app/src/main/java/com/example/chat/ui/theme/`. Nothing
outside that folder should declare a colour, radius, duration or spacing value.

That rule exists because it was broken once: motion constants, a semantic colour
and a component token type were declared in `message/RichComposerBridge.kt`, a
text-composer helper. Because the read-receipt colour was defined beside a
composer instead of in the theme, it never received dark-mode handling and
shipped failing contrast at 1.76:1.

### 1.1 Colour — `Color.kt`, `Theme.kt`

The page is tinted and raised things are white. This is the opposite of the
original scheme, where page, card and bubble sat at `#F7FBF6`, `#F1F7F0` and
`#FFFFFF` — **1.04:1 and 1.09:1 apart**. That is not a subtle separation, it is
no separation, and it is why cards never read as cards no matter how correctly
they were tokenised.

| Role | Light | Dark |
|---|---|---|
| `background` (page, chat thread) | `#E7EFE9` | `#0F1512` |
| `surface` (cards, bubbles, sheets) | `#FFFFFF` | `#1B221E` |
| `primary` (accent, section labels) | `#2E8168` | `#89D5B4` |
| `primaryContainer` (own bubble) | `#B6EFD3` | `#00513B` |
| `error` | `#BA1A1A` | `#FFB4AB` |

Page against surface is **1.17:1**. That is the working minimum for "this is
raised"; anything closer disappears.

**The filled app bar has its own pair and must not use `primary`.** In a
Material 3 dark scheme `primary` is deliberately a light tint, because its job is
legibility *on* dark surfaces. A bar filled with it lands 10.77:1 above the page
and glares.

| | Container | Content |
|---|---|---|
| Light | `#2E8168` | `#FFFFFF` |
| Dark | `#164A37` | `#DCEFE5` |

Read via `appBarColors()`, which detects dark from the scheme's own background
rather than `isSystemInDarkTheme()`, so it stays correct when the user forces a
mode in Settings.

**Read receipts** are a light/dark pair (`#0B57D0` / `#7CC6FF`) because the tick
sits *on* the outgoing bubble and must contrast with the bubble, not the page.
They live in `ChatBubbleTokens`, not as a global constant.

#### Contrast minimums

Enforced by `ChatBubbleTokensTest` for bubbles; apply the same everywhere.

- Body text on its background: **4.5:1**
- Icons, ticks, and other non-text UI: **3:1**
- Test the *actual* pairing. A colour is not "accessible" on its own — the
  receipt tick passed on the dark bubble and failed on the light one.

### 1.2 Type — `Type.kt`

All 15 Material 3 roles are defined. Do not leave any undefined: an unspecified
size falls back to a ramp built for different proportions, and throws outright
if anything multiplies it.

The ramp runs slightly larger than stock M3 — `titleMedium` 17sp, `bodyMedium`
15sp, `labelSmall` 11sp Medium — for an audience spanning several generations.

**Text size is a user setting.** `AppTextSize` (`SMALL` 0.92 / `MEDIUM` 1.0 /
`LARGE` 1.14) scales the whole ramp through `chatTypography()`, so proportions
between roles hold at every setting. Sizing type "a bit larger for this audience"
was the app guessing on behalf of a family; each person decides now.

Never set `fontSize` inline. Use a role. A hardcoded `fontSize = 12.sp` is how
the conversation list ended up outside the scale.

### 1.3 Shape — `Shape.kt`

Four rectangular steps plus a pill. There were 13 hardcoded radii before; a 13dp
corner beside a 14dp one is invisible alone, and collectively it is why nothing
looked aligned with anything.

| Token | Radius | Use |
|---|---|---|
| `extraSmall` | 8dp | inline chips, small clipped thumbnails |
| `small` | 12dp | group avatars, icon tiles, compact cards |
| `medium` | 16dp | **the default** — cards, sheets, bubbles, banners |
| `large` | 28dp | dialogs, large containing surfaces |
| `FullShape` | pill | anything fully rounded |

`extraLarge` intentionally equals `large`. Use `FullShape` rather than
half-the-height dp values: `RoundedCornerShape(24.dp)` on a 48dp control stops
being a pill the moment the height changes.

**No `RoundedCornerShape(N.dp)` literals outside `Shape.kt`.**

### 1.4 Spacing — `Spacing.kt`

`xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`

There were 342 hardcoded `dp` literals across 55 distinct values. Off-grid values
(6, 10, 14, 18) are what made layouts feel unsettled without any single screen
looking wrong.

**Screen edge inset is always `Spacing.lg`.** Every screen shares one margin.

### 1.5 Motion — `Motion.kt`

`MotionShort 140 · MotionMedium 240 · MotionLong 400 · MotionExtraLong 600`

Springs for things arriving under their own steam — `arrivalSpring()` for a
message landing, `placementSpring()` for list items settling. Tweens for
transitions that must finish in lockstep with something else, such as a
navigation crossfade.

Respect the user's reduced-motion preference where the platform exposes it.

**Navigation motion is decided by depth, never by hand.**
`familyChatNavigationDepth()` in `FamilyChatDestinations.kt` is the single
source of truth, and `NavigationDepthTest` guards it:

| Move | Motion |
| --- | --- |
| Equal depth (peers) | shared axis X, `it / 10`, `MotionShort`, crossfade |
| Increasing depth (drill-down) | `it / 4`, `MotionMedium` |
| Image viewer | scale + fade, `MotionMedium` |

A peer move must travel *less* than a drill-down, or the two sections read as
nested. Direction comes from `familyChatSectionIndex()` so a lateral slide
always travels the way the section bar is laid out.

**A new destination must be placed on the ladder.** Getting this wrong is
silent: Prerelease matched the broad `settings/` prefix, landed on the same rung
as the About screen it opens from, and animated as a sideways tab switch for
months without anyone filing it.

---

## 2. Components — `ui/components/FamilyChatKit.kt`

If a screen needs something not here, that is a conversation about adding it
here, not a reason to hand-roll one more variant.

### 2.1 Buttons — intent, not whim

Before this rule existed, `Button` appeared 79 times and `FilledTonalButton` 24,
with nothing distinguishing them.

| Component | Meaning |
|---|---|
| `FcPrimaryButton` | The one action this screen exists for. **At most one per screen.** |
| `FcTonalButton` | Secondary but common. Additive, not committal. |
| `FcTextButton` | Dismissive or navigational. **Never the confirming action.** |
| `FcDangerButton` | Destructive and irreversible. |

All 48dp tall and pill-shaped, so a row of mixed intents still aligns.

Where a flow has stages, show the action for the stage you are on rather than all
of them at once. About offers Install *or* Download *or* Check — never three
buttons of equal weight.

### 2.2 Rows and groups

- `FcListRow` — the one row. 56dp minimum, ellipsizes, optional leading/trailing.
- `FcListGroup` — the card rows sit in. **Owns its own dividers**, so callers
  cannot disagree about the inset.
- `FcRowDivider` — inset 52dp by default, `inset = false` when rows have no
  leading icon.
- `FcSectionLabel` — accent, sentence case. Labels what follows.
- `FcChoiceRow` — one option in a mutually exclusive set, checkmark when active.
- `FcSwitchRow` — whole row toggles.
- `FcIconTile` — tinted container behind a leading glyph. A bare icon reads as
  decoration; on a container it reads as the row's subject.
- `FcStatusPill` — state, not prose. Use instead of writing "Status: Trusted".
- `FcProfileBlock` — who this screen is about.
- `FcCard` — a block of free-form content on the page background: a blog post, a
  media block, anything composing its own interior. Same surface and radius as
  `FcListGroup`, so the two read as one family. Unlike the list group it does
  **not** pad its content, because cards routinely mix edge-to-edge children
  (a photo bleeding to the corners) with inset ones.

**Navigation is a row, not a button.** If it opens a screen, it belongs in a
group with a chevron. "Beta builds" was a full-width tonal button for a long
time; it was the clearest category error in the app.

### 2.3 Dialogs

`FcDialog` is the only chassis. There were three — `AlertDialog`, a custom
`AnimatedActionDialog`, and raw `Dialog` — meaning three sets of padding, radius,
button placement and animation across 32 dialogs.

**Every irreversible action confirms first.** This is not a style preference:
clearing history in group management, and clearing history *and deleting a
contact* in peer management, all fired on a single tap with no confirmation
until they were found and fixed.

Set `destructive = true` so the confirming action is styled as danger. "Delete
group" must not look identical to "Save".

### 2.4 Messages and errors

See §4.

---

## 3. Screen patterns

### 3.1 The shell

The app has **two top-level sections, Chats and Blog.** They are peers, and they
share one shell.

**The shell owns the section bar; a screen never does.** `FamilySectionBar` is
rendered once in `FamilyChatNavHost`, above the `NavHost`, and the NavHost is
inset for it with `padding(shellPadding).consumeWindowInsets(shellPadding)`.
Screens contribute content and their own header actions — nothing else.

This is a rule with a scar. When Chats and Blog each carried their own copy of
the bar in their own `Scaffold`, switching sections slid one bar out to the left
while a second slid in from the right: the control the user had just pressed
appeared to tear in half. Three discontinuities fired at once — a duplicated
travelling bar, a header changing colour mid-slide because Blog used a default
surface bar against Chats' accent one, and a drill-down animation used for a
lateral move.

An earlier version of this file said *"No bottom navigation — two destinations do
not justify permanent chrome."* That was written when the app had one section
and is superseded. **A third top-level section requires re-justifying the bar,
not adding to it**; three peers is the point where a bar stops being navigation
and starts being a menu.

Both sections use the **filled accent header**, at the same height and the same
title alignment. This is what makes the switch read as continuous: the green
band never changes, so only the content beneath it appears to move.

### 3.2 Media in a feed

**A post's height is decided by the post, not by the file inside it.** The media
block takes its geometry from how many attachments there are, never from the
source aspect ratio:

| Count | Layout | Height |
| --- | --- | --- |
| 1 | full width, ratio clamped 4:5 – 1.91:1 | ≤ 340dp |
| 2 | two equal tiles | 220dp |
| 3 | one large, two stacked | 220dp |
| 4+ | 2×2, `+N` on the last tile | 300dp |

A feed is scanned, so posts have to be comparable in size. Taking the media's
own ratio meant a single portrait phone photo produced a frame one and a half
times as tall as the card was wide — one post filled the screen and the feed
lost its rhythm. The 4:5 and 1.91:1 clamps are the portrait and landscape limits
most feeds use; they crop almost nothing in practice.

Only the **outer block** is rounded. Rounding tiles individually leaves gaps of
card colour inside a mosaic, which reads as scattered chips rather than one
piece of content.

**Media is always openable, and videos show their first frame.** A tile that
looks like content but does nothing when tapped is a dead end — and an
untextured grey tile in a mosaic reads as one that failed to load, not as a
video. Full-screen viewers scale up out of the tapped thing (`isFullScreenMedia`)
rather than sliding in, and while a photo is zoomed, paging is disabled so
dragging pans instead of discarding the photo.

### 3.3 Headers

**Filled header** (`AppTopBar(filled = true)`) — the app's front door and the
sign-in hero, meaning the top of *each* section. This is where the accent
carries weight.

**Plain header** (`CenteredAppTopBar`, or `AppTopBar` with a back arrow) —
everywhere below a section root. Content is the subject; chrome recedes.

**Group by consequence, not by category.** Sign out and Delete account are not
comparable: one keeps your history and is reversible, the other needs admin
approval and cannot be undone. Destructive actions get their own group under
their own heading, never adjacent to benign ones.

**Facts are rows; values go in the trailing slot.** "App version" with `v3.0.30`
on the right, not a sentence reading `Current version: v3.0.30`.

**Subtitles describe, they do not label.** "Change password" was once subtitled
"New password" — a form field label. "Request account deletion" was subtitled
with the confirmation dialog's body text.

**Keep engineering telemetry out of headline positions.** Group management once
stacked group code, admin limit, key epoch and key device readiness beside the
avatar. A family member does not need "Key epoch: 7" as the first thing they
read. Give it a labelled section further down.

**Show every option at once where you can.** Horizontally scrolling `FilterChip`
rows put choices off screen and signal the active one only by a fill. Language,
theme, text size and message expiry are all `FcChoiceRow` groups now.

---

## 4. Messages, errors and warnings

The rule: **the app speaks in sentences a family member can act on, in a
container, in the place the message belongs.**

Errors used to be bare `Text` in `colorScheme.error`, dropped inline into a
scrolling column. With no container they read as stray content, and a red
sentence floating between two cards looks like a rendering fault.

### 4.1 Does the user need to see it?

**Show it** when the person can do something, or when it confirms something they
just did, or when it concerns their security:

- Sign-in failures — wrong ID, cooldown, expired session
- No network connection
- Contact and group request outcomes
- Profile changes — username updated, avatar upload failed
- Registration pending approval
- Device approval needed, safety code changed *(always — this is security)*
- Update available, downloaded, or failed to install

**Do not show it** when the person cannot act and the app will recover:

- Background refresh failures — the app retries, and connection state is already
  in the top bar
- Exception types and class names. `error.javaClass.simpleName` used to reach the
  UI, so a family member could be shown `UnknownHostException`
- Push health internals, unless they ran the self-test themselves
- Transient upload retries that resolve on their own

Suppressed does not mean discarded — send it to `FamilyChatDiagnostics.event()`,
where it is actually useful. Current suppressions:

| Source | Behaviour |
|---|---|
| `ConversationManager.refresh` | Shown only when `showIndicator` is true, i.e. the user pulled to refresh. Background syncs log and stay silent. |
| `ManagementManager.reportError` | Never shows a throwable's class name; logs the type, shows a sentence. |
| `SettingsManager` push self-test | Result goes to `pushHealthStatus`, i.e. the row the user tapped, never a screen banner. |

Update checks are **not** suppressed: the user tapped "Check for updates" and is
waiting for an answer.

### 4.2 How to present it

| Where it belongs | Use |
|---|---|
| Concerns the whole screen | `FcMessageBanner` |
| Belongs to one row | that row's `subtitle` |
| Blocks progress until answered | `FcDialog` |
| Confirms a completed action, needs no response | `FcMessageBanner` with `CONFIRMATION`, auto-cleared |
| Transient, app-level, must not move the layout | `Toast` |

**`Toast` is a legitimate tier, but a narrow one.** It is correct only when *all*
of these hold:

- the notice is transient and needs no response
- nothing the user must act on depends on it, and it is never security-related
- either no screen surface owns it, or showing it inline would displace content
  the user is currently reading

The five current uses all qualify: "press back again to exit", the two
share-intent notices that fire before any screen exists, the overlay permission
hint, and the mute toggle confirmation in the chat thread — where a banner would
push the composer and the messages for a trivial toggle.

If a message fails any of those tests, it is a banner, a row subtitle or a
dialog. Do not reach for `Toast` because it is convenient.

`FcMessageBanner` takes a tone: `PROBLEM` uses `errorContainer`, `CONFIRMATION`
uses `primaryContainer`, `NOTICE` uses `surfaceContainerHigh`. All three get a
radius, an icon and padding — they are the app talking, not loose text.

**`NOTICE` is for standing context, not for an outcome** — telling someone what
just happened to their input ("the second video was left out"), or what a limit
is. These were previously dressed as confirmations, which claimed success for a
message that was not reporting anything having happened.

**A notice still has to earn its place.** The compose screen used to open with a
banner explaining that posts are visible only to trusted devices. It was true,
but it was permanent, unprompted and told the user something they already knew
about their own family's app — a banner that is always there stops being read,
and it teaches people to ignore the next one that matters. Reserve the tone for
notices tied to something the user just did.

**A dismissible banner needs somewhere to go.** Pass `onDismiss` whenever the
message can outlive its cause. The blog feed's error banner had no dismissal and
no auto-clear, so one failed refresh sat over the feed for the rest of the
session — including after the next refresh succeeded.

The update status rows in About are the row-subtitle case: the message belongs to
"Latest version", so it lives there rather than floating beneath the card.

### 4.3 Always humanise

**Every raw error passes through `friendlyErrorMessage(raw, language)` before
display.** It maps error codes and exception text to plain bilingual sentences —
`unable to resolve host` becomes "No network connection. Check your connection
and try again".

This function existed for a long time and was applied on the login screen only.
Every other screen showed whatever the exception happened to carry. If you add a
new error path, add its code to the mapper rather than passing the raw string
through.

---

## 5. Accessibility

- **Touch targets 48dp minimum**, even when the glyph is 24dp.
- **Label what carries meaning; leave decoration null.** An icon beside a text
  label that says the same thing is decorative. An icon that is the *only* carrier
  of state is not — the delivery ticks announced nothing at all until they were
  given descriptions.
- **Merge composite indicators.** The double tick is two icons and one meaning;
  it merges into a single semantics node so it announces "Delivered" once.
- **Never rely on colour alone.** Status gets a pill with a word in it.
- Meaningful text does not go below 11sp, and the user can scale everything.

---

## 6. Strings

**`AppStrings` must stay grouped.** Sections live in data classes —
`AppAccessibilityStrings`, `AppCallStrings`, `AppMaintenanceStrings`, and so on —
exposed through forwarding getters so screens still read `strings.someProperty`.

This is not stylistic. DEX method invocations cap at **255 argument registers**.
The flat constructor reached 259 parameters and Android threw
`VerifyError: Verifier rejected class com.example.chat.AppStringsKt` the moment
`stringsFor()` ran at startup — **the app would not launch**. It compiled, passed
every JVM unit test, passed lint and produced a valid APK, because nothing on the
desktop side goes through DEX. Only a device or emulator surfaces it, and
disabling R8 does not help.

`AppStringsDexSafetyTest` holds the ceiling at **240**. If it fails, group the
newest section — do not raise the ceiling.

**No inline `if (language == ZH) "..." else "..."` in screen files.** Translations
belong in `AppStrings`. Several lived in `SettingsAboutScreen` and in the device
list, and the device ones duplicated `strings.approve` and `strings.reject`,
which already existed.

---

## 7. Adding something new

1. Does a kit component already do this? Use it. If it *nearly* does, extend the
   kit — do not hand-roll a variant in the screen file. The blog needed a card
   and a button with a leading glyph; both became `FcCard` and a `leading` slot
   rather than seven hundred lines of parallel components.
2. Does it need a new colour, radius, duration or spacing? Add it to
   `ui/theme/`, never to the screen file.
3. New strings go in a **grouped** class.
4. Is any action irreversible? It confirms first, and its button is
   `FcDangerButton`. Check the whole feature, not the obvious case: deleting a
   blog *post* asked for confirmation while deleting a *comment* fired on the
   first tap.
5. Is there more than one primary button on the screen? Then one of them is not
   primary.
6. Does an icon carry meaning on its own? Give it a description.
7. Check contrast for the actual pairing, in **both** themes.
8. **Can every action on the screen fail, and does the screen say so?** Walk each
   one. Publishing a blog post set an error nobody rendered: the progress bar
   stopped, the draft stayed, and nothing was said.
9. **Is there a new destination?** Place it on the depth ladder in
   `familyChatNavigationDepth()` and add a case to `NavigationDepthTest`.
10. **Does it decode images or read files?** Off the main thread, and sampled.
    The blog feed decoded full-resolution photos synchronously inside
    composition, which janked scrolling and could exhaust memory.
11. **Does it take the user's language from state?** Not by comparing a
    translated string against a literal, which is what the blog's error banner
    did to decide whether to render Chinese.
12. Update this file.

---

## 8. What we deliberately do not do

- **No custom iconography.** Material icons are recognisable from every other
  Android app; a bespoke set costs recognition and buys nothing.
- **No bundled typeface.** Latin plus CJK coverage costs several MB on a 22MB
  APK, and the system faces render both correctly at these sizes.
- **No moving familiar controls.** The send button, swipe-to-reply and long-press
  stay where they are. The users are family members who do not read changelogs.
- **No redundant chrome.** The standalone Refresh button was removed because
  pull-to-refresh already exists and connection state is in the bar subtitle.
