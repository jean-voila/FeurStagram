# Changelog

All notable changes to Feurstagram should be documented in this file.

## Unreleased

Nothing yet.

## v453-0-0-57-79

### Added
- **Quoicouview mode** (experimental, off by default, `quoicouview_mode`). Messages
  becomes the app's main page with the stories tray on top of the inbox and the
  notifications button in the inbox header, and Home is turned off (the Home and
  Messages toggles and the landing page are greyed out while it is on). The switch
  is the first row of the settings overview, under "Experimental feature"; it is
  unavailable when the permanent lock froze Messages hidden. A one-time card
  (`quoicouview_intro_shown`) presents it after the update, with a screenshot the
  *Quoicouview assets* patch adds to the APK's assets; it is skipped on a fresh
  install, where the settings guide comes first.
  The *Quoicouview mode* patch integrates it into Instagram's own code, with no
  overlay or padding:
  - every `IgRecyclerViewAdapter` registers one more row type, `StoriesTrayRow`
    (made a subclass of the obfuscated item definition and view holder at patch
    time), and the inbox's list updates get its model first. The row scrolls,
    recycles and diffs like the inbox's other rows;
  - the row shows the feed's own `reels_tray_container`, adopted when the row is
    bound. The feed page stays alive off screen, so the tray keeps its live
    content (new stories, seen rings, "Your story");
  - stories open as they do from Home: the viewer grows out of the tapped avatar,
    is stacked in the current tab, and shrinks back into it when Back or the
    swipe-down closes it. The feed tray controller's two "is the feed visible"
    checks also accept the tray in the inbox, and the controller, a lifecycle
    listener of the feed fragment that never resumes here, gets the inbox's
    resume and pause instead (that is where it plays the closing animation and
    refreshes the tray). The viewer is added over the inbox rather than replacing
    it (the navigator's other commit), so closing it does not rebuild the inbox
    first; the controller is told the page is covered, then uncovered, as the
    viewer comes and goes;
  - the feed header's notifications button (`ToastingBadge`, with its unread
    badge) moves to the inbox header's end buttons, whose width is refitted as
    `IgdsActionBar` sizes them. Its tap opens the notifications in the Messages
    tab. It stays hidden in Home's header when *Notifications button* is blocked.
- **Hide the Home icon** (#127). *Navigation bar* gains a Home toggle (shown by
  default, `nav_show_home`, frozen by the permanent lock like the other icons).
  Long-pressing a tab is the only way into the settings, so with Home hidden the
  gesture moves to the first icon left on the bar (`Hiders.settingsTab`), and the
  first-run guide points there too. Home only goes once another icon of the live
  bar is shown: hiding every icon keeps it. The swipe skipper already keeps swipes
  and Back off the hidden feed page; on cold start a "Home feed" landing page now
  opens the first visible tab instead (Create excluded), and the settings show it
  as "First icon on the bar".

### Changed
- **Back history skips a hidden Home.** The new *Hidden Home in Back history*
  patch makes the tab Back handling ("NavigationController.onBackPress") count the
  tab history without hidden-feed entries. With Home hidden (or in Quoicouview
  mode), Back from the first icon leaves the app instead of walking onto the
  hidden feed.
- **Debug builds.** `--debug` builds are now `android:debuggable`, and the debug
  bridge gains view-tree commands (`TREE`, `CLICK`, `TOUCH`, `FIELDS`) and `INTRO`
  to show the Quoicouview card.
- Updated to Instagram 453.0.0.57.79. The English and French compatibility guides,
  their modification dates and the sitemap now match this base version.

