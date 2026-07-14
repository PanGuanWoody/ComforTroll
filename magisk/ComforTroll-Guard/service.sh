#!/system/bin/sh

PKG="com.yichuan.thermalsurvey"
COMPONENT="$PKG/.RecordingService"
ACTION="$PKG.START"
STATE_DIR="/data/adb/comfortroll"
LOG_FILE="$STATE_DIR/guard.log"

mkdir -p "$STATE_DIR"
resetprop -w sys.boot_completed 0
sleep 15

log_message() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG_FILE"
}

cmd deviceidle whitelist +"$PKG" >> "$LOG_FILE" 2>&1
appops set "$PKG" RUN_IN_BACKGROUND allow >> "$LOG_FILE" 2>&1
appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >> "$LOG_FILE" 2>&1
rm -f "$STATE_DIR/recording.active" "$STATE_DIR/heartbeat"
log_message "Guard initialized"

while true; do
    if [ -f "$STATE_DIR/recording.active" ]; then
        NOW=$(date +%s)
        HEARTBEAT=$(cat "$STATE_DIR/heartbeat" 2>/dev/null)
        case "$HEARTBEAT" in
            ''|*[!0-9]*) AGE=999 ;;
            *) AGE=$((NOW - HEARTBEAT)) ;;
        esac
        if ! pidof "$PKG" >/dev/null 2>&1 || [ "$AGE" -gt 90 ]; then
            log_message "Sampling heartbeat missing; restarting foreground service"
            am start-foreground-service -a "$ACTION" -n "$COMPONENT" >> "$LOG_FILE" 2>&1
            sleep 20
        fi
    fi
    sleep 60
done
