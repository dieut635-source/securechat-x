# Mô hình bảo mật và phân phối kín SecureChat

SecureChat là ứng dụng Matrix dành cho thiết bị được quản trị, chỉ cài APK trực tiếp từ máy tính.
Ứng dụng không được phát hành qua Google Play, kho ứng dụng Android hoặc website tải công khai.

Không có hệ thống nào an toàn tuyệt đối. Mục tiêu của tài liệu này là định nghĩa các điều kiện có thể
kiểm chứng để một bản SecureChat được coi là đủ điều kiện triển khai.

## Ranh giới tin cậy

- Thiết bị phải dùng Android còn được nhà sản xuất cập nhật, có security patch hiện hành, bootloader
  khóa, Verified Boot ở trạng thái tin cậy và không root. Enrollment phải từ chối thiết bị không đạt;
  app không thể tự bù cho kernel/firmware đã hết vá.
- Android System WebView phải đạt minimum version do security owner phê duyệt và được kiểm tra trong
  inventory. Engine cũ hơn Chromium 119/Huawei có thể đi qua legacy bridge yếu hơn; production phải
  yêu cầu WebView/OEM mới còn được vá hoặc tắt call, không chỉ dựa vào `minSdk`.
- Máy build/sign phải do SecureChat kiểm soát. Production signing key không được đưa lên GitHub,
  GitHub Actions, kho mã nguồn, dịch vụ lưu trữ đám mây hoặc máy của người dùng.
- Máy chủ duy nhất được phép đăng nhập là `https://chat.securechat.com.au`.
- DNS, reverse proxy, homeserver, MatrixRTC/TURN và mọi push gateway nếu có phải do SecureChat
  kiểm soát. Không dùng analytics, crash upload, bản đồ, push gateway hoặc CDN của bên thứ ba.
- HTTPS chỉ tin các CA hệ thống của Android. CA do người dùng hoặc MDM cài không được tự động tin
  trong bản release.

## Bất biến của bản production

Một APK production phải thỏa tất cả điều kiện sau:

1. Build từ Git commit sạch, đã review và có tag trùng với version trong ứng dụng.
2. Build type là `fdroidRelease`; không phải debug, nightly hoặc gplay.
3. Gradle thất bại ngay khi thiếu release keystore; không được fallback sang debug key.
4. APK được ký bằng đúng chứng chỉ release đã ghim và không có signer thứ hai ngoài dự kiến.
5. File checksum SHA-256 được tạo trên máy ký và chuyển tới người cài qua kênh độc lập.
6. Không chứa Firebase, UnifiedPush gateway công cộng, PostHog, Sentry hoặc endpoint upload log.
7. Không cho cleartext HTTP, không tin user-installed CA và không có quyền tự cài APK.
8. Homeserver và registration policy bị khóa trong mã; cấu hình quản trị không được chuyển ứng dụng
   tới một domain khác.
9. Full unit tests, Android lint, ktlint, detekt, dependency vulnerability scan (không chấp nhận CVE
   đã biết có điểm CVSS) và kiểm tra manifest đều đạt trên đúng commit phát hành. OWASP scan JVM
   phải được bổ sung bằng SBOM/advisory scan riêng cho crate/native Rust, AAR nhúng và web bundle.
10. APK cuối cùng được kiểm tra lại sau khi ký; không dùng lại artifact cũ trong thư mục `build/`.
11. Shell và `local.properties` không được cung cấp MapTiler, Sentry, PostHog hoặc Rageshake cho build
    production; mọi APK nằm trực tiếp trong `app/build/` đều là artifact trung gian không đáng tin.
12. Git LFS phải `fsck`, checkout từ cache ngoại tuyến và không còn tracked pointer trước khi test;
    screenshot baseline phải được review, không được record hàng loạt chỉ để làm gate xanh.
13. Merged manifest và compiled XML trong APK phải chứng minh component nội bộ không exported ngoài
    ý muốn, release diagnostic/profile receiver đã bị loại và FileProvider chỉ expose subtree cần
    thiết cho từng use case.
