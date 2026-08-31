#!/usr/bin/env bash
#
# Chạy toàn bộ unit test trên máy phát triển bộ nhớ nhỏ (A-05).
#
# Vì sao cần script này: gradle.properties đặt -Xmx8g cho daemon vì R8 của bản
# release cần chừng đó. Trên máy 16 GB, daemon 8 GB cộng với các test worker
# song song làm daemon sập giữa chừng, nên cổng test đầy đủ chưa từng chạy trọn
# một lần ở máy này. Script hạ heap của daemon và ép test chạy tuần tự.
#
# Bản build release KHÔNG được dùng script này - nó vẫn cần 8 GB.
#
# Dùng:
#   ./scripts/run-full-test-gate.sh            # toàn bộ module
#   ./scripts/run-full-test-gate.sh :app:test  # chỉ một task
#
set -euo pipefail

cd "$(dirname "$0")/.."

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
    TASKS=("test")
fi

OUT_DIR="build/full-test-gate"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/gradle.log"

# Mốc thời gian để loại kết quả XML cũ của những lần chạy trước. Không có mốc
# này thì script sẽ đếm cả test không hề chạy trong lần này và báo xanh giả.
STAMP="$OUT_DIR/.start-stamp"
touch "$STAMP"

echo "==> Chạy: ${TASKS[*]}"
echo "==> Log:  $LOG"
echo

# --max-workers=2 giới hạn số task chạy song song; maxParallelForks=1 buộc mỗi
# module chỉ mở một JVM test. -Xmx3g cho daemon là đủ để biên dịch, không đủ để
# chạy R8 - đó là chủ ý.
set +e
./gradlew "${TASKS[@]}" \
    --console=plain \
    --max-workers=2 \
    -Dorg.gradle.jvmargs="-Xmx3g -Dfile.encoding=UTF-8 -XX:+UseG1GC" \
    -Psecurechat.test.maxParallelForks=1 \
    -Psecurechat.test.maxHeapSize=1g \
    2>&1 | tee "$LOG"
GRADLE_STATUS=${PIPESTATUS[0]}
set -e

echo
echo "==> Gradle thoát với mã $GRADLE_STATUS"
echo "==> Đếm lại từ file kết quả XML, không tin thông báo của Gradle:"
echo

# Gradle chỉ in số test khi có lỗi. Đếm thẳng từ JUnit XML để biết chắc bộ test
# đã thực sự chạy chứ không phải bị bỏ qua vì up-to-date.
set +e
python3 - "$OUT_DIR" "$STAMP" <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET

out_dir, stamp = sys.argv[1], sys.argv[2]
cutoff = os.path.getmtime(stamp)
files = glob.glob("**/build/test-results/**/*.xml", recursive=True)
# Chỉ tính file được ghi sau khi script bắt đầu.
files = [p for p in files if os.path.getmtime(p) >= cutoff]

total = failed = skipped = 0
classes = 0
failures = []
for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    if root.tag != "testsuite":
        continue
    classes += 1
    total += int(root.get("tests", 0))
    skipped += int(root.get("skipped", 0))
    for case in root.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            failed += 1
            cls = (case.get("classname") or "?").split(".")[-1]
            failures.append(f"{cls} :: {case.get('name')}")

print(f"  Lớp test:  {classes}")
print(f"  Tổng test: {total}")
print(f"  Bỏ qua:    {skipped}")
print(f"  HỎNG:      {failed}")
if failures:
    print()
    print("  Danh sách hỏng:")
    for name in sorted(failures):
        print(f"    - {name}")

summary = os.path.join(out_dir, "summary.txt")
with open(summary, "w") as handle:
    handle.write(f"classes={classes}\ntests={total}\nskipped={skipped}\nfailed={failed}\n")
    for name in sorted(failures):
        handle.write(f"FAILED {name}\n")
print()
print(f"  Tóm tắt ghi tại: {summary}")

# Bộ test rỗng là thất bại, không phải thành công. Trường hợp này xảy ra khi mọi
# task đều up-to-date và không ai để ý.
if total == 0:
    print()
    print("  CẢNH BÁO: không có test nào chạy. Thử lại với --rerun-tasks.")
    sys.exit(2)
sys.exit(1 if failed else 0)
PY
COUNT_STATUS=$?
set -e

if [ "$GRADLE_STATUS" -ne 0 ] || [ "$COUNT_STATUS" -ne 0 ]; then
    echo
    echo "==> CỔNG TEST ĐỎ"
    exit 1
fi

echo
echo "==> CỔNG TEST XANH"
