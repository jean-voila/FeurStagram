<p align="center">
  <img src="docs/app_icon.png" alt="FeurStagram Icon" width="128">
</p>

<h1 align="center">FeurStagram</h1>
<p align="center">Distraction-Free Instagram</p>

<p align="center">
  <a href="https://github.com/jean-voila/FeurStagram/releases/latest">
    <img src="https://img.shields.io/github/v/release/jean-voila/FeurStagram?style=for-the-badge&label=Download%20APK&color=10a37f" alt="Download APK">
  </a>
  <br>
  <a href="https://discord.gg/Z9QvMw8s76">
    <img src="https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Join Discord">
  </a>
  <br>
  <a href="https://www.instagram.com/feurstagram_official/">
    <img src="https://img.shields.io/badge/Instagram-Official%20Account-E4405F?style=for-the-badge&logo=instagram&logoColor=white" alt="Official Instagram">
  </a>
</p>

<p align="center">
  <img src="https://komarev.com/ghpvc/?username=jean-voila-feurstagram&label=Views&color=gray&style=flat" alt="Views">
</p>

---


<p align="center">
  <img src="docs/screens.png" alt="FeurStagram screenshots" width="600" />
</p>

<p align="center">
  <img src="docs/tuto.gif" alt="FeurStagram tutorial" width="300" />
</p>

An open source Instagram app for Android without distractions.

