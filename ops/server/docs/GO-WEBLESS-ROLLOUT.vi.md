# Rollout gỡ đăng nhập Web của SecureChat

Tài liệu này áp dụng cho bước **gỡ giao diện Web công khai** trên
`chat.securechat.com.au`. Bước này không xóa dữ liệu Synapse, phòng chat, khóa E2EE,
thiết bị hay media.

## Ranh giới bảo mật

Sau rollout:

- `/` và `/login` chỉ trả một trang SecureChat trung tính, không có form đăng nhập,
  JavaScript hoặc mã Web cũ;
- `/.well-known/matrix/client` được phục vụ từ static root trung tính riêng, vẫn giữ
  RTC focus LiveKit để không làm hỏng audio/video call;
- `/_synapse/admin/` trả `404` qua public nginx, nhưng đường quản trị nội bộ
  `http://127.0.0.1:8008/_synapse/admin/` không bị sửa;
- proxy Matrix và các route LiveKit hiện có được giữ nguyên;
- mã Web cũ có thể còn trong snapshot/backup phục vụ forensics, nhưng nginx
  không còn `root` hoặc `alias` tới nó. Script không cung cấp rollback mở Web cũ.

Đây **chưa phải** là enforcement “chỉ app SecureChat được đăng nhập”. Matrix login là
API mà Android cũng cần dùng, nên không được chặn endpoint này ở nginx trước khi module
xác thực app-only và APK tương thích đã sẵn sàng. Chỉ gỡ trang Web không ngăn một client
khác tự gọi API. Không dùng `User-Agent`, CORS hay một shared secret nhúng trong APK làm
rào cản cuối cùng vì chúng có thể bị giả mạo hoặc trích xuất.

Sau khi guard chạy `enforce`, migration/bind hoàn tất và session APK đã được kiểm tra,
chuyển sang phase riêng trong `ops/server/docs/CLOSE-PUBLIC-LOGIN.vi.md`. Không gộp hai phase
trong một lần chạy vì đóng `/login` quá sớm có thể khóa chính thiết bị cần giữ.

## File của rollout

- `ops/server/scripts/disable-web-login.sh`
- `ops/server/scripts/verify-nginx-topology.py`
- `ops/server/scripts/verify-synapse-runtime.py`
- `ops/server/assets/app-only-public/index.html`
- `ops/server/assets/app-only-public/.well-known/matrix/client`

Hãy chép cả cây `ops/server/` lên một thư mục tạm trên máy chủ; không chỉ chép riêng script,
vì script sẽ xác minh asset tương đối trước khi làm gì.

## 1. Snapshot ngoài script — bắt buộc

Backup tự động phục vụ forensics/recovery thủ công fail-closed, nhưng không thay
thế snapshot hạ tầng.
Trước cửa sổ thay đổi, tạo snapshot DigitalOcean hoặc một bản sao mã hóa, quyền truy cập
hạn chế, tối thiểu gồm:

```text
/opt/matrix/nginx/nginx.conf
/opt/matrix/docker-compose.yml
/opt/matrix/element-web/
/opt/matrix/synapse/data/homeserver.yaml
```

Không đưa snapshot lên Git: `homeserver.yaml`, Compose và các file lân cận có thể chứa
secret. Ghi lại mã snapshot và giờ UTC trong change record.

## 2. Chạy báo cáo chỉ đọc

Trên máy chủ, từ thư mục chứa cây `ops/server/`:

```bash
sudo REPORT=1 bash ops/server/scripts/disable-web-login.sh
```

Không đặt biến cũng tương đương chế độ báo cáo:

```bash
sudo bash ops/server/scripts/disable-web-login.sh
```

Chỉ tiếp tục khi báo cáo xác nhận:

- Docker Compose hiện tại qua `config -q`;
- container `nginx` đang chạy và `nginx -t` thành công;
- có đúng một root Web cũ hoặc một root neutral;
- nhận diện được proxy Matrix/Synapse;
- có đúng một location và alias cho `/.well-known/matrix/client`.
- chưa có tham chiếu Admin API, hoặc đã có đúng một
  `location ^~ /_synapse/admin/ { return 404; }` trong server block public.
- homeserver có đúng một client listener `8008`, không `additional_resources`;
  Docker chỉ publish port này trên `127.0.0.1`/`::1`, không có published port,
  client listener, network mode hoặc network thứ hai chưa review.
- chỉ có một running Synapse-like container/process; nó thuộc đúng Compose
  service, chạy đúng một `homeserver` process với duy nhất
  `--config-path /data/homeserver.yaml`, không worker/override; alias Docker
  literal `synapse` thuộc duy nhất origin và nginx cùng network đó.

Nếu script báo topology không khớp, **dừng tại đây**. Lấy snapshot đã lọc bí mật bằng:

```bash
sudo docker exec nginx nginx -T 2>&1 \
  | grep -E '^[[:space:]]*(server_name|root|location|alias|proxy_pass)'
sudo docker compose -f /opt/matrix/docker-compose.yml config --services
sudo docker inspect nginx \
  --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
```

