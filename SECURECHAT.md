# SecureChat X — ghi chú fork

Fork của `element-hq/element-x-android`, rebrand thành **SecureChat** cho homeserver
`https://chat.securechat.com.au`.

## Nhánh

- `develop` — nhánh làm việc chính (giữ tên như upstream để dễ merge).
- `main` — để dành cho bản phát hành.

## CI

`.github/workflows/securechat-build.yml` — chạy khi push lên `main`/`develop`, hoặc bấm tay
(Actions → SecureChat APK Build → Run workflow).

- JDK 21, Gradle wrapper của repo.
- Build `:app:assembleFdroidDebug`; Firebase bị loại khỏi dependency graph. UnifiedPush được bật
  và trỏ vào dịch vụ ntfy của chính SecureChat.
  Đây là build kiểm thử, có application ID và nhãn `dbg` riêng, không thay thế được bản production.
- Artifact: **`app-debug.apk`** (bản **arm64-v8a**, ~99 MB — mọi điện thoại Android đời mới đều là arm64).
  Cần bản universal thì cập nhật các lệnh sao chép ở bước "Collect APKs" trong workflow.
- Job audit chạy `tools/check/check_securechat_configuration.sh` để chặn cấu hình thương hiệu,
  endpoint phân tích và Firebase cũ quay lại.
- Job test chạy các module đăng nhập, MDM, deeplink, tin nhắn, chia sẻ và enterprise đã sửa.
- Android lint chạy cho biến thể `fdroidDebug`.
- `-PallWarningsAsErrors=true` để cảnh báo Kotlin trong mã thay đổi làm đỏ CI thay vì bị bỏ qua.

Các workflow build, test, quality/lint, screenshot, LFS, dependency analysis, Maestro và Sonar dùng
chung đã được giữ lại và đổi cấu hình cho SecureChat. Sonar chỉ upload khi có `SONAR_TOKEN`.
Những workflow chỉ phục vụ công ty mẹ (private enterprise build, Danger bot, project triage,
post-release và đồng bộ Localazy của dự án cũ) đã bị xoá; workflow còn lại không cần private submodule.

## Đồng bộ với upstream

```bash
git fetch upstream
git merge upstream/develop
```

Remote `upstream` đã cấm push (`no_push`).

## Enterprise FOSS

Metadata và gitlink của private enterprise submodule đã bị loại bỏ. Bản build dùng implementation
FOSS ở `features/enterprise/impl-foss`; Gradle vẫn chạy bình thường khi không có thư mục `enterprise/`.

## Rebrand (đã làm)

