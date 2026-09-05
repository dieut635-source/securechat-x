# Báo cáo sẵn sàng production — SecureChat Android

**Ngày đánh giá:** 2026-08-30

**Phiên bản source:** 26.08.3

**Mô hình phân phối:** APK cài trực tiếp từ máy tính; không phát hành Google Play hoặc kho ứng dụng

**Kết luận hiện tại:** **NO-GO — CHƯA SẴN SÀNG TRIỂN KHAI PRODUCTION**

## 1. Kết luận điều hành

Nhánh hiện tại đã được harden đáng kể: thương hiệu Android hiển thị là SecureChat, ứng dụng khóa vào
`https://chat.securechat.com.au`, logic đọc managed configuration được áp dụng fail-closed, bề mặt deep link,
OAuth, WebView cuộc gọi, thông báo, clipboard, chia sẻ file, auto logout, network trust và signing
đã được siết chặt. Kiểm tra cấu hình runtime đã đạt ở lần chạy hoàn chỉnh gần nhất; phải chạy lại sau
bản vá FileProvider đang chờ và trên chính commit cuối trước khi coi đó là bằng chứng release.

Tuy nhiên, chưa được phép coi đây là bản production. Các release gate bắt buộc vẫn thiếu bằng chứng:
khóa ký production và certificate pin độc lập, metadata checksum dependency đã review, kết quả quét
CVE/SBOM hoàn chỉnh cho cả JVM, native Rust/AAR và web bundle, full test/lint/build trên đúng commit
cuối, Paparazzi khớp toàn bộ baseline đã duyệt, APK production được ký và kiểm tra hậu ký, smoke test
trên thiết bị thật, pentest độc lập và bằng chứng hardening máy chủ. Ngoài ra, website/homeserver
public vẫn lộ branding và liên kết phân phối upstream.

Git LFS không còn là nguyên nhân chặn hiện tại: 3.170 snapshot UI đã được hydrate và đối chiếu SHA-256
với object ID trong `HEAD`, không còn pointer hoặc object sai. Gate ảnh vẫn đỏ vì 2.841/3.170 test
Paparazzi phát hiện sai khác ảnh thật. Không được ghi đè hàng loạt baseline rồi tự coi là đạt khi chưa
review nguyên nhân và ảnh kết quả theo từng nhóm giao diện.

Mục tiêu “bảo mật 1000%” không phải cam kết kỹ thuật có thể chứng minh. Không có ứng dụng nào an
toàn tuyệt đối. Tiêu chuẩn phù hợp ở đây là: mọi gate có bằng chứng lặp lại được, không còn P0/P1
chưa xử lý hoặc chưa có chấp thuận rủi ro bằng văn bản, và pentest độc lập không còn phát hiện mức
nghiêm trọng/cao chưa đóng.

## 2. Phạm vi và cách hiểu mức độ

- **P0 — chặn phát hành:** thiếu điều kiện làm cho artifact không thể được tin cậy hoặc vi phạm trực
  tiếp yêu cầu branding/phân phối. Còn một P0 là kết luận NO-GO.
- **P1 — rủi ro cao trước triển khai:** có thể dẫn đến lộ dữ liệu, giả mạo luồng xác thực, sai chính
  sách hoặc vận hành không an toàn. Phải đóng hoặc có quyết định chấp thuận rủi ro cụ thể trước khi
  triển khai.
- **P2 — cải thiện quan trọng:** nợ kỹ thuật, khả năng tương thích hoặc hiệu năng; không mặc nhiên là
  lỗ hổng khai thác được nhưng phải có owner và thời hạn xử lý.

Báo cáo phân biệt ba lớp: source Android trong repository, artifact APK cuối cùng, và hạ tầng public
`chat.securechat.com.au`. Việc source pass không chứng minh APK ký cuối hoặc máy chủ an toàn.

## 3. P0 — các điều kiện đang chặn production

### P0.1. Chưa có ceremony ký production hoàn chỉnh

Chưa được cung cấp khóa ký production, alias, certificate SHA-256 pin, OpenPGP release-tag signer
fingerprint lấy từ hồ sơ độc lập, signed tag trùng phiên bản và máy ký ngoại tuyến. Task kiểm tra
signing đã được thử theo hướng âm tính và từ chối đúng khi thiếu input; đây là hành vi fail-closed,
không phải một bản release thành công.

Điều kiện đóng:

- Tạo khóa RSA tối thiểu 3072 bit trên máy ký ngoại tuyến, certificate còn hạn ít nhất một năm.
- Giữ private key ngoài repository/CI/cloud; lưu ít nhất hai backup mã hóa ở hai vị trí vật lý.
- Ghim chính xác SHA-256 certificate từ hồ sơ độc lập.
- Tạo signed annotated tag `v26.08.3` trên đúng commit đã review; `git verify-tag --raw` phải trả đúng
  một `VALIDSIG` khớp primary/signing fingerprint được duyệt và lưu bên ngoài repository.
- Chạy ceremony bằng `tools/release/build_securechat_offline.sh`, rồi xác minh signer, package,
  version, zip alignment, v2/v3 và checksum trên máy cài độc lập.

Các keystore debug/nightly đang được track là khóa công khai/phát triển và tuyệt đối không được dùng
để phân phối. Release gate đã được thiết kế để từ chối các signer này và không fallback sang debug.

### P0.2. Thiếu Gradle dependency verification metadata đã review

Repository chưa có `gradle/verification-metadata.xml` chứa checksum SHA-256 đã bootstrap từ môi
trường sạch và review độc lập. Script release và source-gate CI cố ý từ chối chạy khi thiếu file này.

Điều kiện đóng:

