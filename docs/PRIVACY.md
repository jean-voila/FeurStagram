# Privacy

Feurstagram is designed to remove distracting and intrusive parts of Instagram.

## Data collection

Feurstagram itself does not collect analytics, credentials, personal data, or usage data.

The GitHub Pages site does not load third-party analytics scripts.

## Credentials

Feurstagram does not collect, store, proxy, or transmit Instagram usernames, passwords, or session tokens.

Authentication is handled by Instagram inside the app.

## Network behavior

Instagram's native requests remain enabled, including feed and discovery loads,
logging, seen events, statistics, uploads and commerce. The content switches filter
successful delivery responses locally, after reception, so blocked lists do not reach
the interface. HTTP errors and security notices retain Instagram's handling.

**Instagram telemetry is no longer blocked.** Hidden content still consumes API data;
CDN assets for removed media are not fetched just to simulate viewing them. No
synthetic views, interactions or periodic traffic are generated. The optional
Following-only feed still changes the pagination header when the feed is visible.

Feurstagram's own code does not collect these data or route requests through a proxy.
See the README for the selected response paths and filtering behavior. This behavior
does not guarantee that Instagram will accept a modified client or prevent account
restrictions.

## Third parties

Feurstagram is not affiliated with Instagram, Meta, Facebook, or any third party.

## Recommendation

If you do not want to trust a prebuilt APK, build Feurstagram yourself from source.
