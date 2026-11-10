# FeurStagram build environment: JDK 21 + Android SDK, nothing else.
#
# Usage:
#   1. Put the stock Instagram APK/BUNDLE next to build.sh (e.g. instagram.apkm)
#   2. Drop the Morphe CLI jar into tools/ (build.sh requires it)
#   3. docker build --platform linux/amd64 -t feurstagram-build .
#   4. docker run --rm --platform linux/amd64 \
#        -v "$PWD:/work" -v feur-gradle:/root/.gradle \
#        -e GITHUB_TOKEN=<classic PAT with read:packages> -e GITHUB_ACTOR=<you> \
#        feurstagram-build ./build.sh instagram.apkm --clone
#
# The result lands at ./feurstagram.apk.
# Android's Linux SDK tools are x86-64 binaries. On Apple Silicon, enable
# amd64 emulation in the container runtime and keep the platform flags above.
#
# Resource use: ~2-4 GB disk (JDK, SDK, Gradle cache), 4 GB RAM recommended
# for the dex-patching step.

FROM eclipse-temurin:21-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

# Checksum of commandlinetools-linux-11076708_latest.zip as published on
# https://developer.android.com/studio#command-line-tools-only. Update both
# together when bumping the tools.
ARG CMDTOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -sSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && echo "${CMDTOOLS_SHA256}  /tmp/cmdtools.zip" | sha256sum -c - \
    && unzip -q /tmp/cmdtools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

WORKDIR /work