1. Bootstrap metadata trên một môi trường sạch, không dùng cache không rõ nguồn.
2. Đối chiếu nguồn và checksum artifact/plugin.
3. Review độc lập thay đổi metadata, commit vào đúng source release.
4. Chứng minh build ngoại tuyến chỉ dùng các byte đã ghim.

Không được tạo metadata chỉ để làm gate “xanh” mà không review artifact.

### P0.3. Chưa có vulnerability scan đạt gate

Invocation OWASP Dependency-Check đã được sửa để chỉ chạy task gốc
`:dependencyCheckAggregate` theo chế độ tuần tự (`--no-parallel --no-configure-on-demand`). Dry-run
đã xác nhận không còn gọi task trùng ở từng subproject. Tuy vậy, lần quét online thất bại fail-closed
khi cập nhật dữ liệu NVD/CISA KEV nhận HTTP 403 và máy không có database local dùng được; lần quét
offline vì thế cũng không thể hoàn tất. Tại thời điểm chốt báo cáo chưa có report đầy đủ chứng minh
mức CVSS gate `0.0` đã đạt.

Điều kiện đóng: cập nhật database qua quy trình được kiểm soát, lưu bằng chứng nguồn/thời điểm dữ
liệu, chạy scan trên đúng commit cuối, review mọi finding và đính kèm report vào hồ sơ phát hành.
OWASP Dependency-Check cho dependency JVM không bao phủ đầy đủ crate/native library Rust, AAR nhúng
hoặc JavaScript/web bundle; hồ sơ phát hành còn phải có SBOM và advisory/CVE scan riêng cho các lớp
này, kèm version/commit của từng scanner và quyết định xử lý finding.

### P0.4. Full unit/screenshot gate chưa đạt trên đúng commit cuối

Gate tích hợp toàn repository đã đạt `ktlintCheck`, `detekt`, release lint, release source
compile/manifest, call verifier/tests, appnav tests và debug assembly. Suite
`./gradlew test -x compound -x mediaupload` cũng đạt với 6.764 actionable tasks; media-upload sau khi
hydrate fixture đạt 35/35. Đây là bằng chứng tốt cho phần không phụ thuộc baseline, nhưng không thay
thế `./gradlew test` đầy đủ và screenshot verification trên đúng commit cuối.

Lệnh ceremony production còn yêu cầu cả full unit test và screenshot verification:

```text
./gradlew test verifyPaparazziDebug detekt ktlintCheck \
  :app:lintFdroidRelease \
  :features:call:impl:verifySecureChatCallAssets \
  --offline --no-daemon --no-configuration-cache \
  -PallWarningsAsErrors=true
```

Sau khi baseline được review/cập nhật có kiểm soát phải chạy lại toàn bộ lệnh trên một source tree
sạch, cố định, không thay đổi sau khi test; sau đó build/sign bằng ceremony ngoại tuyến và kiểm tra
APK hậu ký. Không được dùng artifact cũ còn lại trong bất kỳ thư mục `build/` nào.

### P0.5. Paparazzi còn 2.841 sai khác ảnh thật

Git LFS đã được khôi phục từ binary được kiểm soát. Cả 3.170 snapshot UI đã hydrate; SHA-256 của từng
file khớp object ID được track trong `HEAD`, không còn file thiếu, pointer chưa thay hoặc object sai.
Media-upload đạt 35/35 sau khi hydrate fixture. Vì vậy không được tiếp tục quy lỗi gate ảnh cho LFS.

22 baseline Compound cũ đã được record lại sau thay đổi có chủ đích sang font Inter/semantic theme,
review trực quan ở light/dark/high-contrast/RTL/typography/icons/colors, rồi
`verifyRoborazziDebug` đạt hai lần (lần hai buộc rerun 281/281 tasks). Ngược lại, lần chạy đầy đủ
`:tests:uitests:verifyPaparazziDebug` có 3.170 test và 2.841 test thất bại do assertion mismatch thật
trên phạm vi lớn. Chưa có bằng chứng rằng tất cả sai khác đều là thay đổi branding/theme mong muốn.

Điều kiện đóng: phân loại nguyên nhân theo màn hình và cấu hình, chỉ record baseline sau khi review;
kiểm tra trực quan có phân tầng tối thiểu cho login/chat/settings/call, light/dark, accessibility và
ngôn ngữ/RTL; chạy Paparazzi đạt ít nhất hai lần trên source tree sạch và xác nhận lại trong CI Linux
trên cùng commit. Script release vẫn phải chạy `git lfs fsck`, checkout LFS ngoại tuyến, từ chối mọi
tracked pointer còn sót và chạy screenshot verification; việc LFS đã đúng không làm gate Paparazzi
tự động đạt.

### P0.6. Bề mặt web public vẫn vi phạm yêu cầu bỏ branding upstream

Audit HTTP chỉ đọc ngày 2026-08-30 cho thấy:

- Trang gốc có title SecureChat nhưng vẫn chứa câu `Sorry, Element requires JavaScript...` và đường
  dẫn `/vector-icons/...`.
- `/manifest.json` vẫn dùng `/vector-icons/...`; `related_applications` còn liên kết Google Play,
  F-Droid và iTunes cho package/ID upstream như `im.vector.app`.
- Các URL website, privacy, acceptable use, policy, copyright và help trong app hiện cùng trỏ về
  trang gốc, chưa phải các trang chính sách/help riêng đã được audit.

Điều này trực tiếp trái yêu cầu sản phẩm độc lập, không còn branding Element/Vector và không quảng bá
app store. Đây là thay đổi phía máy chủ/web, không thể đóng chỉ bằng pull request Android.

