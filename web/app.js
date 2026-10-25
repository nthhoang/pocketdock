(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);

  function show(state, title, detail) {
    document.body.dataset.state = state;
    $('status').textContent = title;
    $('detail').textContent = detail || '';
  }

  function deviceLabel() {
    const ua = navigator.userAgent;
    let device = 'Thiết bị khác';
    if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) device = 'iPad';
    else if (/iPhone/.test(ua)) device = 'iPhone';
    else if (/Android/.test(ua)) device = 'Android';
    let browser = 'Trình duyệt';
    if (/CriOS|Chrome/.test(ua)) browser = 'Chrome';
    else if (/FxiOS|Firefox/.test(ua)) browser = 'Firefox';
    else if (/Safari/.test(ua)) browser = 'Safari';
    return device + ', ' + browser;
  }

  const REASONS = {
    not_found: 'Mã QR không hợp lệ hoặc đã hết hạn. Hãy quét mã mới trên máy tính.',
    bad_token: 'Mã QR không hợp lệ. Hãy quét lại mã trên máy tính.',
    expired: 'Mã QR đã hết hạn. Hãy quét mã mới trên máy tính.',
    already_used: 'Mã QR này đã được dùng. Hãy quét mã mới trên máy tính.',
    too_many_attempts: 'Thử sai quá nhiều lần. Hãy đợi một phút rồi thử lại.',
  };

  // Link có dạng https://tenmien/#maPhong.token. Phần sau dấu # không bị gửi lên máy chủ web.
  const hash = location.hash.slice(1);
  const dot = hash.indexOf('.');
  const sessionId = dot > 0 ? hash.slice(0, dot) : '';
  const token = dot > 0 ? hash.slice(dot + 1) : '';
  // Xóa mã khỏi thanh địa chỉ: token chỉ dùng một lần nên tải lại trang sẽ phải quét lại
  history.replaceState(null, '', location.pathname + location.search);

  if (!sessionId || !token) {
    show('error', 'Thiếu mã kết nối', 'Hãy quét mã QR trên màn hình máy tính.');
    return;
  }

  show('connecting', 'Đang kết nối...', '');
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(proto + '//' + location.host + '/ws');

  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'join', sessionId, token, deviceInfo: deviceLabel() }));
  };

  ws.onmessage = (event) => {
    let m;
    try { m = JSON.parse(event.data); } catch (e) { return; }
    switch (m.type) {
      case 'waiting_approval':
        show('waiting', 'Chờ máy tính cho phép...', 'Hãy bấm Cho phép trên máy tính.');
        break;
      case 'joined':
        show('ok', 'Đã kết nối', 'Thiết bị đã được máy tính cho phép. Các chức năng sẽ có ở bước sau.');
        break;
      case 'rejected':
        show('error', 'Máy tính đã từ chối', 'Quét mã QR mới nếu muốn thử lại.');
        break;
      case 'join_rejected':
        show('error', 'Không vào được', REASONS[m.reason] || 'Hãy quét lại mã QR trên máy tính.');
        break;
      case 'bye':
        show('error', 'Máy tính đã ngắt kết nối', 'Quét mã QR mới để kết nối lại.');
        break;
    }
  };

  ws.onclose = () => {
    const state = document.body.dataset.state;
    if (state === 'connecting' || state === 'waiting' || state === 'ok') {
      show('error', 'Mất kết nối', 'Hãy quét lại mã QR trên máy tính.');
    }
  };
})();
