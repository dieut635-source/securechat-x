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
    # Cần một task đụng tới aapt2, vì aapt2 được giải quyết qua detachedConfiguration
    # mà các task biên dịch không chạm tới. KHÔNG dùng assembleFdroidDebug: gộp dex cho
    # app này vượt quá heap 3g mà script ghim, và cổng CI cũng không hề chạy assemble.
    :app:processFdroidDebugResources
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
# Gradle có thể đỏ mà VẪN ghi ra bảng (nó ghi ở cuối build, bất kể kết quả). Không
# để set -e cắt script ở đây, nếu không bước bổ sung aapt2 cho nền tảng khác sẽ bị
# bỏ qua và bảng lặng lẽ thiếu bản Linux mà CI cần.
set +e
./gradlew "${TASKS[@]}" \
    --write-verification-metadata sha256 \
    --no-configuration-cache \
    --console=plain \
    --max-workers=2 \
    -Dorg.gradle.internal.http.connectionTimeout=30000 \
    -Dorg.gradle.internal.http.socketTimeout=60000 \
    -Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8 -XX:+UseG1GC"
GRADLE_STATUS=$?
set -e

[ -f "$METADATA" ] || { echo "LỖI: Gradle không sinh ra $METADATA"; exit 1; }

if [ "$GRADLE_STATUS" -ne 0 ]; then
    echo
    echo "CẢNH BÁO: Gradle thoát với mã $GRADLE_STATUS. Bảng vẫn được ghi nhưng có thể"
    echo "THIẾU phụ thuộc của những task không chạy tới. Xem log rồi chạy lại."
fi

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
echo "==> Bổ sung aapt2 cho các nền tảng khác"

# aapt2 có classifier theo nền tảng: macOS lấy -osx.jar, CI Linux lấy -linux.jar.
# Gradle chỉ ghi được checksum cho artifact nó thật sự giải quyết, nên bảng sinh
# trên máy Mac luôn thiếu bản Linux và làm CI đỏ với đúng lỗi này:
#
#   Dependency verification failed ... aapt2-<ver>-linux.jar
#
# Tải nốt các classifier còn lại từ Google Maven rồi tự băm. Đây là công cụ build
# xử lý tài nguyên trước khi đóng gói, tức nó CÓ khả năng chèn mã vào APK - nên
# ghim băm chứ không đưa vào trusted-artifacts.
python3 - "$METADATA" <<'PY'
import hashlib
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

path = sys.argv[1]
NS = "https://schema.gradle.org/dependency-verification"
BASE = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"
WANTED = ("linux", "osx", "windows")

with open(path, encoding="utf-8") as handle:
    text = handle.read()

versions = sorted(set(re.findall(r'name="aapt2" version="([^"]+)"', text)))
if not versions:
    print("  Không thấy aapt2 trong bảng - bỏ qua (build chưa giải quyết tới nó).")
    raise SystemExit(0)

ET.register_namespace("", NS)
tree = ET.parse(path)
root = tree.getroot()
added = 0

for version in versions:
    component = None
    for node in root.iter(f"{{{NS}}}component"):
        if node.get("name") == "aapt2" and node.get("version") == version:
            component = node
            break
    if component is None:
        continue

    have = {a.get("name") for a in component.findall(f"{{{NS}}}artifact")}
    for classifier in WANTED:
        filename = f"aapt2-{version}-{classifier}.jar"
        if filename in have:
            continue
        url = f"{BASE}/{version}/{filename}"
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                blob = response.read()
        except Exception as error:  # noqa: BLE001 - mạng hỏng thì báo, không dừng build
            print(f"  BỎ QUA {filename}: {error}")
            continue
        digest = hashlib.sha256(blob).hexdigest()
        artifact = ET.SubElement(component, f"{{{NS}}}artifact", {"name": filename})
        ET.SubElement(
            artifact,
            f"{{{NS}}}sha256",
            {"value": digest, "origin": "Downloaded from Google Maven and hashed locally"},
        )
        print(f"  + {filename}  {digest[:16]}...  ({len(blob)} byte)")
        added += 1

if added:
    ET.indent(tree, space="   ")
    tree.write(path, encoding="UTF-8", xml_declaration=True)
    print(f"  Đã thêm {added} artifact.")
else:
    print("  Không thiếu artifact nào.")
PY

echo
echo "==> XONG. Bước bắt buộc tiếp theo: chạy một build sạch để chứng minh bảng"
echo "    checksum đầy đủ. Nếu thiếu phụ thuộc nào, build sẽ đỏ ngay:"
echo
echo "      ./gradlew :app:assembleFdroidDebug --no-configuration-cache"
echo
echo "    Chưa làm bước đó thì chưa biết file này có dùng được không."

# Thoát đỏ nếu Gradle đỏ. Bảng được ghi ra không có nghĩa là nó đầy đủ, và một
# script báo thành công sau khi build hỏng đúng là kiểu xanh giả cần tránh.
exit "$GRADLE_STATUS"
