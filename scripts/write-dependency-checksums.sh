#!/usr/bin/env bash
#
# Sinh gradle/verification-metadata.xml — bảng checksum SHA-256 của mọi phụ
# thuộc build (mục chặn 04 trong báo cáo audit).
#
# ĐIỀU PHẢI HIỂU TRƯỚC KHI CHẠY
#
# File này KHÔNG chứng minh phụ thuộc là sạch. Nó chỉ ghi lại đúng những gì máy
# này tải về hôm nay, rồi khoá chặt lại. Nếu một thư viện đã bị chèn mã độc từ
# trước khi chạy script, checksum sẽ ghi lại nguyên trạng thái độc đó và mọi
# build sau đều "đạt".
#
# Cái nó thật sự chặn: từ hôm nay trở đi, một phụ thuộc bị đổi nội dung trên
# kho Maven mà giữ nguyên số phiên bản sẽ làm build ĐỎ thay vì lặng lẽ đi vào
# APK. Đó là kiểu tấn công chuỗi cung ứng phổ biến nhất, và trước đây chúng ta
# hoàn toàn không có phòng bị.
#
# Vì vậy phải chạy script này trên máy sạch, mạng tin được, và chỉ chạy một lần
# rồi soi kỹ phần thay đổi ở những lần cập nhật sau.
#
# Dùng:
#   ./scripts/write-dependency-checksums.sh
#
set -euo pipefail

cd "$(dirname "$0")/.."

METADATA="gradle/verification-metadata.xml"

ASSUME_YES=no
if [ "${1:-}" = "--yes" ]; then
    ASSUME_YES=yes
fi

if [ -f "$METADATA" ]; then
    echo "ĐÃ CÓ $METADATA"
    echo
    echo "Chạy lại sẽ GỘP thêm checksum mới vào file cũ, không xoá cái đã có."
    echo "Nếu muốn sinh lại từ đầu thì tự xoá file trước."
    echo
    if [ "$ASSUME_YES" = yes ]; then
        echo "(--yes: tiếp tục gộp)"
    elif [ -t 0 ]; then
        read -r -p "Tiếp tục gộp? [y/N] " reply
        [[ "$reply" =~ ^[Yy]$ ]] || { echo "Dừng."; exit 0; }
    else
        echo "Không có bàn phím để hỏi. Chạy lại với --yes nếu thật sự muốn gộp."
        exit 1
    fi
fi

# Các task này phải phủ hết những gì cổng phát hành trên CI sẽ chạy, nếu không
# build sẽ đỏ ở CI vì gặp phụ thuộc chưa có trong bảng. Danh sách bám theo
# .github/workflows/securechat-release.yml.
TASKS=(
    testClasses
    compileFdroidReleaseSources
    :app:lintFdroidRelease
    detekt
    ktlintCheck
)

echo "==> Sinh checksum cho: ${TASKS[*]}"
echo "==> Sẽ tải về toàn bộ phụ thuộc chưa có trong cache. Việc này lâu."
echo

# --write-verification-metadata không chạy chung với configuration cache.
# Heap 3g và max-workers 2 vì cùng lý do như run-full-test-gate.sh.
#
# KHÔNG dùng --refresh-dependencies. Bản đầu của script này có nó, với lập luận
# "để checksum phản ánh kho thật chứ không phải cache cũ". Lập luận đó sai:
# Gradle vẫn băm từ artifact trong cache, và một artifact đã bị sửa mà khớp
# metadata từ xa thì cờ này cũng không tải lại. Nó không thêm được bảo đảm nào.
#
# Cái giá thì rất thật: lần chạy có cờ đó treo sau ~50 phút ở một lần tải dở -
# kết nối còn mở, luồng vẫn nằm trong ContentLengthInputStream.read, nhưng
# không file nào trong cache được ghi thêm. Nhìn từ ngoài y hệt "mạng chậm".
#
# Hai timeout dưới đây để một mirror treo làm build ĐỎ nhanh thay vì đứng im
# hàng giờ. Đó là lỗi tệ nhất của lần chạy trước: nó không hỏng, nó chỉ không
# bao giờ xong.
./gradlew "${TASKS[@]}" \
    --write-verification-metadata sha256 \
    --no-configuration-cache \
    --console=plain \
    --max-workers=2 \
    -Dorg.gradle.internal.http.connectionTimeout=30000 \
    -Dorg.gradle.internal.http.socketTimeout=60000 \
    -Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8 -XX:+UseG1GC"

[ -f "$METADATA" ] || { echo "LỖI: Gradle không sinh ra $METADATA"; exit 1; }

echo
echo "==> Kiểm tra lại file vừa sinh"

# Đúng những gì cổng CI kiểm, chạy tại chỗ để không phải đợi CI mới biết hỏng.
grep -Fq '<sha256 value=' "$METADATA" || {
    echo "LỖI: không có checksum SHA-256 nào trong file."
    exit 1
}

COMPONENTS=$(grep -c '<component ' "$METADATA" || true)
CHECKSUMS=$(grep -c '<sha256 value=' "$METADATA" || true)

echo "  Thành phần: $COMPONENTS"
echo "  Checksum:   $CHECKSUMS"

# Gradle mặc định bật cả kiểm chữ ký PGP khi sinh file. Rất nhiều thư viện
# Android không ký PGP, nên bật lên là build đỏ hàng loạt vì lý do không liên
# quan tới an toàn. Chỉ giữ kiểm checksum.
if grep -q '<verify-signatures>true</verify-signatures>' "$METADATA"; then
    echo
    echo "  Tắt verify-signatures (nhiều thư viện Android không ký PGP)."
    python3 - "$METADATA" <<'PY'
import sys
path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
text = text.replace(
    "<verify-signatures>true</verify-signatures>",
    "<verify-signatures>false</verify-signatures>",
)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
PY
fi

echo
echo "==> XONG. Bước bắt buộc tiếp theo: chạy một build sạch để chứng minh bảng"
echo "    checksum đầy đủ. Nếu thiếu phụ thuộc nào, build sẽ đỏ ngay:"
echo
echo "      ./gradlew :app:assembleFdroidDebug --no-configuration-cache"
echo
echo "    Chưa làm bước đó thì chưa biết file này có dùng được không."
