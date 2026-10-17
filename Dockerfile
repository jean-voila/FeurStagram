# FeurStagram build environment: JDK 21 + Android SDK, nothing else.
#
# Usage:
#   1. Put the stock Instagram APK/BUNDLE next to build.sh (e.g. instagram.apkm)
#   2. Drop the Morphe CLI jar into tools/ (build.sh requires it)
#   3. docker build -t feurstagram-build .
#   4. docker run --rm -v "$PWD:/work" -v feur-gradle:/root/.gradle \
#        -e GITHUB_TOKEN=<classic PAT with read:packages> -e GITHUB_ACTOR=<you> \
#        feurstagram-build ./build.sh instagram.apkm --clone
#
# The result lands at ./feurstagram.apk.
#
# Resource use: ~2-4 GB disk (JDK, SDK, Gradle cache), 4 GB RAM recommended
# for the dex-patching step.

FROM eclipse-temurin:21-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive \
    PATH="/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -sSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/cmdtools.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

WORKDIR /work
