# SecureChat single-device guard — P0

Module này chặn tạo phiên đăng nhập mới tại callback
`check_login_for_spam` của **Synapse 1.159.0**. Callback chỉ đọc binding và
không tự tạo/adopt binding. Đây là lớp kiểm soát P0 cho
chính sách “một tài khoản, một thiết bị”, chưa phải toàn bộ kiến trúc
“chỉ ứng dụng SecureChat mới truy cập được”.

Ở trạng thái vận hành bình thường, public ingress phải chặn hoàn toàn mọi biến
thể endpoint `/login` và `/register`. Guard luôn để `enforce` như
defense-in-depth. Không có rollback/cửa sổ bảo trì nào được mở các route này
công khai.

## Hành vi bảo mật

- Ở `enforce`, tài khoản chưa bind luôn bị từ chối bằng
  `SC_ENROLLMENT_REQUIRED`; tài khoản đã bind luôn bị từ chối bằng
  `SC_DEVICE_ALREADY_BOUND`. `device_id` do client chọn không tạo ngoại lệ.
- Ở `audit`, callback luôn allow và chỉ log trạng thái `SC_AUDIT_*`; callback
  không `INSERT`, `UPDATE` hay `DELETE` binding.
- Binding chỉ được ghi bằng CLI migration sau khi device, đúng một access token
  còn hiệu lực và public E2EE device key đã tồn tại. Việc kiểm tra hidden/NULL
  device, token mồ côi và E2EE key nằm trong CLI plan/bind.
- Thiết kế này loại race cũ nơi callback ghi binding trước khi Synapse hoàn tất
  `register_device`.
- Khi schema chưa sẵn sàng hoặc truy vấn cơ sở dữ liệu lỗi, chế độ `enforce`
  fail closed.
- Module không đọc và không tin `User-Agent`. IP, tên thiết bị và auth provider
  cũng không được dùng làm bằng chứng sở hữu thiết bị.
- Không có API self-service để thay device. Việc thay thế phải được thiết kế
  thành thủ tục chỉ quản trị viên mới được phép thực hiện ở giai đoạn sau.

Binding được lưu trong bảng riêng
`securechat_single_device_bindings_v1`. Cột `claim_id` là mã thao tác ngẫu nhiên
do CLI bind ghi; callback login không ghi bảng này.

## Cấu hình

Đặt thư mục này vào `PYTHONPATH` của **mọi process/worker phục vụ `/login`** và
thêm module vào `homeserver.yaml`.

Trước khi load module, áp dụng `schema/001_bindings_v1.sql` bằng một role DDL
ngắn hạn trong cửa sổ đã review, rồi thu hồi role đó. Runtime module chỉ chạy
`SELECT` readiness check; cả `audit` lẫn `enforce` không tự tạo schema và callback
không có bất kỳ câu ghi nào.

Chế độ audit, không chặn và không ghi binding:

```yaml
modules:
  - module: securechat_single_device.module.SecureChatSingleDeviceModule
    config:
      mode: audit
```

Chế độ enforce:

```yaml
modules:
  - module: securechat_single_device.module.SecureChatSingleDeviceModule
    config:
      mode: enforce
```

`mode` là bắt buộc. Giá trị sai hoặc khóa cấu hình không được nhận diện làm
Synapse từ chối khởi động, tránh triển khai nhầm do lỗi chính tả.

Quy trình triển khai an toàn:

1. Sao lưu và thử phục hồi cơ sở dữ liệu.
2. Chạy `audit`, xác nhận log `SC_AUDIT_*` và kiểm kê toàn bộ device/token cũ.
3. **Trước mọi thao tác revoke/plan/bind**, chặn mọi biến thể `/login` và
   `/register` tại public ingress, drain request đang chạy, đổi toàn bộ
   process/worker phục vụ login sang `enforce`, và xác minh không còn đường
   login chạy `audit`.
4. Với **tài khoản hiện hữu đang có device**, dùng quy trình migration bên dưới:
   chọn đúng một device cần giữ, chỉ thu hồi các device khác qua Synapse Admin
   API, rồi bind device được giữ bằng CLI. Không thu hồi credential của device
   được chọn và không yêu cầu tài khoản đăng nhập lại.
5. Sau migration, giữ public `/login` đóng ở steady state; guard vẫn chạy
   `enforce` để fail closed nếu routing bị cấu hình sai.