14. Signed release tag phải được kiểm tra bằng `git verify-tag --raw`; đúng một bản ghi GnuPG
    `VALIDSIG` phải khớp primary key hoặc signing subkey fingerprint đã duyệt và lưu độc lập ngoài
    repository. Fingerprint đã ghim và fingerprint thực tế phải có trong provenance được ký.
15. Mọi file phân phối phải được tạo trong staging ẩn cùng filesystem với output, chỉ publish bằng
    rename sau toàn bộ hậu kiểm. Failure/signal phải xóa staging; APK ký thô trong `app/build/` phải
    bị xóa ở cả đường thành công và thất bại.

## Biện pháp bảo vệ trong ứng dụng

- Tên hiển thị, application ID và homeserver production lần lượt là `SecureChat`,
  `com.securechat.app` và `https://chat.securechat.com.au`. Cấu hình MDM không được phép bật đăng ký
  hoặc chuyển sang homeserver khác.
- Bản release bật `FLAG_SECURE` cho mọi Activity, không tạo ảnh trong Recent Apps, yêu cầu PIN ứng
  dụng 6 số, cấm PIN yếu, không chấp nhận weak biometric và không có grace period sau khi app mất
  foreground.
- Nội dung nhạy cảm sao chép từ ứng dụng được đánh dấu sensitive và tự xóa khỏi clipboard sau 30
  giây nếu vẫn là nội dung do SecureChat đặt.
- `allow_file_send=false` được kiểm tra lại tại các ranh giới gửi attachment, voice message và
  Android share intent. Đây là chính sách gửi ra, không phải DLP hoàn chỉnh: nó không ngăn người dùng
  lưu/chia sẻ media đã nhận sang ứng dụng khác.
- `auto_logout_minutes>0` dùng deadline bền vững. Khi deadline hết hạn hoặc homeserver policy đổi,
  điều hướng phiên bị khóa đồng bộ trước khi xóa session; lỗi SDK không được phép khôi phục lại màn
  hình đã đăng nhập.
- QR login bị vô hiệu ở lớp API khi homeserver bị khóa, tránh payload QR đưa một base URL khác qua
  kiểm tra tên server.
- URL mở ngoài chỉ chấp nhận HTTPS hợp lệ, không user-info và không cổng tùy ý. SecureChat không sinh
  permalink qua dịch vụ `matrix.to`; link mới dùng URI `matrix:`.
- WebView cuộc gọi chỉ nạp gói web đã nhúng từ origin/path cục bộ cố định, cấm file/content access,
  mixed content, geolocation, popup và điều hướng ngoài allowlist. Camera/microphone chỉ được cấp cho
  đúng origin cục bộ; cầu nối message cũng bị giới hạn origin.
- Notification cuộc gọi đến luôn có visibility `SECRET`; caller/avatar/text không được nạp khi app
  ở nền hoặc khóa. Activity cuộc gọi không hiển thị trên Android keyguard; thao tác Answer phải qua
  PIN, kiểm tra lại đúng active ringing event rồi mới mở call UI.
- Bản release không ghi trace ra file, không bật HTTP body logging, developer settings, Firebase,
  UnifiedPush công cộng, analytics hay crash upload.

## Managed configuration và quản trị thiết bị

Cài APK bằng `adb install` **không** tự áp managed configuration. Các default trong APK là giá trị
khởi tạo của ứng dụng, không phải bằng chứng rằng chính sách tổ chức đã được cấp. Production phải có
DPC/EMM ở chế độ Device Owner hoặc Profile Owner, hoặc giải pháp OEM được kiểm soát như Knox, đặt
application restrictions cho `com.securechat.app`:

| Key | Default trong APK | Yêu cầu vận hành |
| --- | --- | --- |
| `homeserver_url` | `https://chat.securechat.com.au` | Không được phép đổi sang domain khác. |
| `allow_registration` | `false` | Luôn fail-closed; DPC không được bật lại. |
| `allow_file_send` | `true` | Production phải quyết định rõ theo threat model/DLP. |
| `auto_logout_minutes` | `0` | Fleet bảo mật nên cấp số dương đã phê duyệt; `0` không chống thiết bị bị bỏ quên. |

