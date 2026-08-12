#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if ! command -v adb >/dev/null 2>&1; then
    echo "adb를 찾을 수 없습니다. Android Studio의 SDK Platform-Tools를 설치하고 PATH에 추가해 주세요." >&2
    exit 1
fi

cd "$REPOSITORY_ROOT"
adb start-server >/dev/null

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    DEVICE_STATE="$(adb -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)"
    if [[ "$DEVICE_STATE" != "device" ]]; then
        echo "ANDROID_SERIAL=$ANDROID_SERIAL 기기가 연결되어 있지 않거나 디버깅 승인을 기다리고 있습니다." >&2
        exit 1
    fi
else
    CONNECTED_DEVICES="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
    DEVICE_COUNT="$(printf '%s\n' "$CONNECTED_DEVICES" | awk 'NF { count++ } END { print count + 0 }')"

    if [[ "$DEVICE_COUNT" -eq 0 ]]; then
        echo "사용 가능한 Android 기기가 없습니다." >&2
        echo "USB 디버깅 또는 무선 디버깅을 연결하고, 기기에서 이 컴퓨터의 RSA 키를 허용해 주세요." >&2
        adb devices -l >&2
        exit 1
    fi
    if [[ "$DEVICE_COUNT" -gt 1 ]]; then
        echo "Android 기기가 여러 대 연결되어 있습니다. 아래처럼 대상 serial을 지정해 주세요:" >&2
        echo "ANDROID_SERIAL=<serial> ./scripts/install-device.sh" >&2
        adb devices -l >&2
        exit 1
    fi

    export ANDROID_SERIAL="$CONNECTED_DEVICES"
fi

echo "Herald debug APK를 빌드하고 $ANDROID_SERIAL 기기에 설치합니다."
./gradlew :app:installDebug --console=plain

echo "Herald를 실행합니다."
adb -s "$ANDROID_SERIAL" shell am start -W \
    -n dev.imian.herald/.ui.MainActivity >/dev/null

echo "설치가 끝났습니다. Herald에서 '권한 연결'을 눌러 알림 접근을 허용해 주세요."
