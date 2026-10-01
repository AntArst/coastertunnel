#!/bin/bash
set -e

echo "Building Kotlin Android app..."
cd android
./gradlew installDebug

# adb honours ANDROID_SERIAL when more than one device is attached.
echo "Launching app..."
adb shell am start -n com.coaster.tunnel/com.coaster.tunnel.MainActivity

echo "Done!"
