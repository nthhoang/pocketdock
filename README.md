# PocketDock

**Sử dụng Java, Kotlin và WebRTC để xây dựng hệ thống PocketDock kết nối thiết bị di động thành thiết bị ngoại vi không dây cho máy tính theo mô hình P2P**

Đồ án cơ sở 4, Trường Đại học Công nghệ thông tin và Truyền thông Việt - Hàn (VKU). Giảng viên hướng dẫn: ThS. Nguyễn Thanh Cẩm. Nhóm thực hiện: Nguyễn Hữu Quốc Bảo, Nguyễn Tam Huy Hoàng.

## Đồ án làm gì

Nhiều khi ta có sẵn một chiếc điện thoại hoặc máy tính bảng nhưng không dùng đến. PocketDock cho phép dùng chúng như các thiết bị đi kèm của máy tính Windows:

- **Màn hình phụ:** hiện lại hình màn hình máy tính trên điện thoại, chạm vào màn hình điện thoại để điều khiển con trỏ chuột.
- **Loa:** phát âm thanh của máy tính ra điện thoại.
- **Webcam:** dùng camera của điện thoại làm webcam cho máy tính.
- **Micro:** dùng micro của điện thoại làm micro cho máy tính.

Máy tính mở ứng dụng và hiện một mã QR. Điện thoại quét mã bằng trình duyệt (Safari hoặc Chrome), người dùng trên máy tính chọn "Cho phép", sau đó hình và tiếng được truyền trực tiếp giữa hai thiết bị bằng WebRTC (mô hình P2P). Máy chủ chỉ giúp hai bên tìm thấy nhau lúc bắt đầu.

Để đưa webcam và micro của điện thoại vào các ứng dụng họp như Zoom hoặc Teams, đồ án dùng hai phần mềm có sẵn là OBS Virtual Camera và VB-Cable, không tự viết trình điều khiển.

## Phạm vi

Có làm: máy tính Windows 10/11; iPhone, iPad (iOS 16.4 trở lên) và điện thoại Android qua trình duyệt; cùng mạng Wi-Fi (có máy chủ TURN dự phòng); mỗi lần một điện thoại; bốn chức năng nêu trên.

Không làm: màn hình mở rộng thật, trình điều khiển ảo tự viết, macOS, Linux (Ubuntu chỉ dùng để lập trình), nhiều thiết bị cùng lúc.

## Tình hình hiện tại

Đang làm theo lộ trình các mốc M0 đến M7, thử nghiệm W1 đến W3 trên Windows, rồi viết báo cáo.

Đã xong và đã chạy thử trên Ubuntu với iPhone: ghép nối bằng mã QR, truyền hình thử và tiếng thử, dùng webcam và micro của điện thoại, giữ phòng 90 giây để nối lại, lưu lịch sử vào cơ sở dữ liệu, màn hình kiểm tra hệ thống.

Đã viết xong nhưng **chưa thử trên máy Windows thật** (chờ đợt mượn máy W2): chụp màn hình thật, điều khiển chuột bằng chạm, lấy âm thanh của máy tính, đưa tiếng micro vào VB-Cable. Trên Ubuntu các phần này tự tắt và dùng hình thử, tiếng bíp để vẫn chạy được.

Để dành khi triển khai công khai: đóng gói bằng Docker, thuê máy chủ và tên miền riêng, máy chủ TURN tự dựng.

## Các thành phần

```text
pocketdock/
├── server/           Máy chủ Kotlin (Ktor): tạo phiên, kiểm tra mã, chuyển tin giữa hai bên
├── web/              Trang web mở trên điện thoại (HTML, CSS, JavaScript)
├── desktop/          Ứng dụng máy tính (Java, JavaFX, webrtc-java)
│   └── audio-helper/ Chương trình phụ C# lấy âm thanh máy tính, và bản giả để thử trên Ubuntu
├── w1/               Các chương trình thử nhỏ chạy trên Windows (đợt thử W1)
├── docs/             Tài liệu (giao thức tin nhắn)
└── .github/          Quy trình dựng bản chạy trên Windows
```

## Công nghệ

- Máy tính: JDK 21, JavaFX 21, webrtc-java 0.20.0, ZXing (tạo mã QR), SQLite, Gson.
- Máy chủ: Kotlin 2.3, Ktor 3.5 (Netty, WebSocket).
- Trang web: HTML, CSS, JavaScript thuần, không dùng framework.
- Windows: chương trình phụ C# (.NET Framework 4.8, NAudio) để lấy âm thanh; đóng gói bằng jpackage trên GitHub Actions.

## Chạy thử trên máy phát triển

Cần JDK 21 và cloudflared (để điện thoại truy cập được qua HTTPS). Mở ba cửa sổ dòng lệnh:

```bash
# 1. Máy chủ
cd server && ./gradlew run

# 2. Đường hầm HTTPS cho điện thoại
cloudflared tunnel --url http://localhost:8081

# 3. Ứng dụng máy tính (dán địa chỉ trycloudflare.com của bước 2)
cd desktop && POCKETDOCK_PUBLIC_URL=https://xxxx.trycloudflare.com ./gradlew run
```

Quét mã QR bằng điện thoại cùng mạng Wi-Fi, rồi chọn "Cho phép" trên máy tính. Địa chỉ trycloudflare.com đổi sau mỗi lần chạy. Sau khi sửa mã máy chủ phải khởi động lại máy chủ và quét mã QR mới.

