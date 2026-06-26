#!/usr/bin/env bash
#
# Feurstagram build pipeline.
#
#   ./build.sh <instagram.apk> [--clone] [--install]
#
# Builds the patch bundle (.mpp) from the Gradle project, then applies it to the
# given Instagram APK with the local Morphe CLI, producing ./feurstagram.apk.
#   --clone     install side-by-side as a separate package (com.instagram.android.feurstagram)
#   --install   install the result on the connected ADB device
#
# Signing: set FEURSTAGRAM_KEYSTORE_PASS (and optionally FEURSTAGRAM_KEY_PASS)
# to sign with feurstagram.keystore. That keystore is PKCS12, which the Morphe
# CLI cannot read (it expects BKS), so the APK is built unsigned and signed with
# the Android SDK's apksigner — this reproduces the existing release signature,
# so users update in place without uninstalling. Override the keystore/alias
# with FEURSTAGRAM_KEYSTORE / FEURSTAGRAM_KEY_ALIAS. Without a keystore password
# the CLI signs with a throwaway key (fine for testing, not for release).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI="$(ls -t "$DIR"/tools/morphe-cli-*.jar 2>/dev/null | head -1)"
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
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "${ANDROID_SDK_ROOT:-}"; do
        if [ -n "$sdk" ] && { [ -d "$sdk/platform-tools" ] || [ -d "$sdk/build-tools" ]; }; then
            export ANDROID_HOME="$sdk"
            break
        fi
    done
fi

# Signing material. apksigner ships with the Android SDK build-tools.
KEYSTORE="${FEURSTAGRAM_KEYSTORE:-$DIR/feurstagram.keystore}"
KEY_ALIAS="${FEURSTAGRAM_KEY_ALIAS:-feurstagram}"
APKSIGNER=""
if [ -n "${ANDROID_HOME:-}" ]; then
    APKSIGNER="$(ls -t "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | head -1)"
fi

APK=""
CLONE=0
INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --clone) CLONE=1 ;;
        --install) INSTALL=1 ;;
        *) APK="$arg" ;;
    esac
done

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: ./build.sh <instagram.apk> [--clone] [--install]" >&2
    exit 1
fi
if [ -z "$CLI" ]; then
    echo "Error: Morphe CLI not found under tools/ (morphe-cli-*.jar)." >&2
    exit 1
fi

echo "==> [1/3] Building patch bundle (.mpp)"
"$DIR/gradlew" -p "$DIR" :patches:build
MPP="$(ls -t "$DIR"/patches/build/libs/patches-*[0-9].mpp 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1)"
if [ -z "$MPP" ]; then
    echo "Error: no .mpp produced under patches/build/libs/" >&2
    exit 1
fi
echo "    bundle: $MPP"

echo "==> [2/3] Applying to $(basename "$APK")"
ARGS=(-jar "$CLI" patch -p "$MPP" -f --purge --bytecode-mode=FULL -o "$OUT")
[ "$CLONE" -eq 1 ] && ARGS+=(-e "Clone")

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
java "${ARGS[@]}"

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

echo "==> [3/3] Output: $OUT"
if [ "$INSTALL" -eq 1 ]; then
    echo "    installing on device..."
    adb install -r "$OUT"
fi