6. Enrollment mới chỉ được thực hiện qua kênh **nội bộ/restricted** đã review,
   trong lúc public `/login` và `/register` vẫn đóng. Guard `audit` có thể tạm
   allow đúng luồng nội bộ để Synapse tạo xong device/token và upload E2EE key;
   callback vẫn không bind. Đóng và drain kênh nội bộ, bật lại `enforce`, chạy
   plan rồi CLI bind. Gói P0 chưa tạo kênh này: nếu chưa có thì dừng.

### Provisioning tài khoản mới không tạo session ngoài guard

Không dùng `register_new_matrix_user` của Synapse 1.159.0 cho baseline này.
Script đó không gửi `inhibit_login` và Shared-Secret Registration API sẽ tạo
ngay device/access token qua `register_device`, không gọi
`check_login_for_spam`. Kết quả bypass toàn bộ precondition migration.

Tài khoản mới phải được tạo qua Shared-Secret Registration API **chỉ trên
localhost/mạng quản trị**, với body có `"inhibit_login": true` và
`"refresh_token": false`. Xác minh response không có `access_token`,
`refresh_token` hay `device_id`, và kiểm kê DB không có visible device hoặc
token cho user đó. Enrollment SecureChat chỉ chạy qua kênh nội bộ/restricted đã
review; public ingress tiếp tục trả 404 cho login, registration và toàn bộ
Synapse Admin API. Shared secret phải ở file bí mật, được luân chuyển và không
xuất hiện trong command line/report.

Không bật `enforce` rồi kỳ vọng callback tự di chuyển hoặc enroll phiên: tài
khoản chưa bind luôn nhận `SC_ENROLLMENT_REQUIRED`. CLI migration là đường duy
nhất trong gói P0 để ghi binding cho device đã tồn tại và đạt mọi precondition.

## Migration an toàn cho device đang đăng nhập

CLI PostgreSQL đi kèm cho phép giữ lại chính xác một device đã chọn. Mặc định
luôn là `plan` chỉ đọc. Công cụ không có lệnh xóa và không bao giờ đọc hoặc in
giá trị access token, refresh token, mật khẩu hay DSN.

Không được chạy migration trong khi `/login` còn hoạt động ở chế độ `audit`.
Một callback audit có thể đã cho request đi qua trước khi CLI lấy khóa bảng;
sau khi CLI commit, request đó có thể tiếp tục tạo thêm device/token. Khóa DB
của CLI không đóng được race này. Cờ bind `--confirm-logins-enforced` là xác
nhận vận hành bắt buộc, **không phải phép kiểm tra tự động**: quản trị viên phải
đảm bảo mọi đường login đã ở `enforce`, hoặc đã bị chặn và drain cho tới khi
`enforce` được bật.

DSN chỉ được nhận qua một trong hai biến môi trường sau, không có tham số DSN
trên command line. **Ưu tiên DSN file; biến DSN trực tiếp chỉ nên chứa tên
libpq service, không chứa mật khẩu**:

- `SECURECHAT_DATABASE_DSN_FILE`: lựa chọn khuyến nghị; file thường, quyền
  `0600`, không phải symlink, thuộc sở hữu effective UID của process, chứa DSN
  hoặc tên libpq service. Lưu file ngoài Git/worktree.
- `SECURECHAT_DATABASE_DSN`: phù hợp khi chỉ chứa tham chiếu như
  `service=securechat`; tránh đặt mật khẩu trực tiếp trong lịch sử shell.

Ví dụ plan mặc định:

```bash
export SECURECHAT_DATABASE_DSN_FILE=/run/secrets/securechat-postgres-dsn
python3 -m securechat_single_device.migrate_cli \
  --user-id '@test1:chat.securechat.com.au' \
  --device-id 'DEVICE_ID_DA_CHON'
```

JSON plan xác nhận user và device tồn tại, đồng thời chỉ hiển thị:

- device ID được chọn, `last_seen_ms`, trạng thái có E2EE device key và số lượng
  credential còn hiệu lực;
- `other_devices_to_revoke`;
- số access token không gắn device;
- credential còn hiệu lực gắn với device ID không còn trong danh sách;
- số hidden device khớp cross-signing key và ID của hidden row bất thường;
- ID của mọi device row có `hidden IS NULL` (luôn là blocker);
- binding hiện hữu, blockers và `can_bind`.

IP, User-Agent, display name, token và thông tin kết nối cơ sở dữ liệu không có
trong output. Dù vậy MXID và device ID vẫn là dữ liệu vận hành, nên không đăng
report công khai và phải lưu report ngoài Git/worktree.

Quy trình migration bắt buộc:

1. Xác minh mọi `/login` worker đã ở `enforce`; hoặc chặn `/login` tại ingress,
   drain request đang chạy và giữ endpoint đóng.
2. Chạy plan và đối chiếu `selected_device.device_id` trực tiếp với ứng dụng
   đang giữ lại. Device chỉ đủ điều kiện khi có đúng **một** access token còn
   hiệu lực, **không có** current refresh token và có row public device key
   trong `e2e_device_keys_json`. Trạng thái khác phải dùng fresh admin
   re-enrollment; không đoán hoặc tự adopt credential cần giữ.
3. Nếu có `other_devices_to_revoke`, dùng **Synapse Admin API trong một bước
   riêng** để thu hồi đúng các ID đó. Endpoint chuẩn là
   `POST /_synapse/admin/v2/users/<user_id>/delete_devices`. Không đưa admin
   access token vào URL, command line hoặc file report.
4. Không đưa device đã chọn vào yêu cầu revoke. Chạy lại plan và chỉ tiếp tục
   khi `can_bind: true`.
5. Bind bằng cách lặp lại chính xác cả user ID và device ID, đồng thời xác nhận
   điều kiện login đã được khóa bằng cờ bắt buộc:

```bash
python3 -m securechat_single_device.migrate_cli \
  --action bind \
  --user-id '@test1:chat.securechat.com.au' \
  --device-id 'DEVICE_ID_DA_CHON' \
  --confirm-user-id '@test1:chat.securechat.com.au' \
  --confirm-device-id 'DEVICE_ID_DA_CHON' \
  --confirm-logins-enforced
```

6. Giữ public `/login` và `/register` đóng vĩnh viễn sau khi toàn bộ tài khoản
   cần giữ đã bind và mọi login worker chạy `enforce`. Device được giữ tiếp tục
   dùng session hiện tại, không đăng nhập lại. Không dùng rollback để mở public
   enrollment; provisioning mới là phase nội bộ/restricted riêng.

Chế độ bind:

- chạy giao dịch `SERIALIZABLE`, giới hạn thời gian query/lock;
- khóa ngắn các bảng `users`, `devices`, `access_tokens`, `refresh_tokens`,
  `e2e_device_keys_json`, `e2e_cross_signing_keys` để ngăn user/device,
  credential hoặc metadata E2EE thay đổi giữa lúc kiểm tra và ghi binding;
- khóa row user được chọn;
- từ chối nếu còn device khác, token không gắn device, credential mồ côi,
  token quản trị mạo danh (`puppets_user_id`), binding cũ, user đặc biệt/bị
  khóa, hoặc device được chọn không có chính xác một access token người dùng
  thường còn hiệu lực;
- từ chối nếu còn **bất kỳ row refresh token nào** của user/device,
  kể cả predecessor đã rotate hoặc row hết hạn; baseline SecureChat không
  cho phép adoption khi tồn tại một bearer-token lineage có thể bị hiểu sai;
- từ chối nếu device được chọn chưa upload public E2EE device key;
- nhận diện hidden row tạo bởi cross-signing key là metadata nội bộ, báo warning
  nhưng không coi là session; hidden row không khớp cross-signing key là
  blocker fail-closed;
- từ chối mọi row `devices.hidden IS NULL` bằng
  `SC_MIGRATION_NULL_HIDDEN_DEVICE_PRESENT`;
- chỉ chạy một câu `INSERT ... ON CONFLICT DO NOTHING` vào
  `securechat_single_device_bindings_v1`;
- rollback toàn bộ khi có conflict hoặc lỗi.

Công cụ **không** `DELETE`/`UPDATE` các bảng `devices`, `access_tokens`,
`refresh_tokens`, E2EE keys hay pushers. Thu hồi phiên luôn phải đi qua Synapse
Admin API sau khi quản trị viên xác nhận mapping. Không chạy SQL xóa token trực
tiếp vì sẽ bỏ qua cleanup và cache invalidation của Synapse.

### Giả định vận hành của migration

- CLI migration chỉ hỗ trợ PostgreSQL và `psycopg2`, đúng dependency của
  Synapse 1.159.0. Module login vẫn dùng database abstraction của Synapse.
- Database dùng schema/search path tiêu chuẩn nơi Synapse và bảng guard cùng
  tồn tại.
- Schema có `users.name`; device người dùng là row `devices` với
  `hidden = FALSE`; `(user_id, device_id)` là duy nhất; token có thể mang
  `device_id = NULL`.