Điều kiện đóng: xóa toàn bộ câu/asset/metadata/link upstream khỏi web root và web manifest; cung cấp
các trang privacy, terms/acceptable-use, copyright và help riêng; audit lại bằng cả HTML response,
manifest, asset names và ảnh hiển thị trên trình duyệt.

### P0.7. Chưa có kiểm thử thiết bị thật và pentest độc lập

Không có emulator/điện thoại kết nối trong phiên đánh giá, nên chưa thể smoke-test APK cuối. Trước
triển khai phải kiểm tra trên ít nhất các model/phiên bản Android/WebView thuộc fleet thực tế:

- cài mới, update đúng signer và từ chối downgrade/signer khác;
- login, sync, gửi/nhận tin nhắn và khôi phục process;
- ẩn đăng ký và khóa homeserver;
- `allow_file_send=false` cho attachment, voice và Android share intent;
- auto logout khi app background, bị process kill, đổi policy và lỗi logout tạm thời;
- khóa PIN, screenshot/recents, notification khi khóa máy;
- nhận/trả lời cuộc gọi, Home → notification → PIN, replay notification, camera/microphone bị revoke;
- thiết bị có WebView cũ/mới và mất mạng/chứng chỉ lỗi.

Pentest độc lập phải bao phủ OAuth/deep link, WebView bridge, Matrix session storage, media, database,
IPC/exported component, TLS/proxy, backup/restore và backend. Không tự xem code review nội bộ là
thay thế cho pentest.

### P0.8. Chưa có bằng chứng hardening backend đủ để triển khai kín

Source app chỉ khóa client vào đúng homeserver; nó không chứng minh backend an toàn. Chưa có bằng
chứng quản trị về phiên bản Synapse/Matrix stack, patch level, TURN/MatrixRTC, secret rotation, rate
limit, MFA admin, log retention, backup mã hóa/restore drill, monitoring, incident response và
federation policy.

`/.well-known/matrix/server` hiện trả HTML của web app thay vì JSON Matrix hợp lệ;
`/_matrix/federation/v1/version` trả 404. Kết quả này không chứng minh port federation khác (ví dụ
8448) đã đóng. Nếu hệ thống cố ý không federation, cần cấu hình và kiểm tra chủ động ở mọi đường
mạng; nếu có federation, phải sửa discovery và audit federation.

## 4. P1 — rủi ro cao cần xử lý hoặc chấp thuận rõ ràng

### P1.1. Digital Asset Links sai; HTTPS App Link đang bị tắt fail-closed

`/.well-known/assetlinks.json` hiện chỉ liệt kê các package upstream/debug/nightly và chưa có
`com.securechat.app`. Android manifest đã gỡ HTTPS App Link để không tuyên bố verification sai.
OAuth tạm thời dùng callback chính xác `com.securechat://oauth/callback`, kiểm tra action, host/path,
query cardinality, `state` và PKCE; custom scheme vẫn có rủi ro ứng dụng khác chiếm scheme để gây từ
chối dịch vụ. Endpoint login public tại thời điểm audit chỉ quảng bá password và
application-service, nên OAuth đang không hoạt động trong flow quan sát được; điều này làm giảm khả
năng chạm vào callback hôm nay nhưng không loại bỏ P1 trong code/manifest nếu OAuth được bật sau này.

Chỉ bật lại App Link khi server đã xóa package upstream, thêm đúng package và SHA-256 của certificate
production, callback HTTPS đã audit, rồi xác minh trên APK production-signed bằng
`adb shell pm get-app-links com.securechat.app`.

### P1.2. Chính sách CSP phía web public chưa đạt

Web root dùng CSP meta rất rộng (`connect-src *`, `img-src *`, `media-src *`, `frame-src *`, cùng các
ngoại lệ script/style) và không quan sát được HTTP CSP header. `/call/` không có CSP header/meta trong
lần audit. Đây là bề mặt xử lý nội dung nhạy cảm/camera/microphone nên phải có CSP chặt, header bảo
mật nhất quán, allowlist origin tối thiểu và kiểm thử XSS/clickjacking. CSP này là vấn đề của web
public; bundle call nhúng trong Android đã có CSP/egress allowlist riêng và chặt hơn.

### P1.3. WebView cuộc gọi cũ hơn Chromium 119

Code Android đã giới hạn call WebView vào asset origin/path cục bộ, tắt file/content access, mixed
content, geolocation, popup và điều hướng ngoài allowlist; camera/mic và message bridge được kiểm tra
origin. Tuy nhiên, WebView cũ hơn Chromium 119/Huawei có thể cần legacy JavaScript bridge không có
origin enforcement đầy đủ ở cấp frame.

Fleet production phải bắt buộc System WebView đủ mới hoặc vô hiệu hóa call trên thiết bị không đạt.
Không được chỉ dựa vào `minSdk` để suy ra WebView engine đủ mới.

Egress của call WebView hiện chỉ cho origin cục bộ `https://appassets.androidplatform.net` và
`https://chat.securechat.com.au`. Nếu SFU/media service thực tế dùng host khác, request sẽ bị chặn
fail-closed và cuộc gọi có thể không hoạt động. Không được nới thành wildcard để sửa nhanh; phải giữ
SFU trên host đã duyệt hoặc bổ sung exact origin sau threat review và test thiết bị thật.

### P1.4. Chưa có SPKI pin và kế hoạch xoay pin

Release hiện cấm cleartext, chỉ tin Android system CA và không tin user-installed CA. Đây là baseline
tốt nhưng chưa có SPKI pin/backup pin cho `chat.securechat.com.au`. Không nên tự thêm một pin đơn lẻ:
phải có ít nhất primary/backup key, kế hoạch xoay, kênh khôi phục và kiểm thử certificate renewal.
Nếu tổ chức chọn không pin, phải chấp thuận rõ ràng rủi ro CA công cộng.