Chạy kiểm thử: `cd server && ./gradlew test` và `cd desktop && ./gradlew test`.

## Kiểm tra máy có đủ điều kiện chạy không

Trong ứng dụng có màn hình "Kiểm tra hệ thống", kèm nút "Xuất báo cáo lỗi" để lưu một file chữ gửi cho người hỗ trợ. Có thể chạy kiểm tra mà không mở giao diện:

```bash
PocketDock.exe --selftest        # bản đóng gói trên Windows
./gradlew run --args="--selftest"   # khi đang phát triển
```

Kết quả được in ra màn hình và ghi vào `ketqua/selftest.txt`.

## Bản chạy trên Windows

GitHub Actions dựng bản chạy cho Windows ở mỗi lần đẩy mã lên (xem tab Actions). Tải gói `PocketDock-windows`, giải nén rồi chạy `PocketDock/PocketDock.exe`. Bản này chưa được ký số nên Windows có thể hiện cảnh báo SmartScreen.

Để chạy gọn trên một máy Windows, bấm đúp `Chay-PocketDock.bat` (hoặc chạy `PocketDock.exe --auto`). Ứng dụng tự bật máy chủ đi kèm và đường hầm HTTPS cloudflared, lấy địa chỉ cho điện thoại rồi hiện mã QR; đóng ứng dụng thì cả hai cùng tắt. Máy cần có Internet, và lần đầu chương trình tự tải cloudflared. Khi phát triển trên Ubuntu cũng dùng được: chạy `cd server && ./gradlew installDist` một lần, rồi `cd desktop && ./gradlew run --args="--auto"` (cần cài sẵn cloudflared).

## Biến môi trường

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `PORT` | Cổng của máy chủ | 8081 |
| `TOKEN_TTL_SECONDS` | Thời gian mã QR còn hiệu lực (giây) | 120 |
| `STUN_URLS` | Danh sách máy chủ STUN | STUN của Google |
| `WEB_DIR` | Thư mục trang web mà máy chủ phát | `../web` |
| `POCKETDOCK_SERVER` | Địa chỉ máy chủ mà ứng dụng máy tính nối tới | `http://localhost:8081` |
| `POCKETDOCK_PUBLIC_URL` | Địa chỉ điện thoại sẽ mở (phải là HTTPS) | giống `POCKETDOCK_SERVER` |
| `POCKETDOCK_DB` | File cơ sở dữ liệu SQLite | `~/.pocketdock/pocketdock.db` |
| `POCKETDOCK_SCREEN` | `real` ép chụp màn hình thật, `test` ép dùng hình thử | tự chọn theo hệ điều hành |
| `POCKETDOCK_MOUSE` | `real` ép điều khiển chuột thật, `off` ép tắt | tự chọn theo hệ điều hành |
| `POCKETDOCK_AUDIO` | `real` ép lấy âm thanh máy tính, `test` ép dùng tiếng bíp | tự chọn theo hệ điều hành |
| `POCKETDOCK_AUDIO_HELPER` | Đường dẫn tới chương trình phụ lấy âm thanh | tự tìm |
| `POCKETDOCK_MIC_ROUTE` | `real` ép đưa tiếng micro vào VB-Cable, `off` ép tắt | tự chọn theo hệ điều hành |
| `POCKETDOCK_MIC_OUT` | Đoạn tên thiết bị phát nhận tiếng micro | `CABLE Input` |
| `POCKETDOCK_AUTO` | `1` giống thêm `--auto`: tự bật máy chủ và đường hầm | tắt |
| `POCKETDOCK_SERVER_HOME` | Thư mục máy chủ đã build, dùng cho `--auto` | tự tìm |
| `POCKETDOCK_CLOUDFLARED` | Đường dẫn cloudflared, dùng cho `--auto` | tự tìm hoặc tự tải (Windows) |

"Tự chọn theo hệ điều hành" nghĩa là bật trên Windows và tắt trên hệ điều hành khác. Chất lượng chụp màn hình (720p hoặc 1080p) chọn trong tab Cài đặt của ứng dụng.

## Bảo mật khi ghép nối

- Mã phòng 72 bit và mã xác thực 128 bit, mã chỉ dùng một lần và hết hạn sau 2 phút.
- Nhập sai quá 5 lần trong 1 phút thì bên đó bị khóa tạm thời.
- Điện thoại chỉ được nối khi người dùng trên máy tính bấm "Cho phép".
- Điện thoại bị rớt mạng trong 90 giây thì nối lại được bằng mã tiếp tục dùng một lần mà không phải hỏi lại. Quá 90 giây phải quét mã QR mới.

Chi tiết tin nhắn xem [docs/giao-thuc.md](docs/giao-thuc.md).

## Hạn chế đã biết

- Khóa màn hình hoặc chuyển trình duyệt sang nền lâu trên iPhone có thể làm mất kết nối, vì trình duyệt bị hệ điều hành tạm dừng. Hiện giữ phòng 90 giây để nối lại khi trang còn sống.
- Webcam và micro đưa vào ứng dụng họp phải qua OBS Virtual Camera và VB-Cable.
- Chương trình điều khiển chuột (Robot của Java) không điều khiển được cửa sổ đang chạy với quyền quản trị.
- Chưa có màn hình mở rộng thật, chỉ nhân bản màn hình.
- Bộ cài chưa được ký số.
- Linux (Wayland) chưa hỗ trợ chụp màn hình và điều khiển chuột.
