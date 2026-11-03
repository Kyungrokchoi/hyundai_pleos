#!/usr/bin/env bash
# Prints host, toolchain and device info. Read-only.
echo "== host =="; uname -a; echo "arch: $(uname -m)"
echo "== java ==";  java -version 2>&1 | head -1; echo "JAVA_HOME=${JAVA_HOME:-}"
echo "== android =="; echo "ANDROID_HOME=${ANDROID_HOME:-} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-}"
ls "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/nonexistent}}/platforms" 2>/dev/null | tr '\n' ' '; echo
echo "== adb =="; adb version 2>&1 | head -1; adb devices 2>&1 | tail -n +2
echo "== node/python/docker/postgres =="; node -v 2>&1; npm -v 2>&1; python3 --version 2>&1; docker --version 2>&1 | head -1; psql --version 2>&1
echo "== car-app gradle wrapper =="; (cd "$(dirname "$0")/../car-app" 2>/dev/null && ./gradlew --version 2>&1 | grep -E 'Gradle|JVM' | head -2)