### P1.5. `allow_file_send` không phải DLP hoàn chỉnh

Policy được enforce ở attachment, voice message và share intent đi vào ứng dụng, nhưng người dùng
vẫn có thể nhận/lưu/chia sẻ media bằng các bề mặt hệ điều hành hoặc ứng dụng khác. Nếu dữ liệu mật là
yêu cầu cốt lõi, cần thêm MDM/DPC: cấm cross-profile share, kiểm soát Files/Downloads, IME,
accessibility, screenshot hệ thống, backup cloud và ứng dụng được phép cài.

### P1.6. Android developer verification cho phân phối ngoài Play

Không cần đưa SecureChat lên Play Store để cài bằng ADB. Tuy vậy, trước rollout toàn cầu trên thiết bị
Android được chứng nhận từ năm 2027, tổ chức phải xác minh danh tính và đăng ký
`com.securechat.app` cùng production signing key qua Android Developer Console dành cho ứng dụng chỉ
phân phối ngoài Play. Đây là đăng ký package/signing identity, không phải xuất bản app store.

### P1.7. Hỗ trợ Android API 24 làm rộng bề mặt fleet cũ

FOSS build hiện có `minSdk=24`. Khả năng cài trên Android cũ không đồng nghĩa thiết bị đó còn nhận bản
vá kernel/vendor/WebView hoặc đạt yêu cầu integrity. Nếu không có nhu cầu tương thích đã được phê
duyệt, cần nâng minimum OS; nếu vẫn giữ API 24, enrollment phải chặn mọi máy hết hỗ trợ hoặc không có
security patch hiện hành. Fleet production phải có bootloader khóa, Verified Boot ở trạng thái tin
cậy, không root và Android System WebView đạt minimum version do security owner phê duyệt. Đây là
P1 vì source app không thể tự bù cho kernel/firmware/WebView đã hết vá.

### P1.8. Cài APK bằng ADB không tự áp dụng managed configuration

`adb install` chỉ cài package; nó không đặt `homeserver_url`, `allow_registration`,
`allow_file_send` hoặc `auto_logout_minutes`. Muốn áp chính sách bắt buộc phải provision thiết bị qua
DPC/EMM ở chế độ Device Owner hoặc Profile Owner (hoặc nền tảng OEM như Knox) và gọi API managed
config/application restrictions cho `com.securechat.app`. Default trong APK chỉ là default ứng dụng,
không phải bằng chứng policy quản trị đã được cấp.

Trước rollout phải kiểm thử policy ban đầu, thay đổi khi app đang chạy, process death, reboot và
trạng thái `KEY_RESTRICTIONS_PENDING`. Trong lúc restrictions đang pending hoặc DPC trả dữ liệu lỗi,
chức năng phụ thuộc policy không được phép mở theo giá trị nới lỏng tạm thời. Hồ sơ mỗi thiết bị phải
ghi nhận DPC/profile, policy version và bằng chứng giá trị thực tế sau reboot.

### P1.9. FileProvider từng mở rộng toàn bộ cache; bản vá đang chờ xác minh cuối

Audit phát hiện main FileProvider từng ánh xạ toàn bộ cache và luồng `ACTION_SEND` exported có thể
nhận URI mang authority của chính SecureChat. Một ứng dụng độc hại có thể đoán URI nội bộ, lợi dụng
SecureChat như confused deputy để đọc/upload hoặc kích hoạt xóa file cache mà không có grant hợp lệ.

Bản vá đang được hoàn thiện theo hướng chỉ expose các thư mục mục đích cụ thể
`temp/camera/`, `temp/media/`, `temp/outgoing/` và `notification_sounds/`; notification provider chỉ
expose `temp/notif/`; inbound share từ cả hai authority nội bộ bị từ chối trước khi đọc MIME; chia sẻ
ra sao chép file vào vùng outgoing riêng; cleanup chỉ được phép với URI camera do app tạo. Finding
này vẫn **mở** cho tới khi unit test âm tính, merged manifest/release resource và kiểm tra APK cuối
chứng minh không còn broad root hoặc đường bypass.

### P1.10. Không có push từ xa là hạn chế chức năng phải chấp thuận

Firebase và UnifiedPush công cộng đã bị loại khỏi production để giảm bên thứ ba, nhưng hệ quả là app
không có kênh đánh thức từ xa đáng tin cậy. Tin nhắn và cuộc gọi đến có thể không phát thông báo cho
tới khi người dùng mở app hoặc hệ điều hành cho phép sync nền. Không được mô tả đây chỉ là hardening
mà bỏ qua mất chức năng. Tổ chức phải chấp thuận rõ giới hạn này, hoặc thiết kế push gateway riêng
do SecureChat kiểm soát và threat-review/pentest trước khi bật.

### P1.11. Phân phối thủ công chưa có cơ chế cập nhật khẩn cấp

Không có app store đồng nghĩa không có rollout/recall tự động. Trước production phải có inventory
gắn serial thiết bị với version/commit/certificate/checksum, SLA vá Critical/High, kênh báo động,
server-side minimum supported version hoặc cơ chế chặn client dễ bị tấn công, và runbook thu hồi/cập
nhật bắt buộc. Rollback phải được diễn tập cùng data migration; không dùng `adb install -d`, không
uninstall để né signer/version mismatch và không phân phối lại APK cũ chỉ vì nó còn chữ ký hợp lệ.

