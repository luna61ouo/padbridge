#!/system/bin/sh
# Padbridge 小幫手啟動腳本——用 adb shell 身分跑（電腦用 adb 跑，或手機上的 adb 用戶端透過無線偵錯跑）。
# 跟 App 要暗號 → 停掉舊的小幫手 → 用 App 自己的 APK 當 classpath 起新的（setsid：adb 斷了也不會跟著死）。
APK=$(pm path io.github.luna61ouo.padbridge | head -1 | cut -d: -f2)
[ -z "$APK" ] && { echo "沒裝 io.github.luna61ouo.padbridge"; exit 1; }
T=$(am broadcast -a io.github.luna61ouo.padbridge.CMD -p io.github.luna61ouo.padbridge --es op token | sed -n 's/.*data="\([0-9a-f]*\)".*/\1/p')
[ ${#T} -lt 16 ] && { echo "拿不到暗號"; exit 1; }
# 舊的先好好退場（硬斷電腦、等連線斷了才走），最多等 3 秒，再不走才 kill
if ps -A -o ARGS | grep -v grep | grep -q 'io.github.luna61ouo.padbridge.Daemon'; then
  am broadcast -a io.github.luna61ouo.padbridge.CMD -p io.github.luna61ouo.padbridge --es op daemonquit >/dev/null
  for i in 1 2 3 4 5 6; do ps -A -o ARGS | grep -v grep | grep -q 'io.github.luna61ouo.padbridge.Daemon' || break; sleep 0.5; done
fi
for p in $(ps -A -o PID,ARGS | grep 'io.github.luna61ouo.padbridge.Daemon' | grep -v grep | awk '{print $1}'); do kill "$p"; done
for p in $(ps -A -o PID,ARGS | grep 'app_process / FixAdv' | grep -v grep | awk '{print $1}'); do kill "$p"; done
sleep 1
CLASSPATH="$APK" setsid nohup app_process / io.github.luna61ouo.padbridge.Daemon "$T" > /data/local/tmp/padbridge-daemon.log 2>&1 < /dev/null &
sleep 3
cat /data/local/tmp/padbridge-daemon.log
