# Build environment for the Android app. Nothing is installed on the host:
# JDK, Android SDK and Gradle all live inside this image.
FROM eclipse-temurin:21-jdk-noble

ARG CMDLINE_TOOLS=15859902
ARG GRADLE_VERSION=9.9.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle \
    GRADLE_USER_HOME=/gradle-cache
ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/gradle/bin"

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates git \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" -o /tmp/cmd.zip \
 && mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && unzip -q /tmp/cmd.zip -d "$ANDROID_HOME/cmdline-tools" \
 && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
 && rm /tmp/cmd.zip

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
 && unzip -q /tmp/gradle.zip -d /opt \
 && mv "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
 && rm /tmp/gradle.zip

RUN yes | sdkmanager --licenses > /dev/null

WORKDIR /workspace
