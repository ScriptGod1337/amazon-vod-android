#!/bin/bash
set -e

echo "=== Installing Java ==="
apt-get update && apt-get install -y openjdk-17-jdk wget unzip curl git python3-pip python3-venv adb

echo "=== Installing Android SDK ==="
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools

wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdtools.zip
unzip -q /tmp/cmdtools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
rm /tmp/cmdtools.zip

export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

echo "=== Accepting SDK Licenses ==="
yes | sdkmanager --licenses

echo "=== Installing SDK Components ==="
sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"

echo "=== Adding ANDROID_HOME to environment ==="
cat >> /etc/environment <<EOF
ANDROID_HOME=/opt/android-sdk
PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools
EOF

cat >> ~/.bashrc <<EOF
export ANDROID_HOME=/opt/android-sdk
export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools
EOF

echo "=== Cloning Kodi Plugin ==="
git clone https://github.com/Sandmann79/xbmc /home/vscode/kodi-plugin

echo "=== Done. Reload shell or run: source ~/.bashrc ==="