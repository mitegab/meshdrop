# MeshDrop — the quiet drop

A design document for the interface in `app/src/main/kotlin/com/meshdrop/ui`.

The prototype that goes with this document is [`prototype.html`](prototype.html): open it in a
browser, tap through the states, press `T` to switch between paper and ink, `→`/`←` to step
through the six states. It is one file, with no build step, and it implements the same tokens,
type scale and motion rules as the Compose code — so what you see there is what the app does.
It loads three OFL variable faces from [`fonts/`](fonts/), so it renders identically with no
network. Each state has a notes panel on the right explaining the decisions behind it.

---

## 1. The problem with the old interface

The first version of MeshDrop was designed to look like the thing it is *underneath*: a mesh
of transports. Deep midnight surfaces, a teal accent, a radar that pulsed while scanning, an
orbit of dots beside the transfer, a shimmering chunk "assembly" bar, a marching dashed route
line, and lane swatches coloured like a network diagram.

Every one of those decisions was defensible on its own and wrong together, for three reasons:

1. **It answered questions nobody asked.** No one opens this app to learn about lanes. They
   open it because they are standing next to a friend with a photo album.
2. **Motion stopped meaning anything.** When five things move, movement is decoration. The one
   thing that needed motion — *are my bytes arriving?* — was competing with four animations
   that meant nothing at all.
3. **It asked a question before the user could know the answer.** Home had *Send* and
   *Receive* as two giant, equally weighted doors. A drop is not a role you pick; it is a thing
   that happens, and until somebody taps, both people are just… nearby.

The redesign starts from that last observation, because it is the structural one.

---

## 2. The idea

> **A drop is one act, seen from two sides.**

So there is one surface — **Drop** — and it is symmetric. Files in the tray, phones in the
list, one button that says `Send to Ana's Pixel`. If somebody sends *you* something, the same
surface grows an offer sheet. There is no mode, and the app never asks you to declare one.

Everything else follows:

| Principle | What it means in pixels |
| --- | --- |
| **One act, one surface** | Send and Receive are not destinations. Picking files, deciding on an offer, and inspecting the connection are sheets over Drop, not screens behind it. |
| **Chrome is a hairline, never a shadow** | Elevation is a lie on a flat screen. Separators are 1dp of `hairline`; grouping comes from type and space. |
| **One accent, one action** | Clay is the only saturated colour, and only one filled button appears per screen. A glance always finds the next tap. |
| **Motion answers "is anything happening?" and nothing else** | Exactly one loop per screen: a breathing dot while waiting, or a glowing head on the progress rule while bytes land. No radar. No orbit. No shimmer. |
| **Measurement is not prose** | Every number — speed, ETA, bytes, ports, fingerprints — is monospace and tabular. Numbers stop jittering while they tick, and they read as readings. |
| **Never colour alone** | Every colour that carries meaning also carries a word and a glyph. Green plus "Verified" plus a sentence; never a green padlock by itself. |
| **State is shown, not guessed** | "Listening", "Paused", "stalled", "about 12s left", "waiting for them to accept". The app says what it knows, including when it does not know. |
| **Progressive disclosure with real thresholds** | Default = who, what, how long. One tap = cipher, lanes, retransmits, throughput graph. |
| **Quiet by default** | Paper first, ink for after dark, no dynamic colour, no gradients, no illustrations of wifi waves. |

---

## 3. Design system

### 3.1 Colour

Warm neutrals (always `R > G > B`) and exactly one accent. Contrast: ink 15:1, muted 5.2:1,
clay-on-paper 5.3:1 — all above WCAG AA for body text.

| Token | Paper (light) | Ink (dark) | Used for |
| --- | --- | --- | --- |
| `canvas` | `#FAF9F5` | `#1B1A18` | The page |
| `surface` | `#FFFFFF` | `#232220` | Cards, sheets |
| `subtle` | `#F1EFE7` | `#2C2B28` | Chips, fields, bar tracks |
| `ink` | `#1A1915` | `#F3F1EA` | Primary text |
| `inkMuted` | `#6E6A61` | `#A8A49A` | Captions, labels |
| `hairline` | `#E5E1D6` | `#38362F` | Every separator |
| `clay` | `#B04A2A` | `#E08262` | The one accent: primary button, progress fill |
| `ember` | `#D97757` | `#E9A184` | Large glyphs, rules, the head of a progress bar |
| `clayWash` | `#F6E9E2` | `#35251F` | Selected filters |
| `success` | `#3D7A4E` | `#7FBF95` | Delivered, verified, intact |
| `warning` | `#8A5A12` | `#E0B15C` | Unverified, closing soon, paused |
| `danger` | `#A83A2B` | `#E08A80` | Failed, declined, **not encrypted** |

