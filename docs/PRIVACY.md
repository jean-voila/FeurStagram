# Privacy

Feurstagram is designed to remove distracting and intrusive parts of Instagram.

## Data collection

Feurstagram itself does not collect analytics, credentials, personal data, or usage data.

The GitHub Pages site does not load third-party analytics scripts.

## Credentials

Feurstagram does not collect, store, proxy, or transmit Instagram usernames, passwords, or session tokens.

Authentication is handled by Instagram inside the app.

## Network behavior

Content requests (feed, discovery, Reels, stories) keep Instagram's native
transport. The content switches filter successful delivery responses locally, after
reception, so blocked lists do not reach the interface. HTTP errors and security
notices retain Instagram's handling.

**Telemetry is blocked by default.** With *Block telemetry* on (Settings → Privacy),
the requests that upload Instagram's usage analytics are cancelled before they leave
the phone: client event batches (`/pigeon_nest`, `/logging_client_events`), the
experiment-exposure log, activity-feed impression logs and Meta's ad-measurement
pixel in the in-app browser. Sign-in, two-factor, integrity and security checks,
configuration, uploads and messages are never cancelled. Read receipts, the typing
indicator and story views are separate ghost-mode options. The switch can be turned
off at any time, including under the permanent lock, which restores Instagram's own
telemetry.

Hidden content still consumes API data; CDN assets for removed media are not fetched
just to simulate viewing them. No synthetic views, interactions or periodic traffic
are generated. The optional Following-only feed still changes the pagination header
when the feed is visible.

Feurstagram's own code does not collect these data or route requests through a proxy.
See the README for the selected paths and filtering behavior. This behavior does not
guarantee that Instagram will accept a modified client or prevent account
restrictions.

## Third parties

Feurstagram is not affiliated with Instagram, Meta, Facebook, or any third party.

## Recommendation

If you do not want to trust a prebuilt APK, build Feurstagram yourself from source.
