#!/usr/bin/env bash
#
# Feurstagram build pipeline.
#
#   ./build.sh <instagram.apk|.apkm|.xapk|.apks> [--clone] [--install] [--debug]
#
# Builds the patch bundle (.mpp) from the Gradle project, then applies it to the
# given Instagram APK with the local Morphe CLI, producing ./feurstagram.apk.
#   --clone     install side-by-side as a separate package (com.instagram.android.feurstagram)
#   --install   install the result on the connected ADB device
#   --debug     enable the Debug bridge patch: the settings become drivable over
#               ADB broadcasts (see extensions/.../DebugBridge.java). Never ship it.
#
# Split bundles: an APKMirror .apkm (or .xapk/.apks) is a zip of base.apk plus
# split APKs. The Morphe CLI patches one APK, so those are merged into a single
# universal APK with APKEditor (tools/APKEditor-*.jar) before patching. The merge
# is cached under build/merged/ and reused while it is newer than the bundle.
#
# Signing: set FEURSTAGRAM_KEYSTORE_PASS (and optionally FEURSTAGRAM_KEY_PASS)
# to sign with feurstagram.keystore. That keystore is PKCS12, which the Morphe
# CLI cannot read (it expects BKS), so the APK is built unsigned and signed with
# the Android SDK's apksigner — this reproduces the existing release signature,
# so users update in place without uninstalling. Override the keystore/alias
# with FEURSTAGRAM_KEYSTORE / FEURSTAGRAM_KEY_ALIAS. Without a keystore password
# the CLI signs with a throwaway key (fine for testing, not for release).
set -euo pipefail

# Note the "|| true" on the ls|head lookups below: under `pipefail` a glob that
# matches nothing makes the whole substitution fail, and `set -e` would abort the
# script before the "not found" message it is meant to feed could ever print.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The CLI jar was renamed morphe-cli-* -> morphe-desktop-* upstream at 1.11; accept both.
CLI="$(ls -t "$DIR"/tools/morphe-cli-*.jar "$DIR"/tools/morphe-desktop-*.jar 2>/dev/null | head -1 || true)"
OUT="$DIR/feurstagram.apk"

# Morphe's Android Gradle plugin targets JDK 17-21; pin to 21 so the build does
# not pick up a newer system JDK.
if [ -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
    export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
fi

# The patcher dependency lives on GitHub Packages. Credentials come from
# ~/.gradle/gradle.properties (gpr.user/gpr.key); otherwise fall back to the
# GitHub CLI token if it is available (needs the read:packages scope).
if [ -z "${GITHUB_TOKEN:-}" ] && command -v gh >/dev/null 2>&1; then
    export GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
    export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user --jq .login 2>/dev/null || true)}"
fi

# The extension is an Android library, so the Android SDK must be locatable.
if [ -z "${ANDROID_HOME:-}" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
        "/opt/homebrew/share/android-commandlinetools" "/usr/local/share/android-commandlinetools" \
        "${ANDROID_SDK_ROOT:-}"; do
        if [ -n "$sdk" ] && { [ -d "$sdk/platform-tools" ] || [ -d "$sdk/build-tools" ]; }; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
fi

# Run every JVM tool with the pinned JDK rather than whatever `java` is on PATH
# (Homebrew often puts a JDK 17 there, which the patcher rejects).
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"

# Signing material. apksigner ships with the Android SDK build-tools.
KEYSTORE="${FEURSTAGRAM_KEYSTORE:-$DIR/feurstagram.keystore}"
KEY_ALIAS="${FEURSTAGRAM_KEY_ALIAS:-feurstagram}"
APKSIGNER=""
if [ -n "${ANDROID_HOME:-}" ]; then
    APKSIGNER="$(ls -t "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | head -1 || true)"
fi

APK=""
CLONE=0
INSTALL=0
DEBUG=0
for arg in "$@"; do
    case "$arg" in
        --clone) CLONE=1 ;;
        --install) INSTALL=1 ;;
        --debug) DEBUG=1 ;;
        *) APK="$arg" ;;
    esac
done

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: ./build.sh <instagram.apk|.apkm|.xapk|.apks> [--clone] [--install] [--debug]" >&2
    exit 1
fi
if [ -z "$CLI" ]; then
    echo "Error: Morphe CLI not found under tools/ (morphe-cli-*.jar / morphe-desktop-*.jar)." >&2
    exit 1
fi

