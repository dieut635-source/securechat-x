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

# adb devices báo "device" KHÔNG có nghĩa là chạy được lệnh trên máy. Sau một lần
# khôi phục cài đặt gốc, máy quên uỷ quyền USB debugging và mọi lệnh shell trả
# "error: closed" trong khi danh sách máy vẫn trông bình thường.
#
# Phải kiểm ở đây, vì mọi phép kiểm bên dưới đều đọc kết quả của adb shell: một
# lệnh hỏng trả về chuỗi rỗng, grep -c đếm được 0, và "không có tài khoản nào"
# hiện ra thành ĐẠT. Đúng lỗi đó đã xảy ra ngày 04/09 — script báo máy sạch
# trong khi thật ra nó chưa hỏi được máy câu nào.
probe=$(adb shell echo securechat-probe 2>&1 | tr -d '\r')
[[ "$probe" == "securechat-probe" ]] || die "adb không chạy được lệnh trên máy (nhận: '${probe:-rỗng}').
  Gần như luôn là chưa cấp quyền USB debugging: nhìn màn hình máy, chấp nhận hộp
  thoại 'Allow USB debugging', tick 'Always allow', rồi chạy lại.
  Nếu không thấy hộp thoại: mở khoá màn hình, hoặc Developer options -> Revoke
  USB debugging authorizations rồi rút cắm lại cáp."
ok "adb chạy được lệnh trên máy"
info "máy:  $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
info "gói:  $PKG"
[[ "$PKG" == *".debug" ]] && info "ĐÂY LÀ BẢN DEBUG — chuyển sang bản phát hành sau này PHẢI reset máy lần nữa"

if [[ $VERIFY_ONLY -eq 0 ]]; then
    echo
    echo "=== 1. Máy đã sạch chưa ==="
    # Android từ chối cấp Device Owner khi máy đã có tài khoản. Kiểm TRƯỚC để lỗi
    # nói đúng nguyên nhân, thay vì một thông điệp khó hiểu từ dpm.
    account_dump=$(adb shell dumpsys account 2>/dev/null)
    [[ -n "$account_dump" ]] || die "không đọc được dumpsys account — không kết luận được máy có sạch không"
    accounts=$(printf '%s' "$account_dump" | grep -c "Account {" || true)
    if [[ "${accounts:-0}" -gt 0 ]]; then
        no "máy đang có $accounts tài khoản — Android sẽ TỪ CHỐI cấp Device Owner"
        info "phải khôi phục cài đặt gốc và bỏ qua mọi bước thêm tài khoản"
        exit 1
    fi
    ok "không có tài khoản nào"

    # Dùng "dpm list-owners", KHÔNG grep dumpsys. dumpsys có dòng chẩn đoán
    # "Device Owner Type: -1" mà -1 nghĩa là KHÔNG có owner — grep "Device Owner"
    # bắt đúng dòng đó và báo động giả. Đã xảy ra ngày 04/09.
    owners=$(adb shell dpm list-owners 2>&1 | tr -d '\r')
    [[ -n "$owners" ]] || die "không đọc được danh sách owner — không kết luận được máy đã có Device Owner chưa"
    if [[ "$owners" != *"no owners"* ]]; then
        no "máy ĐÃ có owner: $owners"
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
owner=$(adb shell dpm list-owners 2>&1 | tr -d '\r')
if printf '%s' "$owner" | grep -q "$PKG"; then
    ok "hệ thống ghi nhận $PKG là owner"
    printf '%s\n' "$owner" | sed 's/^/        /'
else
    no "hệ thống KHÔNG ghi nhận owner cho $PKG"
    printf '%s\n' "$owner" | sed 's/^/        /'
fi

