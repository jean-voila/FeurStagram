# Feurstagram design guidelines

Everything Feurstagram draws — the settings page, the update pages, the confirmation
cards, the first-run guide — follows **Material 3 Expressive**, in a **monochrome dark
scheme with white as the only accent**.

Two hard rules:

1. **No hue.** No purple, no brand yellow, no red. If something needs emphasis it gets
   white, more weight, or more space — not a colour.
2. **No bundled resources.** Feurstagram is merged into Instagram's APK by a patcher; it
   has no `res/` of its own. Every drawable is a `GradientDrawable` built at runtime, every
   layout is written in code. This is why the tokens below are Java constants rather than
   a theme.

The whole system lives in **`Settings.java`** — palette, type, shape, buttons, the page
scaffold and the window plumbing. `UpdateChecker` and `Onboarding` draw from it and add
nothing of their own. **If you need a new colour, size or shape, add it there** rather
than inventing one locally; that is what keeps the surfaces consistent.

---

## Colour

| Token | Value | Used for |
|---|---|---|
| `SURFACE` | `#0E0E0E` | Page background |
| `SURFACE_CONTAINER` | `#1B1B1B` | Cards, list rows, the notes panel |
| `SURFACE_CONTAINER_HIGH` | `#262626` | Tonal buttons, a card raised over a page |
| `ON_SURFACE` | `#F4F4F4` | Primary text, icons |
| `ON_SURFACE_VARIANT` | `#ABABAB` | Supporting text, section labels |
| `OUTLINE` | `#757575` | Unchecked control marks (switch thumb, radio) |
| `OUTLINE_VARIANT` | `#3A3A3A` | Hairline borders, unchecked switch tracks |
| `PRIMARY` | `#FFFFFF` | The accent: filled buttons, checked controls, progress |
| `ON_PRIMARY` | `#101010` | Text on a filled accent |
| `ERROR` / `ON_ERROR` | same as `PRIMARY` / `ON_PRIMARY` | See below |
| `RIPPLE` | `#26FFFFFF` | Every pressed state |

**Destructive actions are not red.** A monochrome scheme has no red to lean on, so they
carry the ordinary accent and are made clear by their *wording* — "Enable lock", not
"Enable" — and by a confirmation card that spells out the consequence.

Emoji in user-visible strings (release notes, the coffee button) keep their own colours.
That is fine: they are content, not chrome.

---

## Shape

M3 Expressive leans on shape rather than colour, so the corner scale carries a lot here.

| Constant | Value | Used for |
|---|---|---|
| `CORNER_XL` | 28dp | Cards, dialogs |
| `CORNER_L` | 24dp | The **outer** corners of a connected list group |
| `CORNER_JOINT` | 4dp | The corners **between** rows of a group |
| `CORNER_FULL` | 200dp | Pills — `GradientDrawable` clamps to half the height |
| `ROW_GAP_DP` | 3dp | Gap between rows of a group |

### Connected lists

This is the signature pattern. Options are not a single card with dividers — they are
individual surfaces with round outer corners and nearly square joints:

```
╭───────────────╮   24dp
│  Home feed    │
╰───────────────╯   4dp
   3dp gap
╭───────────────╮   4dp
│  Explore      │
╰───────────────╯   4dp
   3dp gap
╭───────────────╮   4dp
│  Ads          │
╰───────────────╯   24dp
```

Build the rows, then call `sealGroup(context, group)` — it applies the per-corner
backgrounds and the gaps from the child count. Never hand-roll the radii.

**Do not use dividers between rows.** The gap is the divider.

---

## Type

Roboto (`sans-serif`) only; there is no font asset to bundle. Weight and tracking do the
expressive work.

| Helper | Size | Weight | Tracking | Used for |
|---|---|---|---|---|
| `headline(v)` | 30sp | medium | −0.02 | The title of a full-screen page |
| `titleLarge(v)` | 22sp | medium | −0.01 | Card titles, section headings in notes |
| `body(v)` | 14sp | regular | 0 | Supporting text, at 1.15 line spacing |
| section label | 13sp | medium | +0.01 | `ON_SURFACE_VARIANT`, above a group |
| row label | 16sp | regular | 0 | `ON_SURFACE` |
| row support | 13sp | regular | 0 | `ON_SURFACE_VARIANT` |
| button label | 15sp | medium | 0 | Never all-caps |

**Section labels are sentence case** — "Blocked surfaces", not "BLOCKED SURFACES". All-caps
labels are the old Material; M3 dropped them.

