#!/bin/bash
# start-agents.sh

set -e
source /home/vscode/amazon-vod-android/.env

VENV=/home/vscode/amazon-vod-android/.venv
if [ ! -d "$VENV" ]; then
    echo "=== Creating Python venv ==="
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install -q frida-tools requests mechanicalsoup beautifulsoup4
fi


echo "=== Connecting to Fire TV ==="
adb connect $FIRETV_IP:5555
adb devices

# One-time device registration — password entered here, never touches agents
if [ ! -f /home/vscode/amazon-vod-android/.device-token ]; then
    echo "=== No device token — running one-time registration ==="
    /home/vscode/amazon-vod-android/.venv/bin/python3 /home/vscode/amazon-vod-android/register_device.py
    echo "=== Registration complete ==="
else
    echo "=== Device token found, skipping registration ==="
fi

mkdir -p /home/vscode/amazon-vod-android/analysis

# Phase 1: Analysis
echo "=== Phase 1: Kodi Plugin Analysis (Opus) ==="
claude --model claude-opus-4-6 \
       --dangerously-skip-permissions \
       -p "Read CLAUDE.md and execute Phase 1 only. Write output to /home/vscode/amazon-vod-android/analysis/api-map.md. Stop after Phase 1 is complete."

echo "=== Phase 1 complete. Starting build phases (Sonnet) ==="

# Phases 2–6: Porting, building, deploying
claude --model claude-sonnet-4-6 \
       --dangerously-skip-permissions \
       -p "Read CLAUDE.md and /home/vscode/amazon-vod-android/analysis/api-map.md. Execute Phases 2 through 6. Phase 1 is already done."
