#!/bin/bash
set -e

SDK_DIR="/workspaces/android-sdk"
mkdir -p "$SDK_DIR/cmdline-tools"

echo "Downloading Android command line tools..."
curl -sS -o "$SDK_DIR/cmdline-tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip

echo "Extracting command line tools..."
unzip -q "$SDK_DIR/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
rm "$SDK_DIR/cmdline-tools.zip"

echo "Accepting licenses and installing SDK packages..."
export JAVA_HOME="/usr/local/sdkman/candidates/java/21.0.10-ms"
export PATH="$JAVA_HOME/bin:$SDK_DIR/cmdline-tools/latest/bin:$PATH"

# Accept licenses
yes | sdkmanager --sdk_root="$SDK_DIR" --licenses > /dev/null

# Install necessary platforms, build-tools, and NDK
echo "Installing platform-tools, platforms;android-34, build-tools;34.0.0, and ndk;26.1.10909125..."
sdkmanager --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;26.1.10909125"

# Configure local.properties
echo "sdk.dir=$SDK_DIR" > /workspaces/tts-pdf-reader/local.properties
echo "ndk.dir=$SDK_DIR/ndk/26.1.10909125" >> /workspaces/tts-pdf-reader/local.properties

echo "Android SDK and NDK setup completed successfully!"