App phải đọc restrictions lúc khởi động/resume, nghe thay đổi policy và không giữ giá trị nới lỏng
cũ sau process death/reboot. Trước rollout phải test provisioning lần đầu, đổi policy khi app đang
chạy, kill process, reboot, DPC trả bundle lỗi và `KEY_RESTRICTIONS_PENDING`. Khi restriction còn
pending, chức năng phụ thuộc policy không được mở theo default nới lỏng. Hồ sơ thiết bị phải lưu DPC,
profile/policy version và kết quả kiểm tra restrictions sau reboot.

## FileProvider và chia sẻ URI

Audit đã phát hiện cấu hình cũ expose toàn bộ cache qua main FileProvider. Kết hợp với Activity nhận
`ACTION_SEND`, một app độc hại có thể gửi URI mang authority của chính SecureChat để thử biến app
thành confused deputy đọc/upload hoặc xóa file nội bộ đoán được.

Thiết kế được chấp nhận chỉ expose các subtree mục đích cụ thể (`temp/camera/`, `temp/media/`,
`temp/outgoing/`, `notification_sounds/` và `temp/notif/` cho notification provider), từ chối inbound
URI dùng cả hai internal authority trước khi đọc MIME/content, sao chép file chia sẻ ra vào vùng
outgoing riêng, và chỉ cleanup URI camera do app tạo. Finding vẫn mở cho tới khi unit test âm tính,
merged manifest/compiled XML và APK cuối đều xác minh các bất biến này.

## Push và tính sẵn sàng thông báo

Firebase và UnifiedPush công cộng bị loại để tránh phụ thuộc bên thứ ba. Hệ quả là không có kênh
đánh thức từ xa đáng tin cậy: tin nhắn/cuộc gọi có thể im lặng cho tới khi app được mở hoặc Android
cho phép sync nền. Đây là giới hạn chức năng, không phải mức bảo mật miễn phí. Product/security owner
phải chấp thuận bằng văn bản, hoặc triển khai push gateway riêng do SecureChat kiểm soát sau threat
review, kiểm thử và pentest.

## Deep link và OAuth

HTTPS App Link hiện **bị gỡ khỏi manifest** theo nguyên tắc fail-closed. Tại ngày 2026-08-30,
`https://chat.securechat.com.au/.well-known/assetlinks.json` chưa ủy quyền `com.securechat.app` và
vẫn liệt kê các package upstream. Vì vậy không được mô tả endpoint này là verified App Link.

OAuth tạm dùng callback chính xác `com.securechat://oauth/callback`. Server public tại ngày audit chỉ
quảng bá password/application-service nên OAuth chưa hoạt động trong flow quan sát được. Parser yêu cầu `ACTION_VIEW`,
không fragment, đúng một `state` không rỗng và đúng một kết quả (`code` hoặc
`error=access_denied`); SDK kiểm tra state đã lưu và PKCE. PKCE bảo vệ authorization code nhưng một
ứng dụng khác vẫn có thể chiếm custom scheme để gây từ chối dịch vụ nếu OAuth được bật sau này; đây
vẫn là P1 dù flow hiện không dùng OAuth. Muốn loại bỏ rủi ro này phải:

1. Xóa mọi package upstream khỏi Digital Asset Links.
2. Thêm đúng `com.securechat.app` và SHA-256 của certificate production.
3. Triển khai, audit route callback HTTPS và OAuth metadata trên domain SecureChat.
4. Khôi phục intent-filter exact host/path với `autoVerify=true`, rồi xác minh APK production-signed
   bằng `adb shell pm get-app-links com.securechat.app` trên thiết bị thật.

## Giới hạn kỹ thuật đã biết

- FOSS build hiện có `minSdk=24`. Hỗ trợ cài đặt không chứng minh Android 7/vendor image đó còn an
  toàn. Nếu không nâng minimum OS, DPC enrollment bắt buộc phải chặn thiết bị hết hỗ trợ, patch cũ,
  bootloader mở, Verified Boot không tin cậy, root hoặc WebView dưới minimum đã phê duyệt.