Không tự sửa anchor trong script để “cho chạy qua” nếu chưa review cấu hình nginx thật.

## 3. Áp dụng trong cửa sổ bảo trì

Đảm bảo không có thay đổi hạ tầng song song, rồi chạy:

```bash
sudo APPLY=1 bash ops/server/scripts/disable-web-login.sh
```

Luồng áp dụng:

1. kiểm tra asset không chứa Element/Vector/Riot, form, input hoặc script, đồng thời
   xác minh origin Synapse 8008 chỉ publish trên loopback;
2. sao lưu nginx, Compose và static root cũ (nếu có) dưới
   `/opt/matrix/backups/disable-web-login/<backup-id>/` với quyền hạn chế;
3. sinh candidate bằng các thay đổi giới hạn: thêm read-only mount neutral, đổi
   `root`/alias `.well-known` khỏi mã Web cũ và thêm exact block sau vào đúng server
   block HTTPS có `server_name chat.securechat.com.au`:

   ```nginx
   location ^~ /_synapse/admin/ {
       return 404;
   }
   ```

   Script dừng fail-closed nếu không xác định duy nhất server block đó hoặc thấy
   topology Admin API khác; block này không sửa listener Synapse localhost:8008.
   Script cũng từ chối include ngoài `mime.types`, upstream alias, rewrite/generic
   proxy và proxy ở vhost khác; effective topology được audit từ `nginx -T`;
4. chạy `docker compose config -q` và `nginx -t` trên candidate;
5. đặt static root neutral trước, sau đó atomically thay cấu hình và recreate riêng nginx;
6. kiểm tra Matrix versions, `.well-known`, `/`, `/login`, public Admin API phải là
   `404`, Admin API trực tiếp localhost:8008 vẫn là `200`, và các route LiveKit nhận diện
   được;
7. nếu kiểm tra sau cài đặt thất bại, không restore Web/nginx cũ; script cố giữ
   candidate static-neutral/Admin-closed rồi yêu cầu xử lý incident fail-closed.

Lệnh thành công sẽ in `Backup ID`. Lưu ID đó vào change record.

## 4. Kiểm chứng độc lập

Từ một máy ngoài server:

```bash
curl -fsS https://chat.securechat.com.au/.well-known/matrix/client
curl -fsS https://chat.securechat.com.au/_matrix/client/versions
curl -fsS https://chat.securechat.com.au/
curl -fsS https://chat.securechat.com.au/login
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  https://chat.securechat.com.au/_synapse/admin/v1/server_version)" = 404
```

Trên chính máy chủ, kiểm tra kênh quản trị nội bộ vẫn còn (không chạy lệnh này từ máy
ngoài):

```bash
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  http://127.0.0.1:8008/_synapse/admin/v1/server_version)" = 200
```

Kỳ vọng:

- `.well-known` trỏ tới `https://chat.securechat.com.au` và còn khai báo LiveKit RTC
  focus tự host;
- Matrix versions trả JSON hợp lệ;
- Admin API public trả đúng `404`, còn endpoint nội bộ localhost trả `200`;
- `/` và `/login` cùng hiện thông báo SecureChat chỉ dùng app, không có màn hình đăng
  nhập Web;
- đăng nhập và nhắn tin trên hai APK baseline vẫn hoạt động;
- nếu trước đó có LiveKit, gọi audio/video vẫn hoạt động.

Không coi rollout hoàn tất nếu chỉ kiểm tra HTTP. Cần smoke test APK thật vì Matrix và
LiveKit là các luồng cần bảo toàn.

Các probe trong script bỏ qua proxy môi trường bằng `--noproxy '*'` và ép domain về
`127.0.0.1`. Script cũng từ chối Docker publish origin Synapse `8008/tcp` ra
`0.0.0.0`/`::`; nginx 404 không đủ nếu origin vẫn truy cập trực tiếp từ Internet.

## 5. Recovery fail-closed

`ROLLBACK=1` bị script từ chối có chủ đích: nginx/Compose cũ có thể mở lại Web,
Admin API hoặc xóa các deny block login/register đã được cài ở phase sau. Khi có
sự cố, giữ ingress fail-closed, lấy snapshot hiện tại và tạo một candidate riêng
vẫn thỏa toàn bộ điều kiện: static neutral, Admin API 404, login/register 404 nếu
phase close đã chạy, Matrix/LiveKit routes đúng. Test candidate bằng `nginx -t`
và `verify-nginx-topology.py` trước khi cài. Không trực tiếp restore file
`original/nginx.conf` từ backup này.

## Tiêu chí chốt bước này

- root public không còn phục vụ mã Web cũ;
- không còn logo, tên hoặc form đăng nhập Element/Vector/Riot trên URL công khai;
- Admin API public trả 404 trong khi Admin API nội bộ vẫn hoạt động;
- `.well-known`, Matrix sync/login của APK và LiveKit vẫn hoạt động;
- backup ID, snapshot ID, kết quả smoke test và thời gian UTC đã được ghi lại;
- đội triển khai hiểu rằng module app-only/một-account-một-device là bước enforcement
  riêng, chưa được thay thế bởi rollout này.