### P1.12 đã đóng trong source: restore route đăng nhập bị policy xóa

Trước khi sửa, saved navigation state có thể khôi phục route `LoggedIn` sau khi policy/session đã xóa
nó, dẫn đến màn hình trống. Appnav hiện kiểm tra lại security/session policy, loại route cũ và đưa
người dùng về flow hợp lệ thay vì render trạng thái không còn quyền. Bộ appnav test đạt 12/12. Đây là
P1 đã đóng ở source; vẫn phải smoke-test process death/restore trên thiết bị thật và APK cuối.

## 5. P2 — nợ kỹ thuật và hiệu năng

- `/_matrix/client/versions` chỉ quảng bá đến `v1.12`; cần quản trị viên cung cấp version/patch chính
  xác và kế hoạch nâng cấp. Không thể suy ra phiên bản server chỉ từ endpoint này.
- Endpoint HTTPS quan sát được chỉ thương lượng HTTP/1.1. Đây chủ yếu là vấn đề hiệu năng/kết nối,
  không tự nó chứng minh lỗ hổng.
- Gradle còn cảnh báo deprecation từ plugin/bên thứ ba (Detekt reporting API và AGP test fixtures),
  tạo rủi ro khi chuyển sang Gradle 10; chưa phải lỗi production hiện tại.
- FOSS release hiện không obfuscate để tránh phá reflection/native binding. Obfuscation không phải
  ranh giới bảo mật; chỉ bật sau regression đầy đủ.
- Manifest merge có thể cảnh báo về việc remove `WorkManagerInitializer` khi không thấy declaration
  khác. Cấu hình này là chủ đích vì app cung cấp WorkManager configuration; cần giữ test merged
  manifest để đảm bảo initializer không bị thêm lại ngoài ý muốn.

## 6. Những thay đổi bảo mật/branding đã thực hiện trong source

### Branding và cấu hình sản phẩm

- Tên ứng dụng/theme/splash/icon và chuỗi người dùng được chuyển sang SecureChat; application ID là
  `com.securechat.app`.
- Homeserver mặc định và duy nhất là `https://chat.securechat.com.au`; UI đổi server bị loại bỏ.
- Các URL runtime của upstream bị loại bỏ hoặc thay bằng domain SecureChat; permalink mới dùng
  `matrix:` thay vì sinh link qua `matrix.to`.
- Source vẫn giữ một số package/class/resource name và copyright/license upstream để tương thích và
  tuân thủ giấy phép. Các tên này không được phép xuất hiện trong UI, merged manifest, metadata APK
  hoặc endpoint runtime. Không được xóa copyright một cách cơ học.

### Managed configurations

| Key | Default | Hành vi đã kiểm tra trong code |
| --- | --- | --- |
| `homeserver_url` | `https://chat.securechat.com.au` | Chỉ chấp nhận URL tương đương canonical; domain khác bị bỏ qua/fail-closed. |
| `allow_registration` | `false` | Bị khóa `false`; MDM không thể bật lại đăng ký. |
| `allow_file_send` | `true` | Khi `false`, chặn attachment, voice message và Android share intent tại các boundary gửi. |
| `auto_logout_minutes` | `0` | `0` tắt; số dương tạo deadline bền vững qua process death, khóa UI trước cleanup và retry logout khi lỗi. |

Mặc dù default auto logout theo yêu cầu là `0`, profile MDM production nên đặt một giá trị dương phù
hợp với threat model. Giá trị `0` không đáp ứng mục tiêu thiết bị bỏ quên/mất cắp.

### Android runtime và dữ liệu nhạy cảm

- Mọi Activity release áp dụng `FLAG_SECURE`, tắt ảnh Recent Apps; application backup bị tắt.
- `PinUnlockActivity` được khai báo rõ `exported=false`. Release manifest chủ động loại
  WorkManager `DiagnosticsReceiver` và AndroidX `ProfileInstallReceiver`; checker kiểm tra lại các
  receiver này không xuất hiện trong merged manifest/AAPT dump cuối.
- PIN ứng dụng bắt buộc 6 số, cấm PIN yếu, không grace period và không chấp nhận weak biometric.
- Clipboard nhạy cảm được đánh dấu và tự xóa sau 30 giây nếu nội dung vẫn do SecureChat đặt.
- Loại quyền/capability tự cài APK và Android Auto; xóa asset cài APK cũ.
- Firebase, public UnifiedPush, analytics, crash upload và release trace/log nhạy cảm bị tắt khỏi
  production configuration.
- Notification FileProvider đã được thu hẹp về `temp/notif/`. Main FileProvider và các consumer URI
  đang được harden theo P1.9; chưa được coi là đóng trước khi test/merged artifact cuối đạt.
- Golden AAPT dump cũ đã bị loại vì có thể che drift manifest. Tool hiện phân tích APK được cung cấp
  và kiểm tra cả compiled XML network security/FileProvider thay vì so với artifact tĩnh lỗi thời.

### Network, OAuth, deep link và call

- Cleartext bị cấm; trust anchor release chỉ là CA hệ thống Android.
- URL mở ngoài chỉ cho HTTPS hợp lệ, không credential trong URL và không cổng tùy ý.
- OAuth callback được parse chính xác; yêu cầu một `state`, một kết quả và không fragment.
- HTTPS App Link sai được gỡ fail-closed; Matrix link sinh mới dùng scheme `matrix:`.
- Call WebView chỉ nạp bundle local qua origin/path cố định; chặn remote custom call URL và các WebView
  capability không cần thiết. CSP đặt `default-src 'none'`, dùng nonce cho inline script và cấm
  object/base/form; `connect-src` chỉ giữ self/data cùng HTTPS/WSS exact host
  `chat.securechat.com.au`. WebView request egress chỉ cho origin local
  `https://appassets.androidplatform.net` và host SecureChat; SFU ở host khác bị chặn fail-closed cho
  tới khi được review/allowlist.