- TLS release chỉ tin CA hệ thống Android và loại CA do người dùng/MDM cài. Chưa có SPKI pin cùng
  backup pin cho `chat.securechat.com.au`, vì certificate/key production và kế hoạch xoay khóa chưa
  được cung cấp. Không được tự tạo một pin giả hoặc chỉ có một pin không thể xoay.
- Gradle Wrapper có SHA-256 và mọi version khai báo hiện phải cố định, nhưng repository chưa có bộ
  `verification-metadata.xml` được bootstrap/review độc lập cho toàn bộ dependency. Cache offline
  của máy ký phải được tạo từ môi trường sạch, đối chiếu artifact và đóng băng trước release. Script
  ký offline cố ý từ chối chạy cho tới khi metadata SHA-256 đã được review và commit.
- Source vẫn giữ package/class/resource kỹ thuật và dòng copyright upstream để tương thích và tuân
  thủ giấy phép. Chúng không được phép xuất hiện trong UI, manifest cuối, metadata phát hành hoặc
  endpoint runtime.
- Obfuscation không phải ranh giới bảo mật. FOSS release hiện giữ tên bytecode để tránh làm hỏng
  reflection/native bindings; chỉ bật obfuscation sau một vòng regression và smoke test thiết bị
  đầy đủ.
- WebView phiên bản cũ hơn Chromium 119/Huawei vẫn cần bridge JavaScript legacy không có origin
  enforcement ở cấp frame. Fleet production phải bắt buộc Android System WebView mới, hoặc tắt tính
  năng call trên các thiết bị không đạt; audio routing, camera, microphone và PiP cần smoke test thật.

## Quản lý phiên bản và cập nhật khi không có app store

Phân phối thủ công không cung cấp rollout, auto-update hay recall. Trước lần triển khai đầu tiên phải
có inventory gắn serial/device owner với app version, commit, production certificate và checksum;
SLA vá Critical/High; kênh thông báo khẩn; và báo cáo compliance để biết máy nào chưa cập nhật.

Backend phải có minimum supported client version hoặc biện pháp tương đương để chặn client đã biết
dễ bị tấn công sau thời hạn bắt buộc. Runbook phải bao phủ forced update, nghi ngờ lộ signing key,
thu hồi thiết bị và hỗ trợ ngoại tuyến. Rollback chỉ được thực hiện theo procedure đã diễn tập cùng
data migration/recovery; không dùng `adb install -d`, không uninstall để né signer mismatch và không
coi chữ ký hợp lệ của một APK cũ là đủ để phân phối lại.

## Quy trình cài đặt

1. Chép APK và checksum từ máy ký sang máy cài bằng thiết bị lưu trữ được kiểm soát.
2. Trên máy cài, xác minh SHA-256, certificate fingerprint và OpenPGP tag-signer fingerprint trong
   provenance trước khi kết nối điện thoại.
3. Chỉ bật USB debugging trong thời gian cài đặt, xác nhận đúng serial thiết bị rồi dùng
   `adb install --no-streaming <apk>`.
4. Provision DPC/EMM/Knox đúng Device Owner/Profile Owner, cấp managed configuration và xác minh giá
   trị ứng dụng thực nhận; chỉ `adb install` chưa hoàn tất chính sách.
5. Mở ứng dụng và smoke-test đăng nhập, đồng bộ, nhắn tin, file policy, auto logout và cuộc gọi nếu
   tính năng đó được bật. Kill process, reboot và xác minh policy vẫn fail-closed, gồm trường hợp
   `KEY_RESTRICTIONS_PENDING` nếu DPC sử dụng trạng thái này.
6. Ghi inventory version/commit/certificate/checksum/policy theo serial thiết bị; sau đó tắt USB
   debugging và thu hồi quyền máy tính đã được Android cấp.

Không dùng `adb install -d`, vì tùy chọn đó cho phép hạ version. Android phải từ chối APK có version
code thấp hơn bản đang cài và APK không được ký bởi đúng release key.

Tài liệu này chưa xác nhận một APK production đã được tạo. Mọi artifact debug hoặc release cũ dùng
debug signer không được phép cài lên fleet production.

## Xác minh nhà phát triển Android cho phân phối ngoài Play

