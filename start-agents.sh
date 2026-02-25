#!/bin/bash
# start-agents.sh

set -e
source .env

echo "=== Connecting to Fire TV ==="
adb connect $FIRETV_IP:5555
adb devices

# One-time device registration
if [ ! -f /home/vscode/amazon-vod-android/.device-token ]; then
    echo "=== No device token found — running one-time registration ==="
    
    # Ask for password at runtime — never stored
    read -s -p "Amazon Password: " AMAZON_PASSWORD
    echo

    python3 /workspace/kodi-plugin/register_device.py \
        --email "$AMAZON_EMAIL" \
        --password "$AMAZON_PASSWORD" \
        --output /home/vscode/amazon-vod-android/.device-token

    chmod 600 /home/vscode/amazon-vod-android/.device-token
    unset AMAZON_PASSWORD

    echo "=== Device registered. Password cleared from memory. ==="
else
    echo "=== Device token found, skipping registration ==="
fi

# Phase 1: Analysis — use Opus for deep Python comprehension
echo "=== Phase 1: Kodi Plugin Analysis (Opus) ==="
claude --model claude-opus-4-5 \
       --dangerously-skip-permissions \
       -p "Read CLAUDE.md and execute Phase 1 only. Write output to /home/vscode/amazon-vod-android/analysis/api-map.md. Stop after Phase 1 is complete."

echo "=== Phase 1 complete. Starting main build phases (Sonnet) ==="

# Phases 2–6: Porting, building, debugging — use Sonnet
claude --model claude-sonnet-4-5 \
       --dangerously-skip-permissions \
       -p "Read CLAUDE.md and /home/vscode/amazon-vod-android/analysis/api-map.md. Execute Phases 2 through 6. Phase 1 is already done."
