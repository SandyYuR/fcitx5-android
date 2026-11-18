FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

ARG ANDROID_COMPILE_SDK=36
ARG ANDROID_BUILD_TOOLS=36.1.0
ARG ANDROID_NDK_VERSION=28.0.13004108
ARG ANDROID_CMAKE_VERSION=3.31.6
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/lib/jvm/java-17
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${ANDROID_HOME}/cmake/${ANDROID_CMAKE_VERSION}/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk \
    python3 \
    python3-pip \
    python3-venv \
    build-essential \
    ninja-build \
    pkg-config \
    unzip \
    zip \
    curl \
    wget \
    ca-certificates \
    file \
    xz-utils \
    && ln -sfn "/usr/lib/jvm/java-17-openjdk-$(dpkg --print-architecture)" "${JAVA_HOME}" \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL "${CMDLINE_TOOLS_URL}" -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract \
    && mv /tmp/cmdline-tools-extract/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract \
    && bash -c 'set +o pipefail; yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null' \
    && sdkmanager --sdk_root="${ANDROID_HOME}" --install \
        "cmdline-tools;latest" \
        "platform-tools" \
        "platforms;android-${ANDROID_COMPILE_SDK}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${ANDROID_NDK_VERSION}" \
        "cmake;${ANDROID_CMAKE_VERSION}" \
    && chown -R vscode:vscode "${ANDROID_HOME}"
