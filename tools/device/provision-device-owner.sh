#!/usr/bin/env bash
#
# Cấp Device Owner cho SecureChat trên một máy vừa reset, rồi KIỂM CHỨNG nó thật
# sự hoạt động.
#
# Phần kiểm chứng mới là lý do script này tồn tại. "dpm set-device-owner" in ra
# "Success" ngay cả khi app sau đó không cấp được cấu hình nào, và một máy được
# cấp quyền mà không cấp được ba khoá thì nhìn từ bên ngoài giống hệt một máy
# hoạt động đúng. Script đi hết chuỗi: cấp quyền -> app tự cấp cấu hình -> app
# đọc lại được cấu hình đó, và bắt buộc từng mắt xích phải chứng minh được.
#
# Cách dùng:
#   bash tools/device/provision-device-owner.sh <đường-dẫn-apk>
#   bash tools/device/provision-device-owner.sh <apk> --verify-only   # không cài lại
#
set -uo pipefail

APK="${1:-}"
VERIFY_ONLY=0
[[ "${2:-}" == "--verify-only" ]] && VERIFY_ONLY=1

RECEIVER=io.element.android.x.securechat.dpc.SecureChatDeviceAdminReceiver
PROBLEMS=0

die()  { printf '\nLỖI: %s\n' "$*" >&2; exit 1; }
ok()   { printf '  ĐẠT   %s\n' "$*"; }
no()   { printf '  HỎNG  %s\n' "$*"; PROBLEMS=$((PROBLEMS+1)); }
info() { printf '  ·     %s\n' "$*"; }

command -v adb >/dev/null || die "Không thấy adb. Thêm ~/Library/Android/sdk/platform-tools vào PATH."
[[ -n "$APK" && -f "$APK" ]] || die "Thiếu đường dẫn APK, hoặc file không tồn tại."

BT=$(ls -d "$HOME/Library/Android/sdk/build-tools/"* 2>/dev/null | sort -V | tail -1)
[[ -x "$BT/aapt2" ]] || die "Không thấy aapt2 trong Android SDK build-tools."
PKG=$("$BT/aapt2" dump packagename "$APK") || die "Không đọc được application id từ APK."

echo "=== 0. Máy và gói ==="
count=$(adb devices | grep -cw device || true)
[[ "$count" == "1" ]] || die "Cần đúng MỘT máy được cắm; đang thấy $count. Rút bớt hoặc cắm vào."
info "máy:  $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
info "gói:  $PKG"
[[ "$PKG" == *".debug" ]] && info "ĐÂY LÀ BẢN DEBUG — chuyển sang bản phát hành sau này PHẢI reset máy lần nữa"

if [[ $VERIFY_ONLY -eq 0 ]]; then
    echo
    echo "=== 1. Máy đã sạch chưa ==="
    # Android từ chối cấp Device Owner khi máy đã có tài khoản. Kiểm TRƯỚC để lỗi
    # nói đúng nguyên nhân, thay vì một thông điệp khó hiểu từ dpm.
    accounts=$(adb shell dumpsys account 2>/dev/null | grep -c "Account {" || true)
    if [[ "${accounts:-0}" -gt 0 ]]; then
        no "máy đang có $accounts tài khoản — Android sẽ TỪ CHỐI cấp Device Owner"
        info "phải khôi phục cài đặt gốc và bỏ qua mọi bước thêm tài khoản"
        exit 1
    fi
    ok "không có tài khoản nào"

    existing=$(adb shell dumpsys device_policy 2>/dev/null | grep -i "Device Owner" | head -1 | tr -d '\r')
    if [[ -n "$existing" ]]; then
        no "máy ĐÃ có Device Owner: $existing"
        info "chỉ một Device Owner được phép tồn tại; phải reset máy trước"
        exit 1
    fi
    ok "chưa có Device Owner nào"

    echo
    echo "=== 2. Cài app ==="
    adb install -r "$APK" >/dev/null 2>&1 || die "cài thất bại"
    ok "đã cài $PKG"

    echo
    echo "=== 3. Cấp Device Owner ==="
    result=$(adb shell dpm set-device-owner "$PKG/$RECEIVER" 2>&1 | tr -d '\r')
    if [[ "$result" == *Success* ]]; then
        ok "$result"
    else
        no "dpm từ chối: $result"
        exit 1
    fi
