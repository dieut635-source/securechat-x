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
- Build `:app:assembleFdroidDebug` (bản **fdroid** dùng UnifiedPush, **không** cần `google-services.json`).
  Khi có Firebase project cho `com.securechat.app` sẽ đổi sang `assembleGplayDebug`.
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
- Firebase bị loại khỏi build vì repo chưa có Firebase project và push gateway thuộc SecureChat.
  Bản F-Droid vẫn hỗ trợ UnifiedPush. Chỉ bật lại `PUSH_CONFIG_INCLUDE_FIREBASE` sau khi thay toàn bộ
  cấu hình placeholder trong module Firebase và triển khai push gateway riêng.
- Các app-link web cũ đã được bỏ khỏi manifest. Scheme chuẩn `matrix:` vẫn được hỗ trợ để tương tác
  với hệ sinh thái Matrix.

## Managed Configurations (Knox Manage)

Bốn khoá, khai báo trong `app/src/main/res/xml/app_restrictions.xml`, đọc bởi `libraries/mdm`:

| Khoá | Kiểu | Mặc định | Không cấu hình thì | Cấu hình rồi thì |
|---|---|---|---|---|
| `homeserver_url` | string | `https://chat.securechat.com.au` | đăng nhập vào server SecureChat | khoá vào đúng server đó, không đổi được |
| `allow_registration` | bool | `false` | chỉ có "Sign in" | `true` mới hiện "Create account" |
| `allow_file_send` | bool | `true` | gửi file bình thường | `false` → ẩn nút đính kèm, ẩn ghi âm, chặn chia sẻ file từ app khác, chặn dán ảnh từ bàn phím |
| `auto_logout_minutes` | integer | `0` | không tự đăng xuất | `>0` → đăng xuất khi app ở nền quá N phút, kể cả khi tiến trình bị kill |

**Tên khoá là hợp đồng với Knox** — đổi tên không làm build lỗi, nó chỉ âm thầm đưa thiết lập đó về
mặc định trên mọi máy đã triển khai. Đừng đổi.

Parser (`MdmConfigParser`) cố tình dễ dãi: console MDM hay đẩy sai kiểu (checkbox thành chuỗi `"true"`,
số thành `" 30 "`). Giá trị không hiểu được thì rơi về mặc định của **riêng khoá đó**, không làm hỏng
cả cấu hình — một máy từ chối đọc chính sách là máy quản trị viên không sửa được từ xa.
URL `http://` bị từ chối thẳng: gõ nhầm một chữ không đáng đổi lấy kết nối không mã hoá.

Thử không cần Knox: cài **TestDPC** (Google) lên máy/emulator, mục "Managed configurations" → chọn
SecureChat → chỉnh bốn khoá.

## Ký bản phát hành

Bản release của upstream ký bằng **khoá debug** — khoá đó nằm công khai trong repo, nghĩa là ai
cũng ký được bản cập nhật giả. Đã thay bằng cấu hình ký thật đọc từ biến môi trường.

Từ thư mục gốc của repo, dùng `keytool` đi kèm JDK 21 để sinh keystore **bên ngoài** repo:

```bash
keytool -genkeypair -v \
  -keystore ../securechat-release.keystore \
  -storetype PKCS12 \
  -alias securechat \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Mã hoá file để tạo giá trị secret mà không thêm keystore vào Git:

```bash
base64 < ../securechat-release.keystore | tr -d '\n'
```

Lấy vân tay chứng chỉ phát hành (giá trị `SHA256`) bằng:

```bash
keytool -list -v -keystore ../securechat-release.keystore -alias securechat
```

Rồi tạo 5 secret ở Settings → Secrets and variables → Actions: đặt kết quả base64 trên vào
`SECURECHAT_KEYSTORE_BASE64`, và tạo `SECURECHAT_KEYSTORE_PASSWORD`,
`SECURECHAT_KEY_ALIAS` (`securechat`), `SECURECHAT_KEY_PASSWORD` theo giá trị đã nhập khi sinh khoá,
và đặt vân tay chứng chỉ vào `SECURECHAT_RELEASE_CERT_SHA256` (có hoặc không có dấu `:` đều được).

Build: Actions → **SecureChat Release APK** → Run workflow (hoặc đẩy tag `v*`).

Workflow tự **xác minh chữ ký** sau khi build và **thất bại** nếu APK bị ký bằng khoá debug hoặc
không khớp chính xác vân tay `SECURECHAT_RELEASE_CERT_SHA256` đã ghim.

⚠️ **Mất keystore = không bao giờ cập nhật được app.** Người dùng phải gỡ cài và cài lại, mất
toàn bộ tin nhắn đã mã hoá trên máy. Sao lưu keystore và mật khẩu ở **hai nơi tách biệt**.
