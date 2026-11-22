#!/system/bin/sh
# FoldLink Engine: runs as root from boot. When the FoldLink app wants the wide screen it writes
# foldspan.sh into its shared folder; we pick it up and run it (the app itself can't get root).
D=/data/media/0/Android/data/com.murdhi.foldlink/files
LOG=/data/local/tmp/foldspan.log
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
# stop Android's phantom-process killer from killing the engine
device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null
settings put global settings_enable_monitor_phantom_procs false 2>/dev/null
while true; do
  if [ -f "$D/foldspan.sh" ]; then
    mv -f "$D/foldspan.sh" /data/local/tmp/foldspan_run.sh
    pkill -f "app_process.*foldspan.jar" 2>/dev/null
    sleep 0.5
    echo "=== $(date) starting engine" >> "$LOG"
    setsid sh /data/local/tmp/foldspan_run.sh >> "$LOG" 2>&1 < /dev/null &
  fi
  sleep 1
done
