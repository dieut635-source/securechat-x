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
- Artifact: **`app-debug.apk`** (bản universal, cài được cho mọi máy Android).
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
