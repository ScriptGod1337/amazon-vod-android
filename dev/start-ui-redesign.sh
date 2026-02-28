#!/usr/bin/env bash
# start-ui-redesign.sh
#
# Launches the UI redesign agents for Phase 22.
#
# Usage:
#   bash dev/start-ui-redesign.sh implementer   # run implementer agent
#   bash dev/start-ui-redesign.sh reviewer      # run reviewer agent after implementer finishes
#   bash dev/start-ui-redesign.sh both          # run implementer then reviewer sequentially

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOGS="$SCRIPT_DIR/logs"
SCREENSHOTS="$SCRIPT_DIR/review-screenshots"

mkdir -p "$LOGS" "$SCREENSHOTS"

cd "$PROJECT_DIR"

# Verify emulator is connected
echo "=== Checking emulator connection ==="
if ! adb devices | grep -q "emulator-5554"; then
    echo "ERROR: emulator-5554 not found."
    echo "Start the Android TV emulator first:"
    echo "  \$ANDROID_HOME/emulator/emulator -avd <your-tv-avd-name> &"
    exit 1
fi
echo "emulator-5554 connected."

# Push dev token to emulator if .device-token exists
if [ -f "$PROJECT_DIR/.device-token" ]; then
    echo "=== Pushing dev token to emulator ==="
    adb -s emulator-5554 push "$PROJECT_DIR/.device-token" /data/local/tmp/.device-token
    adb -s emulator-5554 shell chmod 644 /data/local/tmp/.device-token
    adb -s emulator-5554 shell touch /data/local/tmp/.device-token
    echo "Token pushed."
else
    echo "WARNING: .device-token not found — app will show login screen on emulator."
fi

run_implementer() {
    echo ""
    echo "=== Starting UI Implementer Agent ==="
    echo "    Log: $LOGS/ui-implementer.log"
    echo ""
    claude \
        --model claude-sonnet-4-6 \
        --dangerously-skip-permissions \
        --verbose \
        -p "$(cat "$SCRIPT_DIR/ui-implementer.md")" \
        2>&1 | tee "$LOGS/ui-implementer.log"
    echo ""
    echo "=== Implementer finished. See $LOGS/ui-implementer.log ==="
}

run_reviewer() {
    echo ""
    echo "=== Starting UI Reviewer Agent ==="
    echo "    Log: $LOGS/ui-reviewer.log"
    echo ""
    claude \
        --model claude-sonnet-4-6 \
        --dangerously-skip-permissions \
        --verbose \
        -p "$(cat "$SCRIPT_DIR/ui-reviewer.md")" \
        2>&1 | tee "$LOGS/ui-reviewer.log"
    echo ""
    echo "=== Reviewer finished. See dev/ui-review-report.md ==="
}

case "${1:-both}" in
    implementer)
        run_implementer
        ;;
    reviewer)
        if [ ! -f "$SCRIPT_DIR/ui-review-request.md" ]; then
            echo "ERROR: dev/ui-review-request.md not found."
            echo "Run the implementer agent first."
            exit 1
        fi
        run_reviewer
        ;;
    both)
        run_implementer
        echo ""
        echo "=== Implementer complete. Starting reviewer in 3 seconds... ==="
        sleep 3
        run_reviewer
        echo ""
        echo "=== Both agents complete. ==="
        echo "    Review report: dev/ui-review-report.md"
        echo "    Screenshots:   dev/review-screenshots/"
        ;;
    *)
        echo "Usage: $0 [implementer|reviewer|both]"
        exit 1
        ;;
esac
