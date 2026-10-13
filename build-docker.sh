#!/usr/bin/env bash
# Build the debug APK in a throwaway Docker container. Nothing is installed on the host.
# Usage: ./build-docker.sh   -> ./notification-pinger.apk (project root, copied from app/build/outputs/apk/debug/)
set -euo pipefail
cd "$(dirname "$0")"

docker run --rm --name pinger-build \
  -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$PWD":/work -w /work \
  eclipse-temurin:17-jdk bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq && apt-get install -y -qq unzip wget >/dev/null
export ANDROID_HOME=/opt/android
mkdir -p $ANDROID_HOME/cmdline-tools
wget -q -O /tmp/cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmd.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
wget -q -O /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
unzip -q /tmp/gradle.zip -d /opt
export PATH=/opt/gradle-8.7/bin:$PATH
cd /work
[ -x gradlew ] || gradle wrapper --gradle-version 8.7 >/dev/null
./gradlew --no-daemon assembleDebug
chown -R "$HOST_UID:$HOST_GID" /work
'

# Copy the APK to the project root so it's easy to find.
cp app/build/outputs/apk/debug/app-debug.apk ./notification-pinger.apk
echo "APK: $PWD/notification-pinger.apk"