# A split bundle has to become one universal APK before the patcher can touch it.
case "$APK" in
    *.apkm | *.xapk | *.apks)
        EDITOR="$(ls -t "$DIR"/tools/APKEditor-*.jar 2>/dev/null | head -1 || true)"
        if [ -z "$EDITOR" ]; then
            echo "Error: $(basename "$APK") is a split bundle and needs APKEditor to merge." >&2
            echo "       Fetch it into tools/, then re-run:" >&2
            echo "       gh release download V1.4.9 -R REAndroid/APKEditor -p 'APKEditor-1.4.9.jar' -D tools/" >&2
            exit 1
        fi
        MERGED="$DIR/build/merged/$(basename "${APK%.*}").apk"
        if [ -f "$MERGED" ] && [ "$MERGED" -nt "$APK" ]; then
            echo "==> [0/3] Reusing merged bundle: $(basename "$MERGED")"
        else
            echo "==> [0/3] Merging splits from $(basename "$APK")"
            mkdir -p "$(dirname "$MERGED")"
            # The bundle holds base.apk plus feature/density splits; -f overwrites
            # a stale merge. Instagram's is ~400 MB, so give the JVM room. The
            # per-file merge log is noisy, so keep it on disk rather than inline.
            if ! "$JAVA_BIN" -Xmx4g -jar "$EDITOR" m -f -i "$APK" -o "$MERGED" > "$MERGED.log" 2>&1; then
                echo "Error: APKEditor failed to merge $(basename "$APK")." >&2
                echo "       Log: $MERGED.log" >&2
                exit 1
            fi
            echo "    merged: $MERGED"
        fi
        APK="$MERGED"
        ;;
esac

echo "==> [1/3] Building patch bundle (.mpp)"
"$DIR/gradlew" -p "$DIR" :patches:build
MPP="$(ls -t "$DIR"/patches/build/libs/patches-*[0-9].mpp 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
if [ -z "$MPP" ]; then
    echo "Error: no .mpp produced under patches/build/libs/" >&2
    exit 1
fi
echo "    bundle: $MPP"

echo "==> [2/3] Applying to $(basename "$APK")"
# Scratch files are purged by default in the CLI; -r writes a JSON report of each
# patch step so a fingerprint that stopped matching is visible instead of silent.
REPORT="$DIR/build/patch-report.json"
mkdir -p "$DIR/build"
ARGS=(-jar "$CLI" patch -p "$MPP" -f -r "$REPORT" -o "$OUT")
[ "$CLONE" -eq 1 ] && ARGS+=(-e "Clone")
[ "$DEBUG" -eq 1 ] && ARGS+=(-e "Debug bridge")

# With a keystore password, defer signing to apksigner (the CLI can't read the
# PKCS12 keystore); otherwise let the CLI sign with a throwaway key for testing.
SIGN_WITH_APKSIGNER=0
if [ -n "${FEURSTAGRAM_KEYSTORE_PASS:-}" ]; then
    if [ ! -f "$KEYSTORE" ]; then
        echo "Error: keystore not found: $KEYSTORE" >&2
        exit 1
    fi
    if [ -z "$APKSIGNER" ]; then
        echo "Error: apksigner not found under \$ANDROID_HOME/build-tools." >&2
        echo "       Install the Android SDK build-tools, or unset FEURSTAGRAM_KEYSTORE_PASS" >&2
        echo "       to sign with a throwaway key (testing only)." >&2
        exit 1
    fi
    SIGN_WITH_APKSIGNER=1
    ARGS+=(--unsigned)
fi
ARGS+=("$APK")
"$JAVA_BIN" "${ARGS[@]}"

if [ "$SIGN_WITH_APKSIGNER" -eq 1 ]; then
    echo "    signing with apksigner ($(basename "$KEYSTORE"), alias $KEY_ALIAS)"
    # Force v1+v2+v3 so the signature is accepted across the whole user base's
    # devices and matches the schemes prior releases shipped; v4 (the .idsig
    # sidecar) is only useful for adb incremental install, so leave it off.
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass "pass:$FEURSTAGRAM_KEYSTORE_PASS" \
        --key-pass "pass:${FEURSTAGRAM_KEY_PASS:-$FEURSTAGRAM_KEYSTORE_PASS}" \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --v4-signing-enabled false \
        "$OUT"
    # apksigner only writes a v4 .idsig when v4 is enabled; clean up just in case.
    rm -f "$OUT.idsig"
    "$APKSIGNER" verify --print-certs "$OUT" 2>/dev/null \
        | grep -i "SHA-256" | head -1 | sed 's/^/    cert /' || true
fi

# Surface which patches actually applied. The CLI aborts on the first failure, so
# this is mostly a receipt — but a fingerprint that quietly stopped matching on a
# new Instagram build is exactly the regression that shipped as issue #117.
if [ -f "$REPORT" ] && command -v python3 >/dev/null 2>&1; then
    python3 - "$REPORT" <<'EOF' || true
import json, sys
report = json.load(open(sys.argv[1]))
for patch in report.get("appliedPatches", []):
    print(f"    ok   {patch.get('name')}")
for patch in report.get("failedPatches", []):
    print(f"    FAIL {patch.get('name')}: {patch.get('exception') or patch.get('error') or ''}")
EOF
fi

echo "==> [3/3] Output: $OUT"
[ "$DEBUG" -eq 1 ] && echo "    !! debug build: settings are drivable over ADB broadcasts — do not release"
if [ "$INSTALL" -eq 1 ]; then
    echo "    installing on device..."
    adb install -r "$OUT"
fi
