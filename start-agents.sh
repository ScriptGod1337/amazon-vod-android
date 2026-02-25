#!/bin/bash
# start-agents.sh

set -e
source /home/vscode/amazon-vod-android/.env

LOGS=/home/vscode/amazon-vod-android/logs
mkdir -p "$LOGS" /home/vscode/amazon-vod-android/analysis

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
    "$VENV/bin/python3" /home/vscode/amazon-vod-android/register_device.py
    echo "=== Registration complete ==="
else
    echo "=== Device token found, skipping registration ==="
fi

# Phase 1: Analysis
# --verbose streams every tool call (file reads, writes, bash) to stdout in real time
# tee saves a full log — tail -f logs/phase1.log in another terminal to follow along
echo "=== Phase 1: Kodi Plugin Analysis (Opus) ==="
echo "    Live output below. Also tailing: tail -f $LOGS/phase1.log"
claude --model claude-opus-4-6 \
       --dangerously-skip-permissions \
       --verbose \
       -p "Read CLAUDE.md and execute Phase 1 only. Write output to /home/vscode/amazon-vod-android/analysis/api-map.md. Stop after Phase 1 is complete." \
  2>&1 | tee "$LOGS/phase1.log"

echo "=== Phase 1 complete. Starting build phases (Sonnet) ==="
echo "    Live output below. Also tailing: tail -f $LOGS/phases2-6.log"

# Phases 2–6: Porting, building, deploying
claude --model claude-sonnet-4-6 \
       --dangerously-skip-permissions \
       --verbose \
       -p "Read CLAUDE.md and /home/vscode/amazon-vod-android/analysis/api-map.md. Execute Phases 2 through 7. Phase 1 is already done." \
  2>&1 | tee "$LOGS/phases.writing.log"