SecureChat không cần và không được đưa lên Google Play để cài bằng máy tính. Tuy nhiên, Android đang
triển khai cơ chế liên kết package/signing key với một nhà phát triển đã xác minh trên thiết bị được
Google chứng nhận. Theo tài liệu Android cập nhật tháng 8/2026, luồng cài bằng ADB vẫn giữ nguyên;
đợt áp dụng toàn cầu cho mọi ứng dụng trên thiết bị được chứng nhận bắt đầu từ năm 2027.

Trước khi triển khai diện rộng, tổ chức phải tạo tài khoản **Android Developer Console** dành cho
ứng dụng chỉ phân phối ngoài Google Play, xác minh tổ chức và đăng ký `com.securechat.app` cùng khóa
ký production. Đây là đăng ký danh tính/package, không phải phát hành lên app store. Không dùng tài
khoản "limited distribution" nếu số thiết bị vượt giới hạn của loại tài khoản đó.

Tài liệu chính thức:

- https://developer.android.com/developer-verification/guides
- https://developer.android.com/developer-verification/guides/android-developer-console
- https://developer.android.com/developer-verification/guides/faq

Tài liệu chính thức cho managed fleet và platform integrity:

- https://developer.android.com/work/managed-configurations
- https://developer.android.com/work/dpc/build-dpc
- https://developer.android.com/work/guide
- https://source.android.com/docs/security/features/verifiedboot/boot-flow
- https://source.android.com/docs/security/bulletin/asb-overview

## Quản lý khóa

- Tạo release key trên máy ký ngoại tuyến, dùng mật khẩu mạnh ngẫu nhiên và không truyền mật khẩu
  qua tham số dòng lệnh hoặc log CI.
- Giữ ít nhất hai bản sao lưu mã hóa ở hai vị trí vật lý tách biệt. Việc truy cập cần được ghi nhận.
- Lưu certificate fingerprint công khai cho người cài; giữ private key bí mật tuyệt đối.
- Duyệt và lưu OpenPGP primary/signing-key fingerprint dùng ký release tag trong hồ sơ ngoại tuyến
  độc lập với repository, release output và keyring trên máy ký; không coi “good signature” là đủ.
- Mất key đồng nghĩa không thể cập nhật ứng dụng hiện có. Nghi ngờ lộ key đồng nghĩa phải thu hồi
  bản cài và thiết lập lại thiết bị theo kế hoạch ứng phó sự cố.

## Những việc cần xác minh ngoài repository

- Federation phải bị tắt có chủ đích nếu hệ thống là mạng kín.
- MatrixRTC/TURN phải hoạt động và không fallback sang dịch vụ công cộng nếu cuộc gọi được bật.
- Máy chủ phải có log retention tối thiểu, rate limiting, MFA cho quản trị viên, backup mã hóa và
  quy trình vá lỗ hổng.
- Penetration test độc lập và kiểm thử thiết bị thật là bắt buộc trước lần triển khai đầu tiên và sau
  các thay đổi lớn về xác thực, mã hóa, media, deep link hoặc networking.
- Thiết bị triển khai phải có patch hiện hành, bootloader khóa, Verified Boot tin cậy, không root,
  WebView đạt minimum; đồng thời vô hiệu nguồn cài không tin cậy, IME/accessibility không được quản
  trị, USB debugging sau cài đặt và backup đám mây. Chính sách MDM nên đặt auto logout khác 0.
- Cần quyết định/risk acceptance cho việc không có remote push và diễn tập việc tin nhắn/cuộc gọi chỉ
  xuất hiện khi app được mở; không được phát hành với kỳ vọng thông báo realtime chưa kiểm chứng.
- Inventory, SLA vá, minimum client version phía server, forced-update/recall và rollback/data
  migration drill là control bắt buộc vì không có app store quản lý vòng đời.
- Không coi mục tiêu “1000%” là một cam kết kỹ thuật: không có ứng dụng nào an toàn tuyệt đối. Chỉ
  phát hành khi mọi gate có bằng chứng, mọi blocker bên ngoài đã đóng và pentest độc lập không còn
  phát hiện nghiêm trọng/chưa xử lý.