- Các cột được dùng đã được đối chiếu trực tiếp với source tag Synapse 1.159.0:
  `users.locked`, `users.suspended`, `access_tokens.valid_until_ms`,
  `access_tokens.puppets_user_id`, `refresh_tokens.expiry_ts`,
  `refresh_tokens.ultimate_session_expiry_ts` và
  `e2e_cross_signing_keys.keydata`.
- “Credential còn hiệu lực” được xác định từ `access_tokens.valid_until_ms` và
  refresh token cuối chuỗi chưa hết `expiry_ts`/`ultimate_session_expiry_ts`.
  Công cụ chỉ đếm row, không lấy cột `token`.
- Access token người dùng thường chỉ được đếm khi `user_id` khớp và
  `puppets_user_id IS NULL`. Mọi puppet token còn hiệu lực có target ở một trong
  hai phía (`user_id` là user đang migrate hoặc `puppets_user_id` trỏ tới user
  đó), đồng thời `puppets_user_id IS NOT NULL`, được đếm riêng trong trường
  `active_puppet_access_token_count` và là blocker
  `SC_MIGRATION_PUPPET_ACCESS_TOKEN_PRESENT`.
- Device được chọn phải có đúng một access token còn hiệu lực và không có
  current refresh token, đồng thời phải có row trong `e2e_device_keys_json`.
  Row này chỉ chứng minh server đã nhận một public device key; **không** chứng
  minh khóa nằm trên thiết bị vật lý, trong Android Keystore hay đến từ APK
  SecureChat. Nhiều token cùng `device_id` vẫn có thể là nhiều lần login độc
  lập vì ID do client chọn; trường hợp này phải fresh admin re-enrollment,
  không được bind/adopt. Nếu ứng dụng dùng cơ chế session ngoài schema chuẩn,
  dừng migration và review thay vì bỏ qua blocker.
- Trong cửa sổ bind, không tạo admin impersonation token hoặc application
  service token cho user đang migrate. Khóa bảng bảo vệ thay đổi device nhưng
  không biến bearer token thành credential ràng buộc phần cứng.
- Role DB phải là credential chuyên dụng, ngắn hạn. Với PostgreSQL, quyền
  `SELECT` đơn thuần **không đủ** cho `SHARE ROW EXCLUSIVE`/`FOR UPDATE`: role
  phải là owner/superuser hoặc có quyền `UPDATE`, `DELETE` hay `TRUNCATE` phù
  hợp trên các bảng được khóa, ngoài `SELECT` và `INSERT` vào bảng binding.
  Vì các quyền đó nguy hiểm hơn chính câu lệnh CLI, chỉ cấp trong cửa sổ bảo
  trì, kiểm tra chính xác trên staging, rồi thu hồi ngay; không giữ superuser
  hoặc DSN này lâu dài.

## Lỗi JSON cho client

Synapse trả HTTP 403 với `errcode: M_FORBIDDEN` và trường bổ sung ổn định:

```json
{
  "errcode": "M_FORBIDDEN",
  "error": "Login was blocked by the server",
  "com.securechat.single_device": {
    "version": 1,
    "code": "SC_DEVICE_ALREADY_BOUND",
    "retryable": false,
    "admin_action_required": true
  }
}
```

Các mã từ chối chính:

- `SC_ENROLLMENT_REQUIRED`
- `SC_DEVICE_ALREADY_BOUND`
- `SC_GUARD_NOT_READY`
- `SC_GUARD_DATABASE_ERROR`

Không mã lỗi nào chứa MXID, device ID, IP, User-Agent hoặc token.
`SC_GUARD_NOT_READY` và `SC_GUARD_DATABASE_ERROR` đặt `retryable: true` vì là lỗi
tạm thời; lỗi policy/device còn lại là `false` và thường cần quản trị viên xử lý.

Không bật logger `synapse.storage.SQL`/SQL DEBUG trong production. Synapse 1.159.0
có thể ghi cả SQL parameter values ở mức DEBUG, bao gồm MXID, device ID và claim ID.
Module cũng cố ý bỏ chi tiết exception DB khỏi log; chỉ ghi mode, decision code và
allowed status. Log homeserver phải được giới hạn quyền, mã hóa/rotate và không đưa
vào issue/PR công khai.

## Threat model và giới hạn bắt buộc phải hiểu

P0 này làm hẹp đường **tạo device qua `/login`**, nhưng chưa ràng buộc bearer
token với phần cứng:

