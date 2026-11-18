FROM mcr.microsoft.com/devcontainers/base:ubuntu-22.04

ARG ANDROID_COMPILE_SDK=36
ARG ANDROID_BUILD_TOOLS=36.1.0
ARG ANDROID_NDK_VERSION=28.0.13004108
ARG ANDROID_CMAKE_VERSION=3.31.6
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV JAVA_HOME=/usr/lib/jvm/java-17
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}:${ANDROID_HOME}/cmake/${ANDROID_CMAKE_VERSION}/bin:${PATH}"

RUN [ "$(uname -s)/$(uname -m)" = "Linux/x86_64" ] \
    || { echo "This Android development image requires Linux x86_64 (Docker --platform=linux/amd64)." >&2; exit 1; }

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
    && curl -fsSL --retry 3 --retry-delay 5 --retry-all-errors \
        --connect-timeout 30 --max-time 300 "${CMDLINE_TOOLS_URL}" -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract \
    && mv /tmp/cmdline-tools-extract/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract \
    && bash -c 'set +o pipefail; yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null' \
    && sdkmanager --sdk_root="${ANDROID_HOME}" --install \
        "platform-tools" \
        "platforms;android-${ANDROID_COMPILE_SDK}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" \
        "ndk;${ANDROID_NDK_VERSION}" \
        "cmake;${ANDROID_CMAKE_VERSION}" \
    && chown -R vscode:vscode "${ANDROID_HOME}"

# Check the installed image against the repository's build requirements. The
# bootstrapped command-line tools stay at build 15859902; do not update to latest.
COPY .devcontainer/toolchain-check.py /tmp/codespaces-toolchain/toolchain-check.py
COPY build-logic/convention/src/main/kotlin/Versions.kt /tmp/codespaces-toolchain/Versions.kt
RUN python3 -B /tmp/codespaces-toolchain/toolchain-check.py \
        --versions /tmp/codespaces-toolchain/Versions.kt \
    && rm -rf /tmp/codespaces-toolchain
