# Đóng public login ở trạng thái vận hành ổn định

Đây là phase **sau** khi guard một-device đã chạy `enforce`, toàn bộ tài khoản
hiện hữu đã được plan/bind và các APK cần giữ đã vượt smoke test bằng session hiện
tại. Phase này không xóa device, token, khóa E2EE, phòng chat hoặc media.

Không dùng User-Agent, CORS hoặc header/secret tĩnh trong APK để nhận diện client.
Các tín hiệu đó đều có thể bị giả mạo hoặc trích xuất. Ingress steady-state đơn giản
không cho tạo phiên đăng nhập hoặc đăng ký mới từ Internet.

## Vì sao phải chặn 16 location

Source Synapse `v1.159.0` khai báo `LoginRestServlet` bằng
`client_patterns("/login$", v1=True)`. Với helper của tag này, bốn base endpoint
được đăng ký là:

- `/_matrix/client/v3/login`;
- `/_matrix/client/r0/login`;
- `/_matrix/client/unstable/login`;
- `/_matrix/client/api/v1/login`.

Chặn riêng `v3` và `r0` là fail-open vì hai đường còn lại vẫn tạo session. Script
đặt một exact location `return 404` cho từng base endpoint; query string vẫn bị exact
location bắt vì nginx không dùng query string khi chọn location. Script cũng đặt một
prefix `^~ .../login/` cho từng version để chặn các subroute SSO/CAS/token-login. Tổng
cộng là bốn exact base location và bốn prefix location cho login. Script áp
dụng cùng exact/prefix policy cho bốn version `/register`, vì callback login
không chạy cho registration và config drift không được phép tạo device/token.

Admin API là phase trước và phải có đúng block sau trong server block HTTPS public:

```nginx
location ^~ /_synapse/admin/ {
    return 404;
}
```

Script từ chối APPLY nếu block Admin API thiếu hoặc có topology khác. Kênh trực tiếp
`http://127.0.0.1:8008` không bị sửa và chỉ được dùng cục bộ/qua SSH kiểm soát.
Script parse `listeners` trong homeserver config và yêu cầu đúng một HTTP client
listener ở container port `8008`, không có `additional_resources`. Docker chỉ
được publish port này với `HostIp` `127.0.0.1`/`::1`, đúng host port 8008, trên
đúng một network đã review; mọi host-published port khác, listener client thứ hai,
`0.0.0.0`, `::`, host/container-network mode hoặc mapping không rõ đều làm script
dừng. Đây là kiểm tra origin trực tiếp, không chỉ kiểm tra nginx.

## Điều kiện bắt buộc trước APPLY

1. Có snapshot hạ tầng độc lập, tối thiểu gồm nginx, Compose, homeserver config và
   PostgreSQL; đã ghi snapshot ID và UTC trong change record.
2. Module `securechat_single_device.module.SecureChatSingleDeviceModule` được cấu
   hình duy nhất với `mode: enforce` trong process Synapse đang chạy. Script kiểm tra
   YAML trong container, container phải mới hơn config và log phải có marker
   `guard ready mode=enforce`. Top-level `modules` phải chứa **đúng một** entry guard,
   không có module khác; script còn hash `__init__.py`, `core.py`, `module.py` runtime
   và so với bundle `ops/server/single-device` đã review.
3. Không có Synapse worker riêng trong topology. Script này cố ý fail-closed nếu tên
   hoặc image/process nào là Synapse thứ hai/generic worker; runtime phải có đúng
   một homeserver process, một effective config path và designated Docker alias.
   Topology nhiều worker cần candidate riêng sau khi xem `nginx -T` và routing thật.
4. Mọi tài khoản/device đang giữ đã qua CLI plan/bind an toàn. Không còn thao tác
   revoke/bind đang chạy và request login đang xử lý dở đã drain.
5. APK đang giữ session đã được kiểm tra login trước đó, PIN, nhắn tin, file,
   audio/video call và background. Không xóa data app hoặc logout sau khi đóng login.
6. Phase `disable-web-login.sh` đã hoàn tất: trang Web trung tính, LiveKit RTC focus
   còn trong `.well-known`, và Admin API public trả `404`.
7. Cấu hình xác thực là password-local-only: public/guest registration, MAS, JWT,
   OIDC mới và legacy, SAML, CAS, LDAP legacy, login-via-existing-session, password
   provider, appservice và module khác đều tắt/không có; password local DB bật.
   `session_lifetime` và hai
   access/refresh-token lifetime phải không cấu hình để session được giữ không tự hết
   hạn sau khi public login đã đóng.
8. Nếu Shared-Secret Registration vẫn cấu hình để provisioning nội bộ, xác nhận nó
   chỉ dùng qua kênh loopback bằng `CONFIRM_SHARED_SECRET_RESTRICTED=1`; giá trị secret
   không được script đọc hoặc in.

`CONFIRM_*` là xác nhận vận hành có chủ đích, không thay thế các bước kiểm tra trên.

## Report mặc định

```bash
sudo REPORT=1 bash ops/server/scripts/close-public-login.sh
```

Không đặt biến cũng là report. Report không sửa file, không reload container và in:

