#!/usr/bin/env bash
# Puts the phone back on the glasses' current CXR socket identity.
#
# The UUID a phone opens its RFCOMM channel with is issued by the glasses' own system service, and
# reissued whenever the glasses pair with a client - the Rokid AI app, another phone, a factory reset.
# The phone keeps the identity it was given (deliberately: discarding it on a failed attempt is what
# turned a dropped connection into a permanent one on 2026-09-17), so a re-pairing leaves it holding a
# UUID nobody answers any more, and the console says 旧的应用连接信息已经失效.
#
# The value cannot be pinned: it lives in /data/system/cxr.connection.data, owned by system with mode
# 600, and the glasses are a production build where `adb root` is refused. It also cannot be read from
# the disk - but the glasses log it at every boot, which is what this reads. Writing it into the app's
# own private preference file is then the whole repair, and it needs no gesture from the wearer.
#
#   GLASSES_SERIAL=… PHONE_SERIAL=… tools/cxr_repair.sh
#
# What a wearer can do without a computer is in docs/FAILURE_RECOVERY.md §8: folding the temples and
# triple-clicking the function key puts the glasses back into their scannable state, which is what the
# app's own 重新识别眼镜 path needs.
set -euo pipefail

REBOOT=yes
case "${1:-}" in
    --no-reboot) REBOOT=no ;;
    "") ;;
    *) echo "未知参数：$1（只支持 --no-reboot）" >&2; exit 2 ;;
esac

GLASSES="${GLASSES_SERIAL:?set GLASSES_SERIAL (adb serial of the glasses)}"
PHONE="${PHONE_SERIAL:?set PHONE_SERIAL (adb serial of the phone)}"
ADB="${ADB:-$(command -v adb)}"
PACKAGE="${PACKAGE:-ai.opstate.rokid.phone}"
PREFS="shared_prefs/cxr-connection.xml"

# Filtered by tag, not by text: adbd logs the shell command it was asked to run, so a pattern like
# `grep 'cxr-service: load CXRConnectionInfo'` matches adbd's own echo of that command and the boot
# line never comes back. `-s cxr-service` cannot match the wrong line.
identity() {
    "$ADB" -s "$GLASSES" shell "logcat -d -s cxr-service | grep 'load CXRConnectionInfo' | tail -1" |
        sed -n 's/.*load CXRConnectionInfo: \([0-9a-f-]\{36\}\).*/\1/p' |
        grep -Ex '[0-9a-f-]{36}'
}

# The glasses print the identity once per boot, so the line is only in the log until the buffer rolls
# over it. When it is gone the answer is the documented one - a reboot, which reprints it - and that is
# cheap precisely because this script runs when the phone cannot talk to the glasses anyway. A wearer
# who would rather not wait can pass --no-reboot and use the gesture path in docs/FAILURE_RECOVERY.md.
ensure_identity() {
    local value
    value="$(identity)"
    if [ -z "$value" ] && [ "$REBOOT" = "yes" ]; then
        # Progress goes to stderr: stdout of this function is the value, and a message in it would be
        # written into the preference file as if it were an identity.
        echo "日志里已没有本次启动的连接信息，重启眼镜让它重新打印（约一分钟）…" >&2
        "$ADB" -s "$GLASSES" reboot >/dev/null
        "$ADB" -s "$GLASSES" wait-for-device
        for _ in $(seq 1 40); do
            sleep 3
            value="$(identity)"
            [ -n "$value" ] && break
        done
    fi
    printf '%s' "$value"
}

worn() {
    "$ADB" -s "$PHONE" shell "run-as $PACKAGE cat $PREFS" 2>/dev/null |
        sed -n 's/.*name="socket_uuid">\([0-9a-f-]\{36\}\)<.*/\1/p'
}

current="$(ensure_identity)"
[ -n "$current" ] || { echo "拿不到眼镜的连接信息：看 docs/FAILURE_RECOVERY.md §8，或重启眼镜后重跑。" >&2; exit 1; }
# Refuse to write anything that is not a UUID, whatever produced it.
printf '%s' "$current" | grep -Ex '[0-9a-f-]{36}' >/dev/null ||
    { echo "读到的东西不像 UUID：$current" >&2; exit 1; }
mine="$(worn)"
echo "眼镜当前: $current"
echo "手机保存: ${mine:-（无）}"
if [ "$current" = "$mine" ]; then
    echo "两者一致，无需修复。若仍连不上，请看 docs/FAILURE_RECOVERY.md §8。"
    exit 0
fi

# The identity the phone holds is stale: replace just that one value, so the paired device's name and
# address - which are how the app knows *which* glasses to talk to - are left exactly as they were.
"$ADB" -s "$PHONE" shell "run-as $PACKAGE cat $PREFS" |
    sed "s|<string name=\"socket_uuid\">[^<]*</string>|<string name=\"socket_uuid\">$current</string>|" > /tmp/cxr-connection.xml
"$ADB" -s "$PHONE" shell am force-stop "$PACKAGE"
"$ADB" -s "$PHONE" push /tmp/cxr-connection.xml /data/local/tmp/cxr-connection.xml >/dev/null
# `cat >` is refused by SELinux on this path; `cp` is allowed.
"$ADB" -s "$PHONE" shell "run-as $PACKAGE cp /data/local/tmp/cxr-connection.xml $PREFS"
"$ADB" -s "$PHONE" shell "run-as $PACKAGE cat $PREFS" | grep -q "$current" ||
    { echo "写入后校验失败" >&2; exit 1; }
echo "已写入，重启手机应用…"
"$ADB" -s "$PHONE" shell am start -n "$PACKAGE/.MainActivity" >/dev/null
rm -f /tmp/cxr-connection.xml
echo "完成：手机应用已启动并按保存的身份重连。"
