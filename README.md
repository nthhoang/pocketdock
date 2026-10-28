# PocketDock

PocketDock là một ứng dụng giúp kết nối điện thoại với máy tính trên cùng mạng LAN/Wi‑Fi hoặc qua Internet. Máy tính sẽ tạo ra một mã QR chứa thông tin phiên kết nối; điện thoại quét mã và xác nhận, sau đó hai thiết bị sẽ thiết lập kết nối trực tiếp qua WebRTC để trao đổi dữ liệu và điều khiển.

Dự án này bao gồm ba thành phần chính:
- `desktop/`: ứng dụng desktop (JavaFX) chạy trên máy tính, hiển thị QR, quản lý phiên làm việc và phê duyệt kết nối
- `server/`: máy chủ Ktor dùng để tạo phiên, xác thực token và làm trung tâm tín hiệu WebSocket
- `web/`: trang web chạy trên điện thoại, nhận thông tin từ QR và tham gia vào phiên kết nối

## Tính năng chính

- Tạo phiên kết nối nhanh chóng bằng mã QR
- Kết nối giữa máy tính và điện thoại trực tiếp qua WebRTC
- Hỗ trợ xác nhận thiết bị trước khi nối
- Tự động hết hạn token để tránh lộ thông tin và bị lạm dụng
- Hỗ trợ STUN/TURN thông qua cấu hình môi trường
- Cung cấp giao diện web trên điện thoại để tham gia kết nối mà không cần cài app riêng
- Hiển thị trạng thái và độ trễ kết nối, hỗ trợ ping kiểm tra đường truyền

## Kiến trúc hệ thống

PocketDock hoạt động theo mô hình sau:

1. Máy tính chạy desktop app và gọi `create_session` tới server
2. Server tạo một session ID và token, sau đó trả về QR code cho desktop app
3. Người dùng quét QR trên điện thoại
4. Điện thoại gửi `join` tới server với session ID và token
5. Máy tính nhận thông báo `join_request` và có thể chấp nhận hoặc từ chối
6. Khi được chấp nhận, hai thiết bị thiết lập kết nối trực tiếp WebRTC và trao đổi dữ liệu

## Công nghệ sử dụng

- Kotlin + Ktor cho backend signaling server
- JavaFX cho giao diện desktop
- WebRTC cho truyền dữ liệu trực tiếp
- JavaScript + HTML/CSS cho giao diện web trên điện thoại
- QR code generation bằng ZXing

## Cấu trúc thư mục

```text
pocketdock/
├── README.md
├── server/           # Máy chủ signaling và quản lý session
├── desktop/          # Ứng dụng desktop của máy tính
├── web/              # Trang web mở trên điện thoại
├── docs/             # Tài liệu, sơ đồ hoặc hướng dẫn phụ
└── .github/          # Workflow CI/CD
```

## Yêu cầu môi trường

- Java 21+
- Gradle Wrapper (đã tích hợp trong từng module)
- Một trình duyệt trên điện thoại để mở URL kết nối
- Mạng LAN/Wi‑Fi hoặc khả năng truy cập từ Internet tới server

## Khởi chạy nhanh

### 1. Chạy backend server

```bash
cd server
./gradlew run
```

Mặc định server chạy ở cổng `8081` và phục vụ nội dung web từ thư mục `../web`.

### 2. Chạy ứng dụng desktop

```bash
cd desktop
./gradlew run
```

Ứng dụng sẽ tự động kết nối tới server mặc định `http://localhost:8081`.

Nếu cần thay đổi địa chỉ server hoặc public URL, hãy thiết lập biến môi trường:

```bash
export POCKETDOCK_SERVER=http://localhost:8081
export POCKETDOCK_PUBLIC_URL=http://localhost:8081
```

### 3. Kết nối điện thoại

- Trên máy tính, desktop app sẽ hiển thị một mã QR
- Mở trình duyệt trên điện thoại và quét mã QR
- Điện thoại sẽ kết nối tới URL chứa session và token
- Máy tính hiển thị thông báo yêu cầu kết nối; nhấn "Cho phép" để tiếp tục

## Biến môi trường của server

Server hỗ trợ các biến môi trường sau:

```bash
PORT=8081
TOKEN_TTL_SECONDS=120
STUN_URLS=stun:stun.l.google.com:19302
WEB_DIR=../web
```

- `PORT`: cổng HTTP/WebSocket mà server lắng nghe
- `TOKEN_TTL_SECONDS`: thời gian sống của token QR
- `STUN_URLS`: danh sách STUN server dùng cho WebRTC
- `WEB_DIR`: đường dẫn thư mục static web mà server phục vụ

## Luồng xử lý chính

- `SessionStore`: quản lý session, token, kiểm tra trạng thái và xác thực quyền join
- `Signaling`: xử lý WebSocket, nhận và gửi tin nhắn giữa desktop và điện thoại
- `PocketDockApp`: desktop UI, hiển thị QR, quản lý cảnh báo, phê duyệt device
- `web/app.js`: code chạy trên browser điện thoại, tham gia vào phiên kết nối qua WebRTC

## Lưu ý

- Token QR chỉ có hiệu lực trong thời gian ngắn và sẽ hết hạn sau khi quá hạn hoặc đã được sử dụng
- Việc nối trực tiếp qua WebRTC thường hoạt động tốt nhất khi cả hai thiết bị ở cùng mạng Wi‑Fi hoặc có thể truy cập được nhau qua NAT/STUN
- Nếu máy tính và điện thoại không cùng mạng, hãy cấu hình STUN/TURN phù hợp để WebRTC có thể thực hiện handshake thành công

## Mục tiêu của dự án

PocketDock nhằm mục tiêu tạo ra một giải pháp đơn giản, nhanh và thân thiện với người dùng để kết nối máy tính với điện thoại mà không cần cài nhiều phần mềm phụ thuộc phức tạp. Dự án này phù hợp cho các trường hợp như:

- Chia sẻ kết nối hoặc điều khiển từ điện thoại sang máy tính
- Kiểm tra trạng thái của thiết bị trong môi trường phát triển
- Tạo nền tảng cho các tính năng truyền dữ liệu, điều khiển hoặc hiển thị trực tiếp trong tương lai

Nếu bạn đang phát triển hoặc mở rộng dự án, hãy bắt đầu từ `server/` để hiểu luồng signaling và từ `desktop/` để kiểm tra trải nghiệm người dùng trên máy tính.
