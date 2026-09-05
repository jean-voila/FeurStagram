# Changelog

All notable changes to Feurstagram should be documented in this file.

## Unreleased

Nothing yet.

## v445-0-0-45-83

### Fixed
- **Ad and "suggested" filtering in the feed works again.** The patch that drops
  ad/promo units injected inline into the timeline stopped applying on Instagram
  444: its fingerprint required the `in_feed_survey` unit type, which Instagram
  removed from the feed-item parser. The patch failed silently and every inline
  ad/netego unit came back. The fingerprint now anchors on four load-bearing
  tokens instead of seven. Fixes #117.
- **Hidden navigation tabs can no longer be reached by swiping.** Hiding a tab
  only removed its icon; the page stayed in the swipe pager, so a flick still
  opened Search, Messages or Reels — and walked straight around the permanent
  lock. A swipe released toward a hidden tab is now re-aimed at the nearest
  visible one, mid-flight, so it stays a single continuous gesture rather than
  landing on the hidden page and cutting away. Always on: it is not a toggle,
  because a switch that turns it off would be a way around the permanent lock.
  Fixes #121 and #92.
- **The landing page can no longer point at a hidden surface.** Search stayed
  selectable as the cold-start page even when its tab was hidden and the
  permanent lock was on, which handed back the surface the lock was meant to
  remove. Options for hidden tabs are now greyed out, and an already-stored one
  falls back to the home feed. Fixes #116.

- **The updater no longer raises two install prompts.** A second Android
  "update this app?" dialog was queued behind the first and surfaced once the
  install had already finished, reporting a failure for an APK that was no
  longer there. Only one confirmation is ever raised now, a second update can't
  start while one is running, and any installer session left over from an
  earlier attempt is abandoned first.

### Added
- **Rebuilt update flow, as full-screen pages.** "Update available" shows the
  release's own notes (headings, bullets and all); choosing Update opens a
  progress page with a real progress bar, transferred size, percentage and a
  Cancel. Leaving that page does not stop the download — it carries on behind an
  ongoing notification with the same progress and the same Cancel. Neither
  screen is a popup any more.
- **What's new.** The first launch after an update shows a card with that
  version's release notes, pulled from its GitHub release. Shown once per
  version, and not gated on the update-check toggle — that toggle is about being
  nagged to update, not about being told what an update did.
- **Design guidelines** written down in `docs/DESIGN.md`, so the Material 3
  Expressive monochrome system is followed the next time the UI changes.
- **Debug builds** (`./build.sh … --debug`): the settings become readable and
  writable over ADB broadcasts, so on-device testing no longer needs the UI.
  Development only, never enabled in a release.

### Changed
- **Feurstagram's own screens are redesigned in Material 3 Expressive,
  monochrome.** No more purple: white is the only accent. Options are grouped
  into connected lists with large outer corners, buttons are pills, section
  labels are sentence case, and the headline is larger and tighter. Settings,
  the update pages, the confirmations and the first-run guide all share it.
- **Every Feurstagram screen now fits any device.** They pad themselves against
  the system bars and the display cutout, so the action buttons are no longer
  hidden behind a three-button navigation bar; the scrolling area shrinks to
  what is actually on screen instead of being cropped; and the content column is
  capped at a readable width on tablets, foldables and wide screens.

### Changed
- Updated to Instagram 445.0.0.45.83.
- `build.sh` now reports which patches applied (`build/patch-report.json`), so a
  fingerprint that stops matching is visible instead of silent, and it accepts
  the renamed `morphe-desktop-*.jar` CLI.
- The update download no longer goes through the system `DownloadManager`; it is
  fetched in-app so its progress can be shown, cancelled, and mirrored into a
  notification when the page is closed.

## v444-0-0-46-85

### Added
- **Force dark (disable HDR)** toggle (Settings → Display, on by default): stops
  Instagram forcing its window into HDR mode, which lifted the black floor and
  washed out the dark UI on HDR OLED screens. Turn it off to restore Instagram's
  HDR behaviour.

### Fixed
- **Deep links** into Instagram now open their target (shared posts, reels,
  profiles) instead of dropping to the home feed, via a new signature-check
  bypass that makes Instagram trust the re-signed build. Fixes #36 and #106.
  Thanks @na-ji.

### Changed
- Updated to Instagram 444.0.0.46.85.

## Previous releases

See GitHub Releases for APK downloads and version-specific changes.