Material You is off. Dynamic colour repaints the app in the user's wallpaper, which is charming
for a notes app and wrong here: clay means "these bytes, this person", and a green wallpaper
must not turn the progress bar green.

### 3.2 Type

Three voices, three jobs. One short scale — decisions are "how loud is this?", never "which
style was this screen using?".

| Role | Family | Size / line | Where |
| --- | --- | --- | --- |
| `displayLarge` | Serif | 34 / 40 | (reserved) |
| `displayMedium` | Serif | 28 / 34 | Page titles: Drop, Activity, Settings |
| `displaySmall` | Serif | 24 / 30 | The receipt headline, the offer sentence |
| `headlineMedium` | Serif | 21 / 27 | The name of the person you are sending to |
| `headlineSmall` | Serif | 18 / 24 | Section titles over a list |
| `titleMedium` | Sans Medium | 15 / 21 | Rows, peer names |
| `bodyLarge` | Sans | 15.5 / 23 | Field text |
| `bodyMedium` | Sans | 14 / 20 | Explanations |
| `bodySmall` | Sans | 12.5 / 18 | Captions, meta lines |
| `labelLarge` | Sans Medium | 14 / 20 | Button labels |
| `Eyebrow` | Sans Medium | 10.5 / 14, `1.4` tracking, uppercase | `SENDING`, `PRIVACY AND TRUST` |
| `Reading` | Mono | 13 / 18 | Speeds, sizes, ETAs, ports, fingerprints |
| `HeroReading` | Mono Medium | 30 / 34 | The one number during a transfer |
| `SafetyCode` | Mono Medium | 26 / 32, `5` tracking | Six digits read out loud, across a room |

The serif is the platform serif (`FontFamily.Serif`, i.e. Noto Serif) and the sans is the
platform UI face: zero APK cost, no licensed font, no download. A product font can be
dropped in by editing one file (`ui/theme/Type.kt`).

### 3.3 Space, shape, motion

* **Space**: 2 / 4 / 8 / 12 / 16 / 24 / 32, page gutter 20, block gap 28. These are the only
  gaps the app is allowed to use, which is what makes screens feel related.
* **Shape**: 6 / 10 / 14 / 18 / 26. Controls 10, cards 14, sheets 18, the offer sheet 26.
  Nothing is a pill except chips and dots.
* **Motion**: 110–180ms for feedback, 260ms for a sheet, 300ms for a progress step. One
  infinite animation per screen, slow (2.4s) and shallow (±35% alpha). Every animated element
  has a static text equivalent, so the app is fully usable with animations off.

---

## 4. The screens

### 4.1 Drop

Reading order — always the same, so the screen is familiar before it is read:

1. **Header.** Wordmark, page title, "You are *Kavi's Pixel* on this network", and two quiet
   doors: Activity, Settings.
2. **Status rail.** One honest line: `● Listening · Visible as Kavi's Pixel · 3 nearby`. It
   breathes only while actually scanning, turns amber and says `Paused` when the user pauses,
   and opens the connection sheet on tap.
3. **What is happening now.** A live transfer card, or a receipt, or nothing.
4. **The tray.** A dashed drop target with a single clay button. The empty state is the first
   thing a new user sees, so it explains itself: *"Files stay on this phone until the other end
   accepts them."* With files in it: real rows, real thumbnails, real sizes, a count line, and
   `Send to Ana's Pixel` — or `Pick a phone first`, disabled, with the reason in the caption.
5. **Nearby.** Rows: tinted initials, name, `Wi-Fi · 12 ms · tap to send`. Tapping a phone with
   an empty tray opens the picker (the only question left); tapping one with files already in
   the tray sends immediately (no question left).
6. **Recent.** Three rows, then `See all`.

### 4.2 Live transfer

The card is the most important thing in the product, so it gets the biggest number:

```
SENDING                                        [ Wi-Fi ]
Ana's Pixel
3 photos · 12.6 MB
64%                                        about 12s left
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░░░░░░░░░░░░░
8.1 MB of 12.6 MB · 3.4 MB/s
──────────────────────────────────────────────────────
Details                                            Cancel
```

* The head of the bar glows while bytes land and goes still when they do not — a stalled
  transfer is visible without reading a word.
* The ETA hedges to "about" when the link is unsteady (`SmoothedReading.isSteady`), because a
  precise lie is worse than an honest approximation.
* `Details` opens real numbers: throughput, steadiness, lane (including lanes that were given
  up), peers, cipher, retransmits, batch id, and the last few seconds as a bare line.