fi

echo
echo "=== 4. Hệ thống có công nhận không ==="
owner=$(adb shell dumpsys device_policy 2>/dev/null | grep -iA2 "Device Owner" | tr -d '\r')
if printf '%s' "$owner" | grep -q "$PKG"; then
    ok "hệ thống ghi nhận $PKG là Device Owner"
else
    no "hệ thống KHÔNG ghi nhận Device Owner cho $PKG"
    printf '%s\n' "$owner" | sed 's/^/        /'
fi

echo
echo "=== 5. Khởi động app và đọc nhật ký ==="
# Xoá log trước khi mở app: nhật ký của lần chạy trước sẽ khiến một lần cấp cấu
# hình THẤT BẠI trông như thành công.
adb logcat -c 2>/dev/null
adb shell am force-stop "$PKG" 2>/dev/null
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12

log=$(adb logcat -d -v brief 2>/dev/null | grep -E "Managed configuration|MDM configuration|device admin|device owner" || true)
if [[ -z "$log" ]]; then
    no "không thấy dòng nhật ký nào về cấu hình quản lý — app có chạy không?"
else
    printf '%s\n' "$log" | sed 's/^/        /'
fi

# Mắt xích 1: app TỰ CẤP được cấu hình.
if printf '%s' "$log" | grep -q "Managed configuration applied"; then
    ok "app đã cấp cấu hình quản lý"
elif printf '%s' "$log" | grep -q "Not device owner"; then
    no "app cho rằng nó KHÔNG phải Device Owner"
else
    info "không thấy dòng 'Managed configuration applied' — có thể cấu hình đã đúng sẵn từ lần trước"
fi

# Mắt xích 2: app ĐỌC LẠI được đúng cấu hình đó. Đây là mắt xích quan trọng nhất:
# cấp được mà đọc không ra thì ba khoá vẫn vô dụng.
if printf '%s' "$log" | grep -q "managed=true"; then
    ok "app đọc lại được cấu hình, managed=true"
elif printf '%s' "$log" | grep -q "managed=false"; then
    no "app đọc ra managed=false — cấu hình KHÔNG tới được RestrictionsManager"
else
    no "không xác định được app có đọc được cấu hình không"
fi

echo
echo "=== 6. Hệ thống có thật sự giữ ba khoá không ==="
# Không tin nhật ký của chính app: hỏi thẳng hệ thống. Nhật ký chỉ nói app tin
# điều gì; dumpsys nói hệ thống đang giữ điều gì.
restrictions=$(adb shell dumpsys device_policy 2>/dev/null | grep -iA8 "application restrictions" | tr -d '\r' || true)
[[ -n "$restrictions" ]] && printf '%s\n' "$restrictions" | head -12 | sed 's/^/        /'
for key in homeserver_url allow_registration allow_file_send; do
    if adb shell dumpsys device_policy 2>/dev/null | grep -q "$key"; then
        ok "hệ thống giữ khoá $key"
    else
        no "KHÔNG thấy khoá $key trong dumpsys device_policy"
    fi
done

echo
if [[ $PROBLEMS -eq 0 ]]; then
    echo "=== TẤT CẢ ĐẠT — chuỗi DPC chạy thật trên phần cứng ==="
else
    echo "=== $PROBLEMS MỤC HỎNG ==="
fi
echo
echo "Gỡ Device Owner (chỉ được khi app còn chạy):"
echo "  adb shell dpm remove-active-admin $PKG/$RECEIVER"
exit $PROBLEMS