I built this project for myself as an alternative to [DFInstagram](https://www.distractionfreeapps.com/) which hasn't been maintained for a long time and was difficult to update. I'm sharing it so others can do the same for themselves.

**This project is entirely free and open-source.** Feel free to fork, copy, enhance, or submit pull requests - do whatever you want with it!

## How do I get notified when there is a new update ?

There will be a story on **the official FeurStagram** account every time there is
an update:

- https://www.instagram.com/feurstagram_official/

Just follow this account and you will get a new story on each release.

## Community

Join the Discord server to get support, follow updates, and discuss development:

- https://discord.gg/Z9QvMw8s76

## Installation

You have two options:

1. **Ready-to-install APK** - Grab the latest patched APK from the [Releases](../../releases) page and install it directly
2. **DIY Patching** - Use the toolkit below to patch any Instagram version yourself

## What Gets Disabled

All content blocks are **individual runtime toggles** — long-press the Home
tab at the bottom-left of the main tab bar to open the FeurStagram settings
dialog and check/uncheck what you want blocked. A single APK covers every
combination.

| Feature | Default | Toggleable | How |
|---------|---------|------------|-----|
| **Home Feed** | Blocked | Yes | Network-level blocking |
| **Explore** | Blocked | Yes | Network-level blocking |
| **Reels** | Blocked | Yes | Network-level blocking |
| **Stories** | Visible | Yes | Network-level blocking |
| **Analytics & telemetry** | Blocked | No | Always blocked |
| **Shopping / commerce preloads** | Blocked | No | Always blocked |

## What Still Works

| Feature | Status |
|---------|--------|
| **Direct Messages** | Works |
| **Profile** | Works |
| **Reels in DMs** | Works |
| **Search** | Works |
| **Notifications** | Works |

## Settings Dialog

**Long-press the Home tab** (the house icon at the bottom-left of Instagram's
main tab bar). A dialog lists the four content toggles; changes persist
across restarts (stored in SharedPreferences `feurstagram_prefs`).


## Requirements

### Linux
```bash
sudo apt install apktool android-sdk-build-tools openjdk-17-jdk python3
```

### macOS
```bash
brew install apktool android-commandlinetools openjdk python3
 sdkmanager "build-tools;34.0.0"
```

## Quick Start

1. **Download an Instagram APK** from [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) (arm64-v8a recommended)

2. **Run the patcher:**
   ```bash
   ./patch.sh instagram.apk
   ```

   Use `--clone` to install FeurStagram **alongside** a stock Instagram
   (different package ID, separate data, both apps on the same device):

   ```bash
   ./patch.sh --clone instagram.apk
   # or specify the cloned package ID explicitly:
   ./patch.sh --clone com.instagram.android.feurstagram instagram.apk
   ```

   Without `--clone`, the patched APK keeps Instagram's original package ID
   and installs as a replacement.

3. **Install the patched APK:**
   ```bash
   adb install -r artifacts/feurstagram_patched_<instagram_apk_name>.apk
   ```

4. **Cleanup build artifacts:**
   ```bash
   ./cleanup.sh
   ```

## File Structure

```
Feurstagram/
├── patch.sh                       # Main patching script
├── cleanup.sh                     # Removes build artifacts
├── apply_network_patch.py         # Network hook patch logic
├── apply_longpress_patch.py       # Injects the long-press hook on the Home tab
├── apply_clone_patch.py           # Rewrites binary AndroidManifest.xml for --clone
├── artifacts/                     # Patched APK output directory
└── patches/
    ├── FeurConfig.smali                  # SharedPreferences-backed toggles
    ├── FeurHooks.smali                   # Network blocking hooks
    ├── FeurSettings.smali                # Settings dialog entry point
    ├── FeurHomeTabWatcher.smali          # Finds feed_tab in the tab_bar
    └── FeurSettingsLongClick.smali       # View.OnLongClickListener shim
```

## Keystore

The patched APK needs to be signed before installation. The patcher uses a keystore file for signing.

### Generating a Keystore

Create a local keystore (do not commit it), then run `patch.sh` with env vars:

```bash
FEURSTAGRAM_KEYSTORE=./feurstagram.keystore \
FEURSTAGRAM_KEYSTORE_PASS=your_store_password \
FEURSTAGRAM_KEY_ALIAS=android \
./patch.sh instagram.apk
```

If `feurstagram.keystore` doesn't exist yet, create one:

```bash
keytool -genkey -v -keystore feurstagram.keystore -alias feurstagram \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Feurstagram, OU=Feurstagram, O=Feurstagram, L=Unknown, ST=Unknown, C=XX"
```

### Keystore Details

| Property | Value |
|----------|-------|
| Filename | `feurstagram.keystore` |
| Alias | `feurstagram` |
| Algorithm | RSA 2048-bit |
| Validity | 10,000 days |

> **Note:** If you reinstall the app, you must use the same keystore to preserve your data. Signing with a different keystore requires uninstalling the previous version first.

## Debugging

View logs to see what's being blocked:
```bash
adb logcat -s "Feurstagram:D"
```

## How It Works

Everything is network-based — there is no UI-level tab redirection. Reels,
Explore, Feed and Stories are all blocked the same way (by refusing their
backend fetches), and each one is individually toggleable at runtime through
the settings dialog.

### Settings Hook
The patcher injects a watcher on the main tab bar binder (`LX/4jG`, the class
that stores the `tab_bar` ViewGroup in field `A0F`). The watcher resolves the
`feed_tab` resource id dynamically via `Resources.getIdentifier(...)`, grabs
the Home tab FrameLayout once it's laid out, and installs a long-press
listener on it. Long-pressing it opens a custom Material 3-styled dark dialog
with four `SwitchCompat` toggles backed by `SharedPreferences`
(`feurstagram_prefs`).

### Network Blocking
Hooks into `TigonServiceLayer` (a named, non-obfuscated class). Before each
request, `FeurHooks.throwIfBlocked()` runs on the request URI; blocked calls
fail with an `IOException` so the stack unwinds cleanly.

#### Blocked network paths

| Path / pattern | Purpose | Toggleable |
|----------------|---------|------------|
| `/feed/timeline/` | Home feed posts | Yes |
| `/feed/reels_tray` | Stories tray | Yes |
| `/discover/topical_explore` | Explore tab content | Yes |
| `/clips/home/`, `/clips/discover` | Reels feed + discovery | Yes |
| `/logging/` | Client event logging | No |
| `/async_ads_privacy/` | Ad-related tracking | No |
| `/async_critical_notices/` | Engagement nudge analytics | No |
| `/api/v1/media/.../seen/` (path contains `/api/v1/media/` and `/seen`) | Post “seen” tracking | No |
| `/api/v1/fbupload/` | Telemetry upload | No |
| `/api/v1/stats/` | Performance / usage stats | No |
| `/api/v1/commerce/`, `/api/v1/shopping/`, `/api/v1/sellable_items/` | Shopping / commerce preloads | No |

Note: despite the name, `/feed/reels_tray` is the stories tray endpoint in Instagram internals.

Matching uses `String.contains()` on the URI path. Instagram changes URL shapes over time; adjust `patches/FeurHooks.smali` if a block stops matching.

## Updating for New Instagram Versions

I'll update this project to support new Instagram versions as they are released. When a new version comes out, I'll apply the necessary patches and release an updated APK.

1. TigonServiceLayer is a named class (doesn't change).

2. Apply the same patches.


## Contributing

This is a personal project I'm sharing with the community. Contributions are welcome!

- 🍴 **Fork it** - Make your own version
- 🔧 **Pull requests** - Improvements and fixes are appreciated
- 📋 **Copy it** - Use the code however you want
- ✨ **Enhance it** - Build something even better

## License

This project is released under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
