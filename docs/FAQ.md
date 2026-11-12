# Frequently Asked Questions

## What is Feurstagram?

Feurstagram is an open-source Android patch for Instagram that removes distracting features such as feed, Reels, Explore and ads while keeping useful features like DMs, stories, profiles, search and notifications.

## Is Feurstagram affiliated with Instagram or Meta?

No. Feurstagram is an unofficial community project and is not affiliated with Instagram or Meta.

## Does Feurstagram collect my password?

No. Feurstagram does not collect, store, proxy, or transmit your credentials.

Instagram login still happens through the Instagram app itself.

## Can I build it myself?

Yes. Users are encouraged to build the APK themselves using the documented patching process.

## Why does Android warn me before installing the APK?

Feurstagram is distributed outside the Google Play Store, so Android may show warnings before installation. This is expected for APKs installed manually.

Only install APKs from the official Feurstagram GitHub releases, or build the APK yourself.

## Classic APK or Clone APK?

The classic APK replaces the regular Instagram app because it keeps Instagram's original package ID.

The clone APK is designed to install alongside the regular Instagram app by using a different package ID.

Use the clone APK if you want to keep the official Instagram app installed at the same time.

## Are blocked contents still downloaded?

Instagram still sends its normal API requests. Feurstagram filters successful content
responses after reception so the selected lists stay hidden. The requests and
Instagram's native telemetry are no longer blocked; this can use more data than older
builds. The app does not generate fake views or interactions.

## Can my Instagram account be banned?

Feurstagram is unofficial and modifies the Instagram Android app. This may violate Instagram's terms of service. Use it at your own risk.

## Why not use a WebView wrapper?

Feurstagram patches the real Android Instagram app instead of wrapping the website in a WebView. This allows it to keep the native app experience while removing specific distracting features.

## Why not use an accessibility-service blocker?

Accessibility blockers usually react to the interface after it appears. Feurstagram aims to remove or block distracting parts more directly through patches.

## Does Feurstagram remove all distractions?

The goal is to remove the most addictive parts while keeping useful social features. Some features may change or break when Instagram updates its app.