- Activity call không exported, không xuất hiện recents và PiP bị tắt. Truy cập call UI dùng one-time
  in-memory token, bị revoke khi app background/khóa; native WebView bị ẩn và camera/mic request bị
  deny nếu trạng thái khóa thay đổi giữa chừng.
- Incoming call notification dùng `VISIBILITY_SECRET`; caller/avatar/text không được nạp khi khóa.
  Answer phải qua PIN và kiểm tra lại đúng active ringing event.
- Appnav không còn khôi phục saved `LoggedIn` route đã bị session/policy xóa; thay vào đó điều hướng
  về flow hợp lệ, tránh màn hình trống sau process restoration.

### Build, signing và supply chain

- Workflow release trên GitHub chỉ là source gate; không nhận production key và phải thất bại nếu
  sinh APK/AAB.
- GitHub Action refs liên quan đã được ghim bằng commit SHA.
- Release signing bắt buộc key/certificate/marker gắn commit; v1 bị tắt, v2/v3 bắt buộc.
- Script offline kiểm tra source sạch, signed tag, OpenPGP tag-signer fingerprint độc lập, JDK 21,
  Android Build Tools 37.0.0, metadata dependency, Git LFS, test/lint, key strength/expiry, signer,
  package/version, alignment, checksum và provenance. Artifact được hậu kiểm trong staging ẩn rồi
  publish nguyên tử; staging và raw APK build bị xóa trên mọi exit. Script cũng từ chối
  secret/endpoint analytics kế thừa từ shell hoặc `local.properties`.
- Trước test/build, script chạy `git lfs fsck`, checkout object LFS từ cache ngoại tuyến rồi quét mọi
  tracked LFS path; chỉ một pointer chưa hydrate cũng làm ceremony thất bại.
- Repository resolution đã được thu hẹp; JitPack chỉ được phép cho các artifact được nêu rõ.
- Các APK release cũ phát hiện trong workspace dùng debug signer RSA 1024 và không đại diện source
  hiện tại đã được chuyển khỏi workspace vào vùng quarantine tạm thời
  `/private/tmp/securechat-unsafe-release-quarantine-20260830`. Chúng tuyệt đối không được phân phối;
  quarantine tạm không phải artifact hoặc bằng chứng release production.

## 7. Bằng chứng kiểm tra hiện có

### Đã đạt

- `bash tools/check/check_securechat_configuration.sh` →
  `SecureChat runtime configuration audit passed.`
- `git diff --check` → đạt.
- `bash -n tools/release/build_securechat_offline.sh` → đạt.
- Kiểm thử synthetic release script đạt: parser `VALIDSIG` chấp nhận đúng primary/signing pin, từ
  chối mismatch/nhiều record/fingerprint lỗi; failure trap giữ nguyên exit lỗi và xóa staging, temp,
  raw signed APK; staged-tree verifier chấp nhận bộ hợp lệ và từ chối APK bị sửa byte. Static
  ordering xác nhận không ghi vào output cuối trước final tree check và rename.
- Nhóm unit test tập trung vào security/managed config/login/call/logout/lockscreen/OAuth/Matrix/share
  đã trả về `BUILD SUCCESSFUL` trong 4 phút 36 giây, với 4.071 actionable tasks. Các task gồm:

  ```text
  :app:testFdroidDebugUnitTest
  :appnav:testDebugUnitTest
  :features:logout:impl:testDebugUnitTest
  :features:call:impl:testDebugUnitTest
  :features:lockscreen:impl:testDebugUnitTest
  :libraries:oauth:impl:testDebugUnitTest
  :libraries:matrix:impl:testDebugUnitTest
  :libraries:androidutils:testDebugUnitTest
  :libraries:rustls-tls:testDebugUnitTest
  :features:share:impl:testDebugUnitTest
  :libraries:mdm:impl:testDebugUnitTest
  :features:login:impl:testDebugUnitTest
  :features:enterprise:impl-foss:testDebugUnitTest
  :libraries:mediaviewer:impl:testDebugUnitTest
  :features:preferences:impl:testDebugUnitTest
  ```

- Sau patch call cuối: unit test module call, compile debug/release, ktlint, merged release manifest,
  configuration audit và `git diff --check` đều đạt.
- Gate tích hợp toàn repository đạt trong 2 phút 07 giây với 11.389 actionable tasks, bao gồm
  `ktlintCheck`, `detekt`, `:app:lintFdroidRelease`, release source compile, merged release manifest,
  call asset verifier/tests, appnav tests và `:app:assembleFdroidDebug`.
- Suite không gồm hai nhóm từng phụ thuộc fixture/baseline
  `./gradlew test -x compound -x mediaupload` đạt với 6.764 actionable tasks.
- Sau khi hydrate LFS, media-upload đạt 35/35; toàn bộ 3.170 UI snapshot có byte SHA-256 khớp object
  ID trong `HEAD`, không thiếu file, không còn pointer và không có object mismatch.
- 22 Compound baseline được record lại sau thay đổi Inter/semantic theme, review trực quan cho
  light/dark/high-contrast/RTL/typography/icons/colors và `verifyRoborazziDebug` đạt hai lần; lần xác
  minh thứ hai buộc chạy lại đủ 281/281 tasks.