### Fixed
- **Launch cards fit short screens.** The cards sized by `Settings.boundToFrame`
  (What's new, follow prompt, settings guide, the new Quoicouview card) measured
  their overflow from the laid-out card, which is already capped at the screen,
  so the buttons could be squeezed instead of the shrinkable block. The card's
  natural height is now measured.
- **Sponsored-media filtering works on Instagram 453.** The media parser's
  `(parser, Z)` entry point reads and hashes each field name inline again (no
  separate codec, carousel children parsed by recursion), so neither previous
  fingerprint matched and the *Feed item filtering* patch failed to apply. A third
  fingerprint matches it by its two error-report strings, signature and inline
  `String.hashCode` dispatch; the 452 and older paths are kept.

## v452-0-0-48-64

### Added
- **Docker build environment.** Build with JDK 21 and the Android SDK in a
  container. The documented commands select Linux amd64 so Android's x86-64
  build tools also run on Apple Silicon with container emulation enabled.

### Changed
- **Auto-updater improvements.**
- **Material 3 Expressive switches.** Settings now use animated switches matching
  the rest of the interface, including their selected, pressed and disabled states.
- Updated to Instagram 452.0.0.48.64. The English and French compatibility guides
  and their modification dates now match this base version.
- **Settings open on an overview of sections.** The settings page used to be one long
  list. It now opens on an overview with one row per section (Blocked surfaces,
  Navigation bar, Feed, Landing page, Display and popups, Updates, Support), each with
  Instagram's own outline icon and a summary of its current state. A row opens that
  section's page in the same window, with a back arrow; Back returns to the overview.
  Permanent lock and Done stay pinned on every page. The Navigation bar page shows a
  live preview of the bar and greys out toggles for tabs the running Instagram build
  does not have. No setting, key or default changed.

### Fixed
- **Settings close with an animation.** Android Back now reverses the settings
  opening transition before closing the overview. Back from a section still
  returns to the overview.
- **Sponsored-media filtering works on Instagram 452.** The media parser now
  delegates to a codec with two field-dispatch paths instead of the old static
  dispatcher. Follow the wrapper's codec call by signature and hook field-name
  reads in both paths, including the direct parser used for carousel children.
  The previous parser hook remains available for older Instagram builds.
- **Suggested accounts no longer fill the story tray.** Instagram mixes suggested
  accounts into the home story tray as ordinary entries of the tray response (typed
  `suggested_user`), so blocking the suggestion endpoints did not catch them: on a test
  account, 12 of the 19 tray entries were suggestions. They are now dropped as the tray
  is parsed when *Suggested accounts* is blocked, and come back when it is turned off.

## v451-0-0-20-80

### Fixed
- **Sponsored posts are removed from the feed.** Instagram now sends its in-feed ads,
  "Sponsored" posts and "ad break" posts alike, inside the timeline response itself,
  wrapped in the same `media_or_ad` unit as a regular post, then injects them client-side
  between the posts. No ad URL to block and no ad unit type to drop, so the Ads toggle
  let them all through (about one post in two on a test account). The media parser now
  reports each post's JSON fields, and a post carrying an ad-only field (`injected`,
  `dr_ad_type`) is dropped before it reaches the feed. Regular posts, including paid
  partnerships, are untouched. Gated on the Ads toggle.
- **Network blocking applies again on Instagram 451.** Instagram 451 turned
  `TigonServiceLayer.startRequest` into a tracing wrapper around a new
  `startRequestInternal`, so the network hook no longer found the request URI and the patch
  failed to apply, which would have let the feed, stories, explore, reels, ads and
  telemetry back in. The hook now targets whichever `startRequest*` method reads the URI.

### Changed
- Updated to Instagram 451.0.0.20.80. The guides that name the Instagram version they were
  checked against now name 451.0.0.20.80.

## v450-0-0-50-77

### Changed
- **New official Instagram account.** Feurstagram moved to a new account,
  [@feurstagram_off](https://www.instagram.com/feurstagram_off/). The card shown after an
  install or update now introduces it as the new account, and *Follow* opens it. The card
  runs once per update, so users who already dismissed it for the old account see it again
  with this release.
- The README and the website (home page, French home page, press kits, Instagram without
  Reels pages and structured data) link to the new account.
- Updated to Instagram 450.0.0.50.77. The guides that name the Instagram version they were
  checked against now name 450.0.0.50.77.

## v446-0-0-49-77

### Added
- **Follow the official account.** The first launch after installing or updating
  FeurStagram shows a card inviting the user to follow
  [@feurstagram_official](https://www.instagram.com/feurstagram_official/), where every
  release is announced. *Follow* opens the profile inside the app, *Not now* closes it.
  "After an install or update" is read from the package's `lastUpdateTime`, not its
  versionName, so a re-release on the same Instagram base counts too; it is shown once per
  install or update, and the "What's new" card only runs once it has been dismissed.
- **Website: Instagram without Reels pages, guides and a press kit.** A landing page for
  "Instagram without Reels" comparing the ways to hide Reels, guides (removing Reels on
  Android, Instagram without the feed, DFInstagram alternative, Feurstagram vs ScrollGuard vs
  WallHabit, the EU's findings on Instagram's addictive design), a press kit, a French
  version of the main pages, structured data, `hreflang` pairs and a `sitemap.xml`.
- The website shows the total download count, loaded from GitHub when the page opens, and
  the official contact address, feurstagram@gmail.com.

### Fixed
- **Deep links keep working on Instagram 446.** Instagram 446 reshaped the family-app scope
  trust check that the signature-check bypass disables: the static
  `boolean(KeyHash, KeyHash, Z)` became an instance method taking the caller identity and
  reading both key hashes from it. The patch failed to apply, which would have sent shared
  posts, reels and profiles back to the home feed. It now matches either form, the new one by
  structure alone.
- The website's canonical and Open Graph URLs pointed at `/Feurstagram/`, which returns 404 on
  GitHub Pages; they now use `/FeurStagram/`.

### Changed
- **The settings page opens with an animation.** Long-pressing Home now brings the
  Feurstagram settings page in with a short rise-and-fade instead of it appearing at once.
- Updated to Instagram 446.0.0.49.77.
- The README's download badge shows the total number of downloads, and the README lists the
  official contact address.

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
