# Giao thức tin nhắn

Máy tính và điện thoại trao đổi tin nhắn dạng JSON với máy chủ qua WebSocket (đường dẫn `/ws`). Mỗi tin có trường `type`. Khung tin tối đa 64 KB, máy chủ chỉ chuyển tiếp những trường cần thiết. Hình và tiếng không đi qua máy chủ mà đi trực tiếp giữa hai thiết bị.

## Tin nhắn với máy chủ (WebSocket)

| Tin | Hướng | Ý nghĩa |
|---|---|---|
| `create_session` | máy tính gửi | Xin tạo phòng mới |
| `session_created` | máy chủ gửi | Trả về mã phòng, mã xác thực, thời hạn và danh sách STUN/TURN |
| `session_expired` | máy chủ gửi | Mã đã hết hạn |
| `join` | điện thoại gửi | Xin vào phòng, kèm mã phòng, mã xác thực và thông tin thiết bị |
| `waiting_approval` | máy chủ gửi | Báo điện thoại đang chờ máy tính cho phép |
| `join_request` | máy chủ gửi | Báo máy tính có thiết bị xin vào |
| `approve` / `reject` | máy tính gửi | Cho phép hoặc từ chối |
| `rejected` | máy chủ gửi | Báo điện thoại bị từ chối |
| `joined` | máy chủ gửi | Báo điện thoại đã vào, kèm danh sách STUN/TURN, mã tiếp tục và thời hạn giữ phòng |
| `join_rejected` | máy chủ gửi | Vào phòng không được; lý do: `not_found`, `bad_token`, `expired`, `already_used`, `too_many_attempts` |
| `offer` | máy tính gửi | Đề nghị kết nối trực tiếp (chỉ máy tính gửi) |
| `answer` | điện thoại gửi | Trả lời đề nghị (chỉ điện thoại gửi) |
| `ice` | hai bên | Thông tin đường đi mạng; chỉ được chuyển tiếp khi phòng đã được cho phép |
| `bye` / `device_left` | hai bên | Một bên rời đi |

## Giữ phòng và nối lại

Khi một bên mất kết nối tới máy chủ, máy chủ giữ phòng "tạm vắng" 90 giây. Bên đó nối lại bằng mã tiếp tục (128 bit, dùng một lần, mỗi lần nối lại thành công thì được cấp mã mới) mà không cần quét lại mã QR và không hỏi "Cho phép" lần nữa.

| Tin | Hướng | Ý nghĩa |
|---|---|---|
| `resume_info` | máy chủ gửi máy tính | Mã tiếp tục của máy tính, gửi khi bấm "Cho phép" |
| `device_away` / `desktop_away` | máy chủ gửi | Bên kia tạm vắng, kèm thời gian giữ phòng |
| `resume` | hai bên gửi | Xin nối lại: vai trò, mã phòng, mã tiếp tục, thông tin thiết bị |
| `resumed` | máy chủ gửi | Nối lại thành công, kèm mã tiếp tục mới |
| `resume_rejected` | máy chủ gửi | Không nối lại được; lý do: `not_found`, `bad_token`, `expired`, `too_many_attempts` |
| `device_back` / `desktop_back` | máy chủ gửi | Bên kia đã quay lại |
| `restart` | máy tính gửi | Yêu cầu điện thoại dựng lại kết nối trực tiếp |
| `ping` / `pong` | điện thoại hỏi máy chủ | Kiểm tra đường truyền còn sống không |

## Kênh điều khiển trực tiếp

Sau khi kết nối trực tiếp, hai bên mở một kênh dữ liệu tên `control` để trao đổi tin JSON (trường `t`):

| Tin | Ý nghĩa |
|---|---|
| `hello` | Chào nhau khi kênh mở |
| `ping` / `pong` | Đo độ trễ |
| `hb` | Nhịp tim, điện thoại gửi 2 giây một lần |
| `webcam`, `mic` | Điện thoại báo bật hoặc tắt webcam, micro |
| `ev` | Sự kiện như trang bị ẩn hoặc hiện lại, khóa màn hình |
| `touch` | Tọa độ chạm: hành động `down`, `move`, `up`, mã ngón tay, `x` và `y` từ 0 đến 1 (tỉ lệ theo hình màn hình), tối đa khoảng 60 tin mỗi giây |

## Bốn làn hình và tiếng

Máy tính dựng sẵn bốn làn trong đề nghị kết nối, theo thứ tự: hình màn hình (máy tính gửi), tiếng loa (máy tính gửi), hình webcam (điện thoại gửi), tiếng micro (điện thoại gửi), rồi kênh dữ liệu. Nhờ vậy khi điện thoại bật webcam hay micro chỉ cần gắn nguồn vào làn có sẵn, không phải thương lượng lại.
