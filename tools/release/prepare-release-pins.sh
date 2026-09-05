#!/usr/bin/env bash
#
# Sinh hai file "pin" mà build_securechat_offline.sh bắt buộc phải có.
#
# VÌ SAO CẦN HAI FILE NÀY
#
# Nghi thức phát hành không tin vào bất cứ thứ gì nằm trong repository. Hai câu
# hỏi nó phải trả lời độc lập:
#
#   1. "APK này có đúng được ký bằng khoá của chúng ta không?"
#      -> so vân tay certificate với securechat-release-cert.sha256
#
#   2. "Commit này có đúng do người của chúng ta ký không?"
#      -> so vân tay khoá OpenPGP với release-tag-signer.fingerprint
#
# Nếu hai file đó nằm trong repo thì kẻ sửa được repo cũng sửa được chúng, và cả
# hai phép kiểm thành vô nghĩa. Nên chúng bắt buộc ở ngoài, và script phát hành
# từ chối chạy nếu phát hiện chúng nằm trong cây nguồn.
#
# CHẠY MỘT LẦN. Sau đó giữ hai file cùng chỗ với keystore.
#
# Cách dùng:
#   bash tools/release/prepare-release-pins.sh /Users/mac/SecureChat-keystore securechat
#
set -euo pipefail

OUT_DIR="${1:-}"
ALIAS="${2:-securechat}"

die() { printf '\nLỖI: %s\n' "$*" >&2; exit 1; }
ok()  { printf '  OK  %s\n' "$*"; }

[[ -n "$OUT_DIR" ]] || die "Thiếu thư mục đích. Ví dụ: bash $0 /Users/mac/SecureChat-keystore securechat"
[[ -d "$OUT_DIR" ]] || die "Không thấy thư mục $OUT_DIR"
[[ -t 0 ]] || die "Phải chạy trực tiếp trong Terminal — script hỏi mật khẩu bằng input ẩn."

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo "")
if [[ -n "$REPO_ROOT" ]]; then
    case "$(cd "$OUT_DIR" && pwd -P)/" in
        "$(cd "$REPO_ROOT" && pwd -P)/"*)
            die "Thư mục đích nằm TRONG repository. Hai file pin phải ở ngoài, nếu không chúng vô nghĩa." ;;
    esac
fi

KEYSTORE="$OUT_DIR/securechat-release.keystore"
CERT_PIN="$OUT_DIR/securechat-release-cert.sha256"
SIGNER_PIN="$OUT_DIR/release-tag-signer.fingerprint"

[[ -f "$KEYSTORE" ]] || die "Không thấy keystore tại $KEYSTORE"

# JDK 21 là bắt buộc cho cả nghi thức phát hành. Dùng keytool của bản khác có thể
# sinh ra vân tay đúng nhưng build sau đó sẽ bị từ chối, và lúc đó lỗi trông như
# lỗi chữ ký chứ không phải lỗi JDK.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
else
    KEYTOOL=$(command -v keytool) || die "Không thấy keytool"
fi
java_major=$("${KEYTOOL%keytool}java" -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')
[[ "$java_major" == "21" ]] || die "Cần JDK 21, đang là ${java_major:-không rõ}. Đặt JAVA_HOME rồi chạy lại."

echo "==> 1/2  Vân tay certificate phát hành"
if [[ -f "$CERT_PIN" ]]; then
    ok "đã có $CERT_PIN — giữ nguyên, không ghi đè"
else
    # keytool tự hỏi mật khẩu. KHÔNG truyền qua tham số dòng lệnh: nó sẽ nằm lại
    # trong lịch sử shell và trong danh sách tiến trình của mọi user trên máy.
    printf '  Nhập mật khẩu keystore khi được hỏi.\n'
    # Xuất certificate ra FILE, không qua đường ống.
    #
    # Bản trước viết `fingerprint=$(keytool ... | openssl ...)`. Với `set -euo
    # pipefail`, keytool hỏng là cả lệnh gán hỏng, và `set -e` THOÁT NGAY — không
    # bao giờ chạy tới dòng `|| die` ngay bên dưới. Nhập sai mật khẩu thì màn hình
    # chỉ có "Enter keystore password:" rồi im lặng trở về dấu nhắc, không một chữ
    # nào nói vì sao. Đã dựng lại đúng triệu chứng đó bằng một keystore thử.
    #
    # Tách ra thì keytool giữ được stderr trên terminal (cả câu hỏi mật khẩu lẫn
    # câu báo lỗi), và mã thoát của nó kiểm được.
    cert_der=$(mktemp "${TMPDIR:-/tmp}/securechat-cert.XXXXXX") || die "Không tạo được file tạm"
    trap 'rm -f "$cert_der"' EXIT
    if ! "$KEYTOOL" -exportcert -keystore "$KEYSTORE" -alias "$ALIAS" -file "$cert_der" -rfc >/dev/null; then
        die "keytool không xuất được certificate.
Hai nguyên nhân thường gặp, theo đúng thứ tự hay gặp:
  1. SAI MẬT KHẨU keystore. Kiểm bằng:
       \"$KEYTOOL\" -list -keystore \"$KEYSTORE\"
     Lệnh đó báo rõ 'password was incorrect' nếu sai.
  2. Sai alias. Lệnh -list ở trên cũng in ra danh sách alias thật có trong keystore."
    fi
    [[ -s "$cert_der" ]] || die "keytool báo thành công nhưng file certificate rỗng — bất thường, dừng lại."
    fingerprint=$(openssl x509 -in "$cert_der" -outform der 2>/dev/null \
        | openssl dgst -sha256 \
        | sed -nE 's/.*=[[:space:]]*([0-9a-fA-F]{64}).*/\1/p' | tr 'A-F' 'a-f') || true
    [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || die "Đọc được certificate nhưng không tính được vân tay SHA-256.
Kiểm openssl: openssl version"
    printf '%s\n' "$fingerprint" > "$CERT_PIN"
    chmod 600 "$CERT_PIN"
    ok "$CERT_PIN"
fi

echo "==> 2/2  Vân tay khoá OpenPGP dùng để ký tag"
if [[ -f "$SIGNER_PIN" ]]; then
    ok "đã có $SIGNER_PIN — giữ nguyên, không ghi đè"
else
    command -v gpg >/dev/null || die "Chưa có gpg. Cài bằng: brew install gnupg"
    signing_key=$(git config --get user.signingkey || echo "")
    [[ -n "$signing_key" ]] || die "Chưa đặt git config user.signingkey. Tạo khoá rồi cấu hình trước."
    fpr=$(gpg --list-keys --with-colons "$signing_key" 2>/dev/null | awk -F: '/^fpr:/{print $10; exit}')
    [[ "$fpr" =~ ^[0-9A-Fa-f]{40}$ ]] || die "Không đọc được vân tay của khoá $signing_key"
    printf '%s\n' "$(printf '%s' "$fpr" | tr 'A-Z' 'a-z')" > "$SIGNER_PIN"
    chmod 600 "$SIGNER_PIN"
    ok "$SIGNER_PIN"
fi

echo
echo "XONG. Hai file pin đã sẵn sàng:"
printf '  %s\n  %s\n' "$CERT_PIN" "$SIGNER_PIN"
echo
echo "LƯU Ý: giữ hai file này CÙNG CHỖ với keystore và sao lưu như keystore."
echo "Mất chúng thì không phát hành được cho tới khi sinh lại; sinh lại được, nhưng"
echo "mất khả năng phát hiện nếu keystore hoặc khoá ký đã bị đánh tráo trong lúc đó."
