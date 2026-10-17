# Saved button

An optional bottom-bar button that opens the saved-posts screen. Instagram has no
Saved tab, so the button borrows the **Reels slot** (`clips_tab`), which is hidden by
default: it is shown, re-skinned with a bookmark drawn in code, and its taps are taken
over. Toggle: *Settings → Navigation bar → Saved* (`saved_button`, off by default).

Code: `SavedButton.java` (icon, touch/click/accessibility, deep link), the *Saved home
deep link* patch (`patches/.../ui/SavedHomePatch.kt`),
`Hiders.SavedTabHook` (re-applies it every layout pass), plus small guards in
`Hiders.VisibilityHider`, `HiddenTabSwipeSkipper` and `Settings`.

## Design notes

- **Not a `nav_show_*` key.** Such keys are lockable; one that starts off would be
  frozen off by the permanent lock. Saved reveals the user's own bookmarks, not a feed.
- **Reels icon toggle stands idle** while Saved is on (the slot is Saved's). The
  `nav_show_reels` hider skips `clips_tab`, otherwise the two listeners would flip
  its visibility on every pass.
- **The Reels page stays unreachable.** The touch listener swallows every event on the
  slot, and `HiddenTabSwipeSkipper` treats `clips_tab` as unreachable while Saved is
  on, so neither a tap nor a swipe can land on Reels — including under the lock.
- **Degrades to nothing.** If `clips_tab` is missing in a build, no button appears.
  If the deep link has no handler, the tap falls back to the Profile tab.

## Destination (451.0.0.53.80)

- `instagram://saved` is in the router table (`X.013D`, jadx name `X.C013D.A1O`) and
  resolves to `com.instagram.urlhandlers.saved.SavedUrlHandlerActivity`, with access
  scope **`SAME_APP`**. Public routes carry `PUBLIC`.
- `SavedUrlHandlerActivity.A2V` only pushes the Saved fragment for the current session.
- Its base, `MainSessionUrlHandlerActivity.onCreate`, reads a bundle holding
  `IgSessionManager.SESSION_TOKEN_KEY`, which only the router adds, and `finish()`es
  without it. So the handler cannot be launched by explicit component: the button has to
  go through the deep link.
- The router derives the caller from the incoming intent (`X.010T.A00` →
  `C010U.A00` → `C01lB.A01` → `C07oe.A00`). It reads Meta's caller-identity extra
  `_ci_`: a `PendingIntent` whose creator gives the package and signature, and whose tag
  is URL-safe unpadded Base64 of `{"t": ms, "r": elapsed ms}`, valid for a day. With no
  identity, a same-process launch counts as **PUBLIC** and the `SAME_APP` route is
  dropped silently (you land on the feed). `SavedButton.callerIdentity` attaches the
  app's own identity. It can only vouch for the package that creates it.
- **Verified at runtime** (451.0.0.53.80, `--clone --debug`). Tapping the button runs
  `UrlHandlerLauncherActivity` → `UrlHandlerActivity` → `SavedUrlHandlerActivity`, and
  the Saved screen opens with the account's saved posts. Without `_ci_`, the chain stops
  at `UrlHandlerActivity`. `adb shell am start -d instagram://saved` is dropped for the
  same reason.
- **Two Saved screens.** Profile → menu → Saved goes through a factory (`X.0iBM.A00`,
  `Fragment (UserSession, tab)`) that returns the tabbed Saved home (`X.0JSB`, module
  `saved_tabbed_home`: pills, "Reels and posts", *Manage*) when a server-side gate allows
  it, and the older collections-only screen (`X.0JU4`) otherwise. `SavedUrlHandlerActivity`
  skips the factory and always builds `X.0JU4`, so the link opened the older screen, whose
  "All posts" cover did not even load. The *Saved home deep link* patch
  (`SavedHomePatch.kt`) swaps that call for the factory, so the link follows the same gate
  as the menu. It finds the factory structurally (static, returns `Fragment`, first
  parameter `UserSession`, calls into the class holding `saved_tabbed_home`) and replaces
  the one static call the handler and the factory share. Verified on 451.0.0.53.80: the
  button opens the tabbed screen; pills, opening a post and back all work. The screen
  shows no ← in its header, because it is the root of the handler's own activity; the
  system back gesture closes it.
- To try another route without rebuilding (`--debug` build only):
  ```sh
  adb shell am broadcast -p <package> -a com.feurstagram.debug.SET \
      --es key saved_uri --es value "instagram://<candidate>"
  ```

## Slot structure (uiautomator, 451.0.0.53.80)

```
LinearLayout tab_bar
  FrameLayout feed_tab     desc="Inicio"
  FrameLayout clips_tab    desc="Reels"     -> ImageView tab_icon
  FrameLayout direct_tab   desc="Mensaje"   -> ImageView tab_icon, TextView notification
  FrameLayout search_tab   desc="Buscar y explorar"
  FrameLayout profile_tab  -> ViewGroup wrapper -> ImageView tab_avatar, View led_badge
```
`SavedButton` takes the `tab_icon` child, and falls back to the largest `ImageView` if
the id is renamed.

## Test checklist

- [ ] `./build.sh instagram.apk --clone --install`: clone opens *its own* Saved screen.
- [ ] Toggle on → Done → bookmark icon in the Reels slot; tap opens Saved.
- [ ] Toggle off → Done → slot follows the Reels icon toggle again.
- [ ] Reels icon row greys out while Saved is on, and back when off.
- [ ] Survives a cold restart and a return from background (listeners re-applied).
- [ ] Permanent lock on with Reels hidden: Saved can still be toggled; neither tap nor
      swipe ever lands on Reels.
- [ ] Landing page (search/direct/profile) still redirects; Saved is not a landing option.
- [ ] Swiping across the bar skips the Saved slot.
- [ ] TalkBack announces "Saved, button"; double-tap opens Saved.
- [ ] Rotation; Instagram light and dark themes (icon colour follows the other icons).
