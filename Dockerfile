# ─────────────────────────────────────────────────────────────────────
# KZKT — Android build image (self-contained)
#
#   Base  : Eclipse Temurin JDK 17
#   SDK   : Android cmdline-tools + Platform 36/37.0 + Build Tools 36
#   Build : Gradle wrapper 9.6.1 (bundled in the repo)
#
#   Build image:
#       docker build -t kzkt-builder .
#
#   Debug build (gradle cache persists via named volume):
#       docker run --rm -v kzkt-gradle:/root/.gradle \
#           kzkt-builder ./gradlew assembleDebug
#
#   Release build (falls back to your local debug keystore):
#       docker run --rm -v kzkt-gradle:/root/.gradle \
#           -v "$HOME/.android:/root/.android" \
#           kzkt-builder ./gradlew assembleRelease
#
#   Custom keystore for publishing (mount ro):
#       -v "$PWD/keystore.properties:/app/keystore.properties:ro"
#       -v "$PWD/release.keystore:/app/release.keystore:ro"
#
#   APKs are written to /app/app/build/outputs/apk/{debug,release}/.
#   Extract them with `docker cp <container>:/app/app/build/outputs/...`.
# ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk

# OS tools required by sdkmanager
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip wget \
 && rm -rf /var/lib/apt/lists/*

# ── Android command-line tools ──────────────────────────────────────
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
RUN mkdir -p /opt/android/cmdline-tools \
 && wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdtools.zip \
 && unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools \
 && mv /tmp/cmdtools/cmdline-tools /opt/android/cmdline-tools/latest \
 && rm -rf /tmp/cmdtools /tmp/cmdtools.zip

ENV ANDROID_HOME=/opt/android \
    ANDROID_SDK_ROOT=/opt/android \
    ANDROID_USER_HOME=/root/.android \
    PATH="$PATH:/opt/android/cmdline-tools/latest/bin:/opt/android/platform-tools"

# ── SDK packages ────────────────────────────────────────────────────
# compileSdk = 37 maps to the "platforms;android-37.0" package, which is
# published on the dev channel (repository2-3.xml) — hence --channel=2.
RUN yes | sdkmanager --channel=2 --licenses >/dev/null \
 && sdkmanager \
      "platform-tools" \
      "platforms;android-36" \
      "build-tools;36.0.0" \
 && sdkmanager --channel=2 "platforms;android-37.0" \
 && sdkmanager --list_installed | grep -E 'platforms;android|build-tools;' | head

# ── Project sources ─────────────────────────────────────────────────
WORKDIR /app
COPY . .

# No need for a long-lived Gradle daemon inside a container
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"

CMD ["./gradlew", "assembleDebug"]
