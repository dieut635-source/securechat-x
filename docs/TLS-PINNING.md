# Ghim chứng chỉ TLS

App chỉ chấp nhận chứng chỉ của `chat.securechat.com.au` và `push.securechat.com.au`
khi chuỗi chứng chỉ chứa một trong hai khoá công khai được ghim trong
`app/src/main/res/xml/network_security_config.xml`.

## Đang ghim gì

| Chứng chỉ | Hạn | Vai trò |
|---|---|---|
| ISRG Root X2 | 02/09/2032 | Gốc của chuỗi hiện tại |
| ISRG Root YE | 02/09/2032 | Pin dự phòng, một mức dưới |

Chuỗi thật tại thời điểm ghim (01/09/2026):

```
chat.securechat.com.au  ->  Let's Encrypt YE2  ->  ISRG Root YE  ->  ISRG Root X2
```

**Không ghim chứng chỉ máy chủ.** Nó gia hạn 60 ngày một lần với khoá mới, mà APK
phân phối thủ công qua USB — ghim nó là mỗi lần gia hạn phải cầm cáp đi từng máy.

## Nó chặn được gì, và không chặn được gì

**Chặn:** bất kỳ CA nào trong ~150 CA hệ thống của Android bị chiếm hoặc cấp nhầm
chứng chỉ cho tên miền của mình. Đây là mối đe doạ chính.

**Không chặn:** kẻ qua được khâu xác thực tên miền của Let's Encrypt — chiếm máy
chủ, chiếm DNS, hoặc cướp định tuyến. Chặn cả trường hợp đó phải ghim khoá riêng
của mình, và trả giá bằng rủi ro vận hành ở trên.

## `expiration` là van an toàn, không phải sơ suất

`pin-set` có `expiration="2027-09-01"`. Sau ngày đó Android **bỏ qua** ghim thay vì
từ chối kết nối.

Nghe như tự làm yếu mình, nhưng với phân phối thủ công thì đây là bắt buộc: nếu
quên cập nhật APK, hậu quả là "không còn được ghim" chứ không phải "cả đội mất
liên lạc và phải đi thu từng máy về".

**Phải dời hạn này trước khi nó tới.** Đặt lịch nhắc trước ít nhất 2 tháng.

## Cách kiểm tra pin còn đúng

Chạy trước mỗi lần phát hành, và bất cứ khi nào đổi cấu hình TLS máy chủ:

```bash
for host in chat.securechat.com.au push.securechat.com.au; do
  echo "== $host"
  openssl s_client -showcerts -connect "$host:443" -servername "$host" </dev/null 2>/dev/null \
    | awk '/BEGIN CERT/,/END CERT/' \
    | openssl x509 -noout -pubkey 2>/dev/null \
    | openssl pkey -pubin -outform der 2>/dev/null \
    | openssl dgst -sha256 -binary | base64
done
```

Lệnh trên chỉ in pin của chứng chỉ đầu tiên. Để lấy đủ cả chuỗi, dùng script kiểm
chứng đã dùng khi thiết lập — nó tách từng chứng chỉ rồi đối chiếu với file cấu
hình, và báo rõ nếu **không pin nào khớp**.

## Khi cần đổi pin

1. Lấy chuỗi mới, tính lại pin cho **cả hai** host.
2. Sửa `network_security_config.xml`, giữ **ít nhất hai** pin.
3. Dời `expiration` lên khoảng một năm.
4. Build APK, cài lên **một máy test trước**, xác nhận đăng nhập và nhận thông báo đẩy.
5. Chỉ sau đó mới triển khai rộng.

Bước 4 không được bỏ. Pin sai không báo lỗi lúc build — nó chỉ hiện ra khi thiết
bị thật không kết nối được.