- số exact location nhận diện cho bốn base login và bốn base register;
- HTTP public của các path và Admin API;
- HTTP trực tiếp localhost của login/Admin API;
- trạng thái có/không chứng minh được guard `enforce`.

Nếu nginx/Compose/server block thật khác giả định, dừng và lấy snapshot đã lọc bí mật:

```bash
sudo docker exec nginx nginx -T 2>&1 \
  | grep -E '^[[:space:]]*(server_name|listen|location|root|alias|proxy_pass)'
sudo docker compose -f /opt/matrix/docker-compose.yml config --services
```

Không nới regex hoặc bỏ precondition chỉ để candidate chạy qua.

Trước khi chép lên server, chạy 5 regression test cho tokenizer/topology helper:

```bash
python3 -m unittest discover -s ops/server/tests -v
```

## APPLY steady-state

Sau khi hoàn tất checklist:

```bash
sudo APPLY=1 \
  CONFIRM_GUARD_ENFORCE=1 \
  CONFIRM_BINDINGS_COMPLETE=1 \
  CONFIRM_ACTIVE_SESSIONS_VERIFIED=1 \
  CONFIRM_SHARED_SECRET_RESTRICTED=1 \
  bash ops/server/scripts/close-public-login.sh
```

Script sẽ:

1. xác minh guard `enforce`, password-local-only, single-process topology, Docker
   origin 8008 chỉ loopback, nginx và Compose hiện tại;
2. backup `nginx.conf`, tạo candidate chỉ thay/thêm 16 login/register location;
3. yêu cầu Admin API block exact đã tồn tại;
4. chạy `nginx -t` trên candidate trong container;
5. atomically cài candidate và recreate riêng nginx;
6. xác minh GET **và** POST đều trả `404` trên bốn version login/register và
   prefix tương ứng, gồm probe SSO/CAS/registration stages; localhost:8008 của
   bốn base login vẫn trả `200`;
7. xác minh `/versions`, probe sync/media và các route LiveKit nhận diện được vẫn
   tới backend. Khi lỗi, chỉ phục hồi bản gốc nếu bản đó đã có đầy đủ deny policy;
   nếu bản gốc mở auth, script giữ candidate fail-closed và yêu cầu xử lý incident.

Probe sync/media không có access token chỉ chứng minh ingress còn route tới Synapse
(kỳ vọng `401`, media có thể `200` theo config). Sau APPLY bắt buộc smoke test bằng
APK đang giữ token: sync foreground/background, gửi/nhận tin, upload/download file và
audio/video call. Không ghi access token vào script, shell history hoặc report.

Mọi `curl` tự động đều dùng `--noproxy '*'` và `--resolve ...:127.0.0.1`, tránh proxy
môi trường tạo kết quả giả. Auditor tokenize effective `nginx -T`, từ chối upstream
alias, inline/multiline bypass, rewrite/internal routing, generic proxy, proxy ở vhost
khác và location Matrix ngoài allowlist chính xác. Nếu topology thật khác, phải viết
candidate riêng sau review; không bỏ kiểm tra.

## Rollback fail-closed và enrollment tách biệt

Rollback không được mở public login/registration. Script chỉ chấp nhận một
backup mà `build_candidate` xác nhận đã có đầy đủ policy đóng và hoàn toàn
idempotent; backup trước lần đóng đầu tiên sẽ bị từ chối:

```bash
sudo ROLLBACK=1 \
  CONFIRM_FAIL_CLOSED_ROLLBACK=1 \
  CONFIRM_GUARD_ENFORCE=1 \
  CONFIRM_SHARED_SECRET_RESTRICTED=1 \
  bash ops/server/scripts/close-public-login.sh
```

Có thể thêm `BACKUP_ID='<id>'`. Trước khi rollback, script snapshot trạng thái
đang đóng; nếu rollback lỗi, nó tự đưa trạng thái đóng trở lại. Sau rollback,
script chạy lại toàn bộ probe login/register/Admin/sync/media/LiveKit.

Enrollment không nằm trong rollback này. Fresh install không thể login ở
baseline steady-state. Provisioning tương lai phải dùng kênh **nội bộ/restricted**
đã review trong khi public auth vẫn đóng: tạo session/device, upload E2EE key,
đóng và drain kênh nội bộ, bật guard `enforce`, rồi CLI plan/bind. Nếu chưa có
kênh đó thì dừng; không mở public nginx.

Guard P0 hiện không có quy trình tự phục vụ để thay device đã bind. Không mở ingress
rồi thử login thiết bị thay thế: guard sẽ từ chối theo thiết kế. Replacement phải dùng
một runbook/admin tool riêng đã review, thu hồi credential cũ và có rollback; nếu chưa
có công cụ đó thì dừng, không sửa SQL binding thủ công.

## Tiêu chí chốt

- bốn version login/register và prefix tương ứng public trả `404` cho GET/POST;
- Admin API public trả `404`; Admin API localhost chỉ dành cho quản trị nội bộ;
- `/versions`, sync, media, nhắn tin, file, LiveKit và background của session hiện hữu
  hoạt động;
- guard vẫn `enforce` trên mọi login worker;
- backup ID, snapshot ID, UTC và kết quả smoke test được lưu ngoài Git;
- không có secret/token/MXID nhạy cảm trong log triển khai.