- **Access-token replay chưa được giải quyết.** Kẻ lấy được access token hiện
  hữu vẫn có thể phát lại token từ client khác cho tới khi token bị thu hồi.
- Chặn public `/login` ngăn đăng nhập qua web ở steady state nhưng không chặn
  client bất kỳ gọi các Matrix API khác bằng bearer token đã có. Đây là lý do
  guard và ingress policy vẫn chưa chứng minh request đến từ APK SecureChat.
- Module không chứng minh request đến từ APK SecureChat. User-Agent hoặc header
  tĩnh đều giả mạo được và cố ý không được dùng. “App-only” thực sự cần khóa
  riêng theo thiết bị trong Android Keystore và xác thực mật mã trên mọi request
  (ví dụ mTLS hoặc reverse proxy ký/xác minh request).
- Device và access token đã tồn tại trước khi bật module không tự bị xóa hoặc
  vô hiệu hóa.
- Callback không chạy khi đăng ký user. Public registration vẫn phải tắt.
- Shared-Secret Registration API và `register_new_matrix_user` có thể tạo
  device/token mà không qua guard. Chỉ cho phép Admin API trên mạng quản trị và
  bắt buộc provisioning với `inhibit_login: true` như trên.
- Admin API, application service và các token không gắn device có thể đi ngoài
  luồng tạo device thông thường; chúng phải bị giới hạn mạng, quyền và audit
  riêng.
- Kẻ biết mật khẩu không thể giành binding qua callback, vì callback không ghi.
  Tuy vậy kênh enrollment nội bộ vẫn cần challenge/chữ ký phần cứng ở lớp kế tiếp.
- Module không thay đổi E2EE, cross-signing hoặc cơ chế khôi phục khóa. Không nên
  hạ các kiểm tra E2EE để né quy trình xác minh thiết bị.
- Sự tồn tại của public key trong `e2e_device_keys_json` không phải attestation
  APK hoặc phần cứng; client khác cũng có thể upload public key của nó.

Vì vậy không được mô tả riêng module này là “100% app-only” hoặc “chống sao chép
token”. Đây là guard P0 fail-closed, cần đi cùng lớp enrollment và
device-bound authentication.

## Kiểm thử cục bộ

Không cần Synapse hay thư viện ngoài:

```bash
cd ops/server/single-device
python3 -m unittest discover -s tests -v
```

Từ root repository, chạy thêm kiểm thử fail-closed của nginx/Synapse topology:

```bash
python3 -m unittest discover -s ops/server/tests -v
```

44 kiểm thử dùng `sqlite3` chuẩn và transaction/Module API fake, gồm: callback
chỉ SELECT, enforce deny tài khoản bound/unbound, audit allow nhưng không ghi,
plan migration, xác nhận lặp mapping,
DSN file chống symlink/sai owner, phân loại hidden cross-signing, không có thao
tác xóa, xác nhận login đã enforce/quiesce, chặn nhiều access token/refresh
token, thiếu E2EE key hoặc có `hidden=NULL` trên device được chọn, không lộ lỗi
rollback/close/adapter DB, semantics retryable, và conflict khi bind.

Đây là unit test, **chưa phải integration test PostgreSQL/Synapse thật**. Trước
production phải chạy plan/bind trên bản sao staging của database, đúng phiên
bản PostgreSQL và schema Synapse đang triển khai; xác minh lock timeout,
privilege của role, rollback và cleanup Admin API. Không lấy 44 test fake làm
bằng chứng rollout production đã đạt.

## Cơ sở tương thích

- [Callback `check_login_for_spam`](https://element-hq.github.io/synapse/latest/modules/spam_checker_callbacks.html#check_login_for_spam)
- [`ModuleApi.run_db_interaction` tại tag v1.159.0](https://github.com/element-hq/synapse/blob/v1.159.0/synapse/module_api/__init__.py)
- [Luồng login v1.159.0: callback chạy trước `register_device`](https://github.com/element-hq/synapse/blob/v1.159.0/synapse/rest/client/login.py)
- [PostgreSQL full schema của Synapse v1.159.0](https://github.com/element-hq/synapse/blob/v1.159.0/synapse/storage/schema/main/full_schemas/72/full.sql.postgres)
- [Synapse chỉ trả device người dùng có `hidden = FALSE`](https://github.com/element-hq/synapse/blob/v1.159.0/synapse/storage/databases/main/devices.py)
- [Quyền và xung đột của `LOCK TABLE` trên PostgreSQL 14](https://www.postgresql.org/docs/14/sql-lock.html)
