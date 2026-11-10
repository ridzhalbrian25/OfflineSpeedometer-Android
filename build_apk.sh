#!/bin/bash
set -e
echo "Building Offline Speedometer Android APK..."
chmod +x gradlew
./gradlew assembleDebug
echo "Build complete! APK located at: app/build/outputs/apk/debug/app-debug.apk"