- Appnav test đạt 12/12 cho cả trường hợp restore saved `LoggedIn` route không còn hợp lệ.
- Konsist license-header test đã được sửa và đạt.
- XML source parse và YAML workflow parse đạt; workflow actions đã ghim SHA.
- Scan pattern không phát hiện private key/token production; tracked keystore chỉ thuộc debug/nightly.
- Kiểm tra âm tính signing đạt mục tiêu: release build bị chặn khi thiếu sáu input production.
- Kiểm tra âm tính script offline đạt mục tiêu: dừng sớm khi thiếu dependency verification metadata.

### Chưa đạt hoặc chưa có bằng chứng cuối

| Gate | Trạng thái |
| --- | --- |
| Full `./gradlew test` trên commit cuối | Chưa có lần chạy full đạt sau mọi thay đổi cuối; bản loại `compound`/`mediaupload` đạt 6.764 tasks và media-upload riêng đạt 35/35 |
| Git LFS integrity | 3.170 UI snapshot đã hydrate và SHA-256 khớp object ID trong `HEAD`; không còn pointer/missing/mismatch |
| Compound screenshot | 22 baseline đã review; verify đạt hai lần, gồm lần rerun 281/281 tasks |
| `:tests:uitests:verifyPaparazziDebug` | **Không đạt:** 3.170 test, 2.841 assertion mismatch thật; chưa được phép coi baseline mới là đúng |
| Full `detekt` + `ktlintCheck` | Đạt trong gate tích hợp |
| `:app:lintFdroidRelease` | Đạt trong gate tích hợp |
| Release source compile/manifest | Đạt trong gate tích hợp |
| `:features:call:impl:verifySecureChatCallAssets` và call tests | Đạt trong gate tích hợp |
| Appnav tests | Đạt 12/12 |
| `:app:assembleFdroidDebug` | Đạt; APK debug không được phân phối |
| `:dependencyCheckAggregate` có database cập nhật | Chưa đạt: NVD/CISA KEV HTTP 403 và không có database local |
| APK production ký ngoại tuyến | Chưa tạo, đúng theo fail-closed |
| Hậu kiểm APK bằng `apksigner`/`aapt`/`zipalign` | Không có APK production để kiểm tra |
| Smoke test thiết bị thật | Chưa thực hiện |
| Pentest độc lập | Chưa thực hiện/cung cấp |
| CI source gate trên PR/commit cuối | Phải xác minh sau khi push |
| FileProvider confused-deputy regression | Bản vá đang chờ unit test/merged manifest/APK verification cuối |

Mọi test phải chạy lại nếu source thay đổi dù chỉ một dòng sau lần test. Không có APK production nào
được tạo hoặc được phép phân phối từ các kết quả trên; `:app:assembleFdroidDebug` chỉ sinh artifact
debug phục vụ kiểm tra.

## 8. Kết quả audit máy chủ public ngày 2026-08-30

### Quan sát tích cực

- `/.well-known/matrix/client` trả JSON 200 và trỏ homeserver về đúng domain.
- MatrixRTC focus quan sát được cũng ở `chat.securechat.com.au/livekit/jwt`.
- HSTS một năm kèm `includeSubDomains`, `X-Content-Type-Options: nosniff`, frame deny và
  no-referrer được quan sát ở well-known client.
- `/config.json` dùng brand/default server SecureChat, tắt custom URL và guest, mobile builds là
  `null`, call dùng cùng domain.
- `/_matrix/client/v3/login` chỉ quảng bá password và application-service flow.
- TLS qua trust store hệ điều hành được `curl` xác minh thành công; certificate có SAN đúng domain và
  còn hạn tại ngày audit. Một lần `openssl s_client` dùng trust store cục bộ không dựng được chain
  đến ISRG X2, nhưng không đủ bằng chứng để kết luận server chain hỏng vì system `curl` đã pass.

### Quan sát cần xử lý

- `/.well-known/matrix/server` sai định dạng như mô tả ở P0.8.
- `assetlinks.json` chỉ còn các package `im.vector.*`/`io.element.*`, không có
  `com.securechat.app`, như mô tả ở P1.1.
- Web root/manifest còn branding, icon và store link upstream như P0.6.
- CSP web root/call quá rộng hoặc thiếu như P1.2.
- GET `/_matrix/client/v3/register` trả 405 `M_UNRECOGNIZED`; kết quả này không chứng minh registration
  đã tắt. Không thực hiện POST phá trạng thái trong audit. Cần bằng chứng cấu hình admin và test trên
  staging cô lập.
- Không có bằng chứng rằng TURN không fallback public, federation port đã đóng, hoặc backup/restore
  và incident-response đã được diễn tập.

## 9. Quy trình build và phân phối được chấp nhận

### 9.1. Source gate

1. Merge đúng commit đã review vào nhánh mặc định; tree sạch; chạy `git lfs fsck`, checkout LFS ngoại
   tuyến và quét chắc chắn không còn tracked pointer.
2. Chạy workflow `SecureChat Production Source Gate` hoặc tương đương trên đúng revision.
3. Gate phải đạt full test, Paparazzi, detekt, ktlint, release lint, call assets, release source
   compile, dependency scan và bộ scan SBOM/CVE riêng cho Rust/AAR/web bundle; CI phải chứng minh
   không sinh APK/AAB.
4. Không đưa keystore hoặc password vào GitHub Secrets để “tiện” ký cloud.

### 9.2. Ceremony trên máy ký ngoại tuyến

Máy ký phải dùng JDK 21, Android Build Tools 37.0.0, cache Gradle/LFS đầy đủ đã kiểm chứng và ngắt
mạng vật lý. Sau khi tạo/xác minh signed tag, chạy:

```bash
tools/release/build_securechat_offline.sh \
  --keystore /secure/offline/securechat-release.keystore \
  --alias securechat \
  --cert-pin-file /secure/offline/securechat-release-cert.sha256 \
  --tag-signer-fingerprint-file /secure/offline/securechat-release-tag-signer.fingerprint
```

Password chỉ nhập ở terminal ẩn. Không truyền password qua command line, environment được log hoặc
file trong repository. Certificate pin và OpenPGP primary/signing-key fingerprint phải đến từ hồ sơ
độc lập bên ngoài checkout. Mọi file phân phối chỉ xuất hiện ở tên output cuối sau khi staging cùng
filesystem, metadata ký, checksum/provenance và source tree cuối đều được xác minh.

### 9.3. Xác minh độc lập trước cài

Trên máy cài, lấy certificate và OpenPGP tag-signer fingerprint từ hồ sơ/kênh độc lập, xác minh
metadata JAR đã ký, `SHA256SUMS`, provenance (gồm signer/primary fingerprint), commit/tag/version,
rồi kiểm tra APK:

```bash
apksigner verify --verbose --print-certs SecureChat-26.08.3-arm64-v8a.apk
aapt dump badging SecureChat-26.08.3-arm64-v8a.apk | head -n 3
adb devices -l
adb install --no-streaming -r SecureChat-26.08.3-arm64-v8a.apk
```

APK phải có `com.securechat.app`, đúng một signer, đúng fingerprint độc lập, v1 tắt và v2/v3 bật.
Không dùng `adb install -d`, không khắc phục signer mismatch bằng uninstall, và không gửi APK qua
chat/email cá nhân/file share công cộng/QR installer.

Sau cài đặt: smoke-test, ngắt USB, revoke debugging authorization và tắt USB debugging/Developer
options trừ khi chính sách vận hành có yêu cầu khác. Sau đó DPC/EMM Device Owner/Profile Owner hoặc
Knox phải cấp managed configuration, kiểm tra giá trị thực tế, khởi động lại máy, kill process và
kiểm tra lại policy trước khi bàn giao. Chỉ cài APK bằng ADB không hoàn thành bước này.

## 10. Checklist quyết định GO/NO-GO

Chỉ chuyển sang **GO** khi tất cả ô sau có bằng chứng gắn với cùng commit/tag/APK:

- [ ] Không còn P0; mọi P1 đã đóng hoặc có risk acceptance được CISO/product owner phê duyệt.
- [ ] Web root, web manifest, call web và policy/help pages không còn branding/link upstream.
- [ ] Backend hardening, federation/TURN, registration, backup/restore và patch level đã được ký xác
      nhận bởi admin hệ thống.
- [ ] `gradle/verification-metadata.xml` đã bootstrap và review độc lập.
- [ ] Git LFS fsck/checkout/pointer scan và screenshot verification đạt.
- [ ] Full test, lint, detekt, ktlint, release compile, call asset audit, JVM dependency scan và
      SBOM/CVE scan Rust/AAR/web bundle đạt trên commit không thay đổi.
- [ ] FileProvider scope, inbound own-authority URI rejection và cleanup regression test đạt trên
      source, merged manifest/resource và APK cuối.
- [ ] Pentest độc lập không còn finding Critical/High chưa xử lý.
- [ ] Production key, pin, backup, rotation và incident-response procedure đã sẵn sàng.
- [ ] Signed tag trùng version, trỏ chính xác vào commit được duyệt và `VALIDSIG` khớp OpenPGP
      primary/signing fingerprint lưu độc lập ngoài repository.
- [ ] Offline ceremony tạo artifact mới; provenance/checksum/signed metadata đã xác minh độc lập.
- [ ] Chỉ output nguyên tử sau toàn bộ hậu kiểm còn tồn tại; không còn staging hoặc raw signed APK
      trong `app/build/` sau cả success/failure drill.
- [ ] APK hậu ký có đúng package/version/signer/signature schemes và không có endpoint/secret cấm.
- [ ] Smoke test thiết bị thật đạt trên fleet đại diện, gồm WebView và call permission/lifecycle.
- [ ] Fleet enrollment chứng minh security patch còn hỗ trợ, bootloader khóa, Verified Boot tin cậy,
      không root và WebView đạt minimum version được phê duyệt.
- [ ] DPC/EMM/Knox cấp đúng managed configuration; startup/resume, đổi policy, process death, reboot và
      `KEY_RESTRICTIONS_PENDING` đều đã test fail-closed.
- [ ] Hồ sơ cài đặt ghi serial thiết bị, version, commit, fingerprint, checksum, người cài và thời điểm.
- [ ] Có inventory/version compliance, SLA cập nhật Critical/High, server-side minimum version,
      runbook forced update/recall và rollback/data-migration drill cho kênh sideload.
- [ ] Đã chấp thuận bằng văn bản việc không có remote push, hoặc push gateway riêng đã threat-review,
      test và pentest.
- [ ] `com.securechat.app` và production signing key được đăng ký theo Android developer verification
      trước thời hạn áp dụng cho fleet; không xuất bản lên app store.

Nếu bất kỳ ô nào chưa hoàn tất, quyết định mặc định vẫn là **NO-GO**.

## 11. Tài liệu vận hành liên quan

- `docs/security_model.md`: threat model, bất biến production và giới hạn kỹ thuật.
- `docs/install_from_github_release.md`: ceremony ký offline và xác minh/cài APK từ máy tính.
- `docs/continuous_integration.md`: contract của source gate và dependency scan.
- `.github/workflows/securechat-release.yml`: CI source-only, không ký và không tạo APK production.
- `tools/release/build_securechat_offline.sh`: release gate ngoại tuyến duy nhất được chấp nhận.
