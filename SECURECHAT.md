# SecureChat X — ghi chú fork

Fork của `element-hq/element-x-android`, rebrand thành **SecureChat** cho homeserver
`https://chat.securechat.com.au`. Kế hoạch chi tiết: `element-x/PLAN.md` trong repo dự án cha (SecureChat).

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
  Cần bản universal thì đổi tên file ở bước "Rename arm64 APK" trong workflow.
- Job thứ hai chạy unit test của hai module ta đã sửa (`features/login/impl`, `features/enterprise/impl-foss`).
- `-PallWarningsAsErrors=false` (upstream để `true`; ta nới ra để cảnh báo không làm đỏ CI).

Các workflow của upstream (build.yml, tests.yml, danger.yml, sonar.yml…) đã bị **disable** trong
tab Actions vì chúng cần secrets/submodule riêng của Element và sẽ luôn đỏ. File vẫn giữ nguyên
trong repo để merge upstream không bị xung đột.

## Đồng bộ với upstream

```bash
git fetch upstream
git merge upstream/develop
```

Remote `upstream` đã cấm push (`no_push`).

## Submodule `enterprise`

`.gitmodules` trỏ tới repo riêng tư `element-android-enterprise` của Element — **không clone được và
không cần**. Thư mục `enterprise/` rỗng nên Gradle bỏ qua, build chạy ở chế độ FOSS.

## Rebrand (đã làm)

| Việc | Ở đâu |
|---|---|
| Tên app, package `com.securechat.app`, OAuth scheme `com.securechat`, URL chính sách | `plugins/src/main/kotlin/config/BuildTimeConfig.kt` |
| Homeserver mặc định | `appconfig/.../AuthenticationConfig.kt` → `DEFAULT_HOMESERVER_URL` |
| Màu thương hiệu (accent xanh #1A73E8) | `features/enterprise/impl-foss/.../SecureChatColors.kt`, trả về từ `DefaultEnterpriseService` |
| Icon | `appicon/element/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml` (vector) + `mipmap-*/ic_launcher*.png` cho Android 7/7.1 |
| Chuỗi còn tên Element | `app/src/main/res/values/securechat_strings.xml` (ghi đè module thư viện) |
| Chỉ đóng gói tiếng Anh | `app/build.gradle.kts` → `localeFilters` |

Còn tên "Element" ở những chỗ **không hiển thị cho người dùng**: package Java `io.element.android.*`,
`namespace` của module app, tên style `Theme.ElementX`, tên thư mục `appicon/element`. Đổi những thứ này
tốn công và làm mọi lần merge upstream xung đột, trong khi người dùng không bao giờ thấy.