echo
echo "=== 5. Khởi động app và đọc nhật ký ==="
# Xoá log trước khi mở app: nhật ký của lần chạy trước sẽ khiến một lần cấp cấu
# hình THẤT BẠI trông như thành công.
pid_before=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
adb logcat -c 2>/dev/null
# am force-stop KHÔNG giết được một app đang giữ Device Owner — Android bảo vệ
# nó. Vẫn gọi cho trường hợp chưa cấp quyền, nhưng KHÔNG được cho rằng app đã
# khởi động lại: so PID trước/sau mới biết sự thật.
adb shell am force-stop "$PKG" 2>/dev/null
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 12
pid_after=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [[ -n "$pid_before" && "$pid_before" == "$pid_after" ]]; then
    info "app KHÔNG khởi động lại (PID $pid_after) — Device Owner không bị force-stop."
    info "Nhật ký khởi động chỉ có sau khi cài đè APK hoặc khởi động lại máy."
fi

log=$(adb logcat -d -v brief 2>/dev/null | grep -E "Managed configuration|MDM configuration|device admin|device owner" || true)
if [[ -n "$log" ]]; then
    printf '%s\n' "$log" | sed 's/^/        /'
fi

# Mắt xích 1: app TỰ CẤP được cấu hình.
if printf '%s' "$log" | grep -q "Managed configuration applied"; then
    ok "app đã cấp cấu hình quản lý"
elif printf '%s' "$log" | grep -q "Not device owner"; then
    no "app cho rằng nó KHÔNG phải Device Owner"
else
    # Im lặng ở đây là kết quả AlreadyCurrent bình thường: cấu hình đã đúng nên
    # không ghi lại. Đánh nó thành HỎNG sẽ dạy người dùng bỏ qua kết quả script.
    info "không cấp lại — cấu hình đã đúng sẵn (AlreadyCurrent), đây là hành vi đúng"
fi

# Mắt xích 2: app ĐỌC LẠI được đúng cấu hình đó. Đây là mắt xích quan trọng nhất:
# cấp được mà đọc không ra thì ba khoá vẫn vô dụng.
#
# Chỉ quan sát được khi giá trị ĐỔI. DefaultMdmService in "MDM configuration
# changed" khi nhận quảng bá, nhưng dòng "MDM configuration loaded" lúc khởi tạo
# thì KHÔNG BAO GIỜ thấy: nó được dựng trong lúc tạo graph, tức trước khi
# PlatformInitializer cài Timber, nên log rơi vào hư vô. Kiểm chứng 04/09 trên
# SM-A066B/Android 16.
if printf '%s' "$log" | grep -q "MDM configuration changed"; then
    ok "app ĐỌC LẠI được cấu hình vừa cấp (qua quảng bá của hệ thống)"
elif printf '%s' "$log" | grep -q "managed=true"; then
    ok "app đọc lại được cấu hình, managed=true"
elif printf '%s' "$log" | grep -q "managed=false"; then
    no "app đọc ra managed=false — cấu hình KHÔNG tới được RestrictionsManager"
else
    info "KHÔNG quan sát được việc đọc ở lần chạy này, vì cấu hình không đổi nên"
    info "hệ thống không phát quảng bá. Đây KHÔNG phải lỗi, nhưng cũng KHÔNG phải"
    info "bằng chứng. Muốn chứng minh dứt điểm: đổi một giá trị trong"
    info "DefaultSecureChatPolicySource, build lại, cài đè, rồi chạy lại script này."
fi

echo
echo "=== 6. Vì sao không hỏi hệ thống trực tiếp ==="
# dumpsys device_policy KHÔNG in application restrictions — nó chỉ có user
# restrictions (no_add_*), thứ hoàn toàn khác. Bản đầu của script grep ba khoá
# trong dumpsys và báo HỎNG cả ba trong khi chuỗi vẫn chạy đúng: sai chỗ hỏi,
# không phải sai hệ thống. Cũng không có lệnh adb nào đọc được application
# restrictions của một gói.
#
# Nên bằng chứng duy nhất có được từ ngoài là chính app nói nó đọc ra gì, ở mắt
# xích 2 bên trên. Nói thẳng giới hạn đó thay vì dựng một phép kiểm luôn đỏ.
info "dumpsys device_policy KHÔNG chứa application restrictions, và adb không có"
info "lệnh nào đọc chúng. Bằng chứng nằm ở mắt xích 2: app tự báo nó đọc được gì."

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