Copy is sentence case throughout, including buttons ("Check for updates", "Cancel
download"). Prefer a verb that says what will happen over a generic "OK".

---

## Components

### Buttons

`Settings.makeButton(context, text, bg, fg, filled)` — always a pill, 52dp minimum height,
20dp horizontal padding, no elevation (`setStateListAnimator(null)`).

Three levels, and only three:

- **Filled accent** — `PRIMARY` / `ON_PRIMARY`. The one action a screen is asking for.
  One per screen.
- **Tonal** — `SURFACE_CONTAINER_HIGH` / `ON_SURFACE`. Secondary actions.
- **Outlined** — `Settings.outlined(context, OUTLINE_VARIANT)`. Dismissals, cancels.

**Action rows are equal-width pills, not end-aligned buttons.** Add them with
`Page.addAction(...)`, which gives each `weight = 1` and an 8dp gap. This is a layout rule
as much as a style one: end-aligned buttons overflow on a narrow screen, weighted ones
cannot.

### List rows

64dp minimum, 20dp horizontal / 16dp vertical padding, label + optional supporting line on
the left, control on the right. **The whole row is the touch target**, not just the
control.

### Switches

`android.widget.Switch`, tinted. One trap: **the platform track drawable is
alpha-blended**, so tinting the track white produces the grey M3 track — the *thumb* is
what carries the accent.

```java
toggle.setTrackTintList(buildStateList(PRIMARY, OUTLINE_VARIANT));  // checked, unchecked
toggle.setThumbTintList(buildStateList(PRIMARY, OUTLINE));
```

Tinting the thumb dark instead makes the "on" state look like a hole punched in the track.

### Single choice

Not a `RadioGroup` with leading buttons: build ordinary rows with a non-clickable
`RadioButton` as the **trailing** control, so choices line up with switches. Manage the
checked state yourself.

### Disabled state

`setAlpha(0.38f)` on the whole row plus `setEnabled(false)` on the control. Never grey the
text by swapping its colour.

---

## Layout: pages, cards, and fitting the screen

### Pages vs cards

- **A task gets a page.** Settings, "Update available", the download progress. Use
  `Settings.newPage(context)` + `Settings.newPageDialog(...)`. Full screen, never a popup.
- **A question or a notice gets a card.** Restart? Enable the permanent lock? What's new?
  Use `Settings.cardFrame(context)` + `Settings.addCard(...)`, centred, dimmed behind.

The page scaffold is always: headline block → scrolling middle that absorbs the leftover
space → action row pinned at the bottom.

### The screen is not a fixed size — this part is not optional

Instagram targets a recent SDK, so its windows are **edge-to-edge**: system bars and the
display cutout overlap our dialog and nothing is inset for us. Left alone, the action
buttons end up under a three-button navigation bar.

`Settings.applyPageInsets(view, sideDp, verticalDp, maxWidthDp)` handles all of it: it pads
for `systemBars() | displayCutout()` and caps the content column (620dp for pages, 520dp
for cards) so rows don't stretch edge to edge on a tablet or an unfolded foldable. Every
root goes through it.

Two traps worth knowing before you write a layout that measures itself:

1. **`requestApplyInsets()` is asynchronous.** The first layout pass runs with *zero*
   insets. Anything that sizes itself from the available space must re-check on a
   `ViewTreeObserver.OnGlobalLayoutListener` — `addOnLayoutChangeListener` on a
   match-parent view never fires again, because its own bounds never change.
2. **A full-screen `Dialog` needs `window.setDecorFitsSystemWindows(false)`** or it may
   receive no insets at all. `Settings.styleWindow(...)` does this, plus clears
   `APPEARANCE_LIGHT_STATUS_BARS` / `..._NAVIGATION_BARS` so the bar icons stay light over
   our dark surface.

**Nothing has a fixed height.** Content that can grow — a settings list, a release-notes
block — goes in a scroller, and `Settings.boundToFrame(frame, card, growable)` shrinks the
nominated child when a card would otherwise be taller than the space available. That is
what keeps the buttons on screen on a short device.

### Checklist before shipping a screen

- [ ] Action buttons visible above a **three-button** navigation bar, not just the gesture
      pill (`adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`,
      restore with `…navbar.gestural`).
- [ ] Nothing under the status bar or a cutout.
- [ ] Content that can be long actually scrolls, and shrinks rather than pushing the
      actions off screen (`adb shell wm size 2340x1080`, restore with `wm size reset` —
      Instagram is portrait-locked, so this stands in for landscape).
- [ ] No fixed heights, no fixed widths, no end-aligned action rows.
- [ ] Bar icons still light; the surface behind them is ours and dark.

---

## What not to do

- Don't introduce a colour. If you think you need one, you need weight, size or space.
- Don't use dividers inside a group — use the 3dp gap.
- Don't use ALL-CAPS labels anywhere.
- Don't put more than one filled accent button on a screen.
- Don't compute a height from `getDisplayMetrics().heightPixels`; it ignores insets,
  multi-window and foldables. Measure against the parent instead.
- Don't add a resource file. There is nowhere for it to go.
