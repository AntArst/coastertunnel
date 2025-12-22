#!/bin/bash
set -e

echo "Building Kotlin Android app..."
cd android
./gradlew installDebug

echo "Launching app..."
adb -s R52W4010HTD shell am start -n com.coaster.tunnel/com.coaster.tunnel.MainActivity

echo "Done!"
