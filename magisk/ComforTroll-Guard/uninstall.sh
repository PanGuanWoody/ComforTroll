#!/system/bin/sh

cmd deviceidle whitelist -com.yichuan.thermalsurvey >/dev/null 2>&1
rm -rf /data/adb/comfortroll