| Việc | Ở đâu |
|---|---|
| Tên app, package `com.securechat.app`, OAuth scheme `com.securechat`, URL chính sách | `plugins/src/main/kotlin/config/BuildTimeConfig.kt` |
| Deeplink nội bộ `securechat://` | `libraries/deeplink/impl`, `AndroidManifest.xml` |
| Homeserver mặc định | `appconfig/.../AuthenticationConfig.kt` → `DEFAULT_HOMESERVER_URL` |
| Màu thương hiệu (accent xanh #1A73E8) | `features/enterprise/impl-foss/.../SecureChatColors.kt`, trả về từ `DefaultEnterpriseService` |
| Icon | module `appicon/element` (tên module kỹ thuật giữ lại), tài nguyên launcher SecureChat |
| Chuỗi hiển thị | tài nguyên ứng dụng và module đã đổi sang SecureChat; overlay cuối ở `app/src/main/res/values/securechat_strings.xml` |
| Chỉ đóng gói tiếng Anh | `app/build.gradle.kts` → `localeFilters` |
| Link "Learn more" | `appconfig/.../LearnMoreConfig.kt` → domain SecureChat |
| Tắt gửi log lỗi từ xa | `DefaultEnterpriseService.bugReportUrlFlow` → `Disabled` |
| URL chính sách, tên app trong bug report | `appconfig/build.gradle.kts` |
| Store listing và changelog | `fastlane/metadata/android/en-US` |

Còn tên "Element" ở những chỗ **không hiển thị cho người dùng**: package Java `io.element.android.*`,
`namespace` của module app và tên thư mục `appicon/element`. Đổi những thứ này
tốn công và làm mọi lần merge upstream xung đột, trong khi người dùng không bao giờ thấy.

## Quyền riêng tư và dịch vụ bên ngoài

- PostHog và Sentry chỉ được biên dịch khi có endpoint/khoá do SecureChat cấu hình. Mặc định cả hai tắt.
- Không có endpoint upload bug report mặc định.
- Firebase vẫn bị loại khỏi build: nó sẽ đưa metadata thông báo qua hạ tầng Google.
- UnifiedPush được bật, trỏ vào ntfy tự host tại `push.securechat.com.au`. Gateway dự phòng trong
  `UnifiedPushConfig` cố ý giữ một host `.invalid` không định tuyến được: nếu ntfy ngừng quảng bá
  Matrix gateway, push phải hỏng thẳng chứ không được âm thầm rơi sang gateway công cộng
  `matrix.gateway.unifiedpush.org` như Element X gốc. Đừng đổi giá trị đó thành URL thật.
- `RustPushersService` đăng ký pusher với `PushFormat.EVENT_ID_ONLY`, nên Synapse chỉ gửi `event_id`,
  `room_id` và số tin chưa đọc — không tên người gửi, không nội dung. Kể cả khi máy chủ ntfy bị chiếm,
  kẻ tấn công chỉ biết có tin đến vào lúc nào.
- Cần app distributor riêng (ntfy) trên máy: `UnifiedPushDistributorProvider` loại trừ chính ứng dụng.
- Còn hở: ntfy đang cho anonymous đọc/ghi, bảo vệ duy nhất là tên topic ngẫu nhiên. Phải siết bằng
  xác thực ntfy và token cấp qua Knox trước khi lên production.
- Các app-link web cũ đã được bỏ khỏi manifest. Scheme chuẩn `matrix:` vẫn được hỗ trợ để tương tác
  với hệ sinh thái Matrix.

## Managed Configurations (Knox Manage)

Bốn khoá, khai báo trong `app/src/main/res/xml/app_restrictions.xml`, đọc bởi `libraries/mdm`:

| Khoá | Kiểu | Mặc định | Không cấu hình thì | Cấu hình rồi thì |
|---|---|---|---|---|
| `homeserver_url` | string | `https://chat.securechat.com.au` | đăng nhập vào server SecureChat | chỉ chấp nhận URL SecureChat tương đương; giá trị khác bị bỏ qua |
| `allow_registration` | bool | `false` | chỉ có "Sign in" | khoá tương thích; kể cả `true` cũng không thể bật tạo tài khoản |
| `allow_file_send` | bool | `true` | gửi file bình thường | `false` → ẩn nút đính kèm, ẩn ghi âm, chặn chia sẻ file từ app khác, chặn dán ảnh từ bàn phím |
| `auto_logout_minutes` | integer | `0` | không tự đăng xuất | `>0` → đăng xuất khi app ở nền quá N phút, kể cả khi tiến trình bị kill |

**Tên khoá là hợp đồng với Knox** — đổi tên không làm build lỗi, nó chỉ âm thầm đưa thiết lập đó về
mặc định trên mọi máy đã triển khai. Đừng đổi.

Parser (`MdmConfigParser`) cố tình dễ dãi với `allow_file_send` và `auto_logout_minutes`: console MDM
hay đẩy sai kiểu (checkbox thành chuỗi `"true"`, số thành `" 30 "`). Giá trị không hiểu được thì rơi
về mặc định của **riêng khoá đó**. Hai khoá nhận dạng nhạy cảm là ngoại lệ fail-closed:
`homeserver_url` không thể chuyển khỏi SecureChat và `allow_registration` không thể bật đăng ký.

Thử không cần Knox: cài **TestDPC** (Google) lên máy/emulator, mục "Managed configurations" → chọn
SecureChat → chỉnh bốn khoá.

## Ký bản phát hành

Bản production chỉ được ký trên máy release cô lập mạng. Production keystore, mật khẩu và bản
base64 của keystore **không được đưa vào GitHub Secrets, CI, cloud, repository hoặc máy lập trình
hằng ngày**. GitHub workflow `securechat-release.yml` chỉ kiểm tra đúng source revision; nó không
nhận khóa và không tạo APK/AAB.

Gradle đã được cấu hình fail-closed: task tạo/cài release yêu cầu đủ thông tin ký, đúng certificate
pin và marker gắn với commit do script offline tạo; keystore phải là file bên ngoài repository và
không được là debug/nightly key. Không còn đường fallback về debug key. Shell/`local.properties` có
MapTiler, Sentry, PostHog hoặc Rageshake cũng bị từ chối. Chữ ký APK production chỉ dùng scheme v2/v3;
v1 bị tắt vì `minSdk` đã là API 24.

### Nghi thức tạo khóa lần đầu

Trên máy offline, dùng `keytool` của JDK 21 để sinh khóa RSA 4096-bit bên ngoài repository. Không
đưa mật khẩu vào command line; `keytool` sẽ hỏi trực tiếp:

```bash
keytool -genkeypair -v \
  -keystore /secure/offline/securechat-release.keystore \
  -storetype PKCS12 \
  -alias securechat \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
chmod 600 /secure/offline/securechat-release.keystore
```

Xuất certificate công khai và ghi vân tay SHA-256 ra một file/giấy độc lập. Lệnh này vẫn hỏi mật khẩu
tương tác; vân tay là public nhưng bản pin phải được giữ qua kênh khác với gói APK:

```bash
keytool -exportcert \
  -keystore /secure/offline/securechat-release.keystore \
  -alias securechat \
  | openssl dgst -sha256
```

Chép đúng 64 ký tự hex vào `/secure/offline/securechat-release-cert.sha256`. Giữ hai bản sao keystore
được mã hóa ở hai vị trí vật lý tách biệt; giữ pin certificate và mật khẩu trong hệ thống quản lý bí
mật riêng. Kiểm tra phục hồi bản sao trước lần phát hành đầu tiên.

### Mỗi lần phát hành

1. Source phải sạch và commit được duyệt phải có signed annotated tag đúng phiên bản, ví dụ:

```bash
git tag -s v26.08.3 -m "SecureChat 26.08.3"
git verify-tag v26.08.3
```

2. Chạy **SecureChat Production Source Gate** trên đúng commit/tag. Gate phải xanh toàn bộ: LFS,
   configuration audit, full unit test, screenshot verification, Detekt, Ktlint, dependency
   vulnerability scan, release lint và release-source compilation. CI không tạo artifact phát hành.

3. Bootstrap `gradle/verification-metadata.xml` bằng SHA-256 trong một môi trường sạch, đối chiếu và
   review độc lập mọi artifact rồi commit metadata cùng source. Không tạo metadata bằng cách mặc
   nhiên tin cache cũ. Script offline cố ý từ chối phát hành nếu file này chưa tồn tại hoặc không có
   checksum SHA-256.

4. Đồng bộ đúng commit cùng toàn bộ LFS/dependency cache sang máy release offline. Cache OWASP/NVD
   mới, đã được duyệt từ source-gate của chính commit này, cũng phải được chuẩn bị trước. Chế độ
   offline tắt mọi updater/remote analyzer và dùng đúng cache đã chuyển vào; không được tái sử dụng cơ
   sở dữ liệu lỗ hổng cũ không rõ nguồn gốc.

5. Khi mạng của máy release đã bị ngắt vật lý, chạy:

```bash
tools/release/build_securechat_offline.sh \
  --keystore /secure/offline/securechat-release.keystore \
  --alias securechat \
  --cert-pin-file /secure/offline/securechat-release-cert.sha256
```

Script hỏi hai mật khẩu bằng input ẩn, yêu cầu đúng Android Build Tools khai báo trong `Versions.kt`,
chạy lại toàn bộ gate với Gradle `--offline --no-daemon`, build `fdroidRelease`, xác minh **từng** APK
(pin certificate, v2/v3, không v1/debug, zip alignment,
application ID/version), rồi sinh `SHA256SUMS` và provenance. Hai metadata file còn được đóng vào
JAR ký bởi chính production key để máy cài đặt xác thực độc lập.

6. Cài từ máy tính theo `docs/install_from_github_release.md`; không tải APK production lên GitHub,
   email, chat, Diawi hoặc file-sharing công cộng.

⚠️ **Mất keystore = không thể cập nhật app.** Không tạo khóa mới để “thay thế”: Android sẽ từ chối
update, và gỡ app để cài lại sẽ xóa local encrypted state. Nếu nghi khóa bị lộ, dừng phân phối ngay,
cô lập thiết bị và thực hiện quy trình ứng phó sự cố riêng.


## Mã PIN cưỡng bức

Người dùng đặt hai mã lúc thiết lập khoá màn hình. Mã thường mở khoá bình thường.
Mã cưỡng bức **cũng mở khoá bình thường** — không báo lỗi, không cảnh báo, không màn
hình khác — nhưng xoá sạch dữ liệu trước đó. Bất cứ dấu hiệu nào lộ ra khác biệt sẽ
mách người đang ép mở máy rằng còn thứ để ép tiếp.

Hai mã bắt buộc khác nhau **ít nhất 2 chữ số**. Với mã 4 số, lệch một chữ số có 36
mã lân cận; nếu mã cưỡng bức rơi vào đó thì một lần bấm nhầm là mất sạch, không xác
nhận, không hoàn tác.

### Phạm vi xoá — đọc kỹ

Xoá **chỉ trên thiết bị đó**, và **chỉ cục bộ**:

| | |
|---|---|
| Xoá | tin nhắn, kho khoá mã hoá, media, cache ảnh, bản ghi phiên của MỌI tài khoản trên máy |
| KHÔNG xoá | tài khoản trên máy chủ |
| KHÔNG đụng | tin nhắn trên máy chủ và trên các thiết bị khác |
| KHÔNG gửi | bất cứ thứ gì lên máy chủ |

### Lỗ hổng đã biết: token vẫn còn hiệu lực

Vì không gọi gì lên máy chủ, **access token của thiết bị đó vẫn sống** sau khi xoá.
Kẻ đã trích được token TRƯỚC đó vẫn dùng tiếp được.

Cố ý không vá trong app. Muốn vô hiệu token thì phải giữ lại token, xoá xong rồi gọi
`/logout` — tức nhét một lời gọi mạng vào đúng đường đi cưỡng bức, nơi mọi thứ phải
nhanh và không được treo. Máy đang offline thì lời gọi đó vô dụng, mà lại thêm một
chỗ hỏng.

**Cách xử lý đúng là ở vận hành:** khi biết có sự cố, quản trị viên xoá thiết bị đó
qua Synapse admin API. Việc này vô hiệu token ngay và đồng thời kích hoạt luôn cơ chế
xoá từ xa (xem `SecureChatRemoteWipe`) nếu máy còn online. Phải nằm trong quy trình
ứng phó sự cố.

### Giới hạn khác

- Chỉ bảo vệ khi máy **chưa bị mở**. Máy đang mở khoá và đưa cho người khác thì mã
  cưỡng bức không giúp gì.
- Không giấu được khỏi người đọc mã nguồn: repo công khai theo AGPL. Bảo vệ nằm ở chỗ
  kẻ ép không biết mã nào là mã nào, không phải ở chỗ giấu cơ chế.
- Sau khi xoá, app ra màn hình đăng nhập — trông như máy chưa từng đăng nhập.