### 4.3 The offer

An incoming transfer is a letter, not an alert: the countdown is a depleting rule at the top,
the sentence names the person and what they are sending, the files are listed, the trust line
is one row (`Check code` expands to six digits and a "They match" button), and the decision is
two buttons. Once the window closes both are disabled and the primary one says `Too late` —
accepting would hang the sender's socket and look like a freeze.

### 4.4 Receipts

A finished transfer gets a proper ending:

```
SENT
Sent to Ana's Pixel
3 photos · 12.6 MB in 4.1s · every file delivered
🖼 IMG_2043.HEIC              4.2 MB
🖼 IMG_2044.HEIC              4.1 MB
🎬 Clip.mov                   4.3 MB
🔒 Encrypted end to end, and every chunk arrived intact.
                                                  Done
```

`Sent` means delivered and verified, and the line says so in words. Failures name what
happened and offer `Try again` only when there is something to try.

### 4.5 Activity

History became Activity because History was a list you could only operate by swiping blind.

* Search over file names, device names and batch ids.
* Filters as pills that fill when active — `Received`, `Sent`, `Problems`, `Verified` — and a
  sort menu (`Newest`, `Oldest`, `Largest`, `Fastest`, `Device`).
* Day groupings (`Today`, `Yesterday`, `12 September 2026`).
* Each row: direction, peer, `3 photos · 12.6 MB · 2 min ago`, a trust mark inline when it is
  verified or not encrypted, and a status glyph.
* Tapping a row opens a detail sheet: every file, provenance (security, transport, batch id),
  and the actions in words — Open, Share, Move to vault, Delete (which asks first).

### 4.6 Settings

Privacy and trust first, then this phone, then the devices you have chosen to trust, then the
network plumbing folded away, then about. Switches state consequences, not feature names:
*"Off. Anything you send can be read by anyone on this Wi-Fi network."*

---

## 5. Voice

* Second person, present tense, plain words. `Send to Ana's Pixel`, not `OK`.
* No security vocabulary in the default path: encryption is "scrambled", verification is
  "compare the numbers", the vault is "the vault". Ciphers and fingerprints live in Settings
  and in the `Details` disclosure.
* Empty states explain how to fill themselves, in one sentence, without blaming the user.
* The app is allowed to say "about", "starting", "stalled", "Too late".
* Sentence case everywhere. No exclamation marks. No "Oops!".

---

## 6. What was deliberately removed

| Removed | Why |
| --- | --- |
| Radar pulse, orbit dots, shimmering chunk bar, marching route line | Five animations meant none of them signalled anything. The progress rule's head glow is the only liveness cue a transfer needs. |
| Per-lane colours (sky/teal/amber) | They turned the transfer card into a network diagram and put four accents on a screen that has one. Lanes are now a glyph and a word; the colour is reserved for bytes moving. |
| Dark-first palette | Most transfers happen in daylight, in a kitchen, between two people looking at the same screen. Paper is the default; ink is a designed second theme, not an inversion. |
| Material You | See §3.1. |
| Bottom navigation | A permanent tab bar claims several equally important places. This app has one screen and two reference pages opened on purpose. |
| Tinted status panels (`Verified · encrypted` as a badge) | A badge tells you a status exists and leaves the meaning to you. One line of English explains what happens to your files, and tapping it gives the rest. |
| Swipe-only actions | Actions you have to know about in advance are not actions. The detail sheet shows them; there is no longer a hidden gesture anywhere in the app. |
| The `Send` / `Receive` split | The structural fix. See §2. |

---

## 7. Where things live

```
ui/theme/       Palette.kt (tokens)   Type.kt (3 voices)   Theme.kt (+ Space, shapes)
ui/components/  Foundations.kt (buttons, cards, fields, rows)
                Motion.kt (BreathingDot, ProgressLine, ThroughputLine)
                Trust.kt (the trust line and its glyphs)
                LiveTransfer.kt (live card, waiting card, receipt)
                FileThumb.kt, TransportVisuals.kt
ui/drop/        DropScreen.kt (the surface)  DropViewModel.kt (send + receive)
                Sheets.kt (pick, offer, connection, rename)
ui/activity/    ActivityScreen.kt  ActivityViewModel.kt
ui/history/     HistoryFilter.kt  — pure, unit-tested search/filter/grouping
ui/settings/    SettingsScreen.kt  SettingsViewModel.kt
```

`drop/DropScreen.kt` is the only place a screen composes sections; everything below it is
presentational. `MeshTheme.colors` is the only way to read a colour, `MeshTheme` in
`ui/theme/Theme.kt` is the only theme entry point, and no screen ever builds a hex value.
