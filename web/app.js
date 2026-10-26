(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);

  function show(state, title, detail) {
    document.body.dataset.state = state;
    $('status').textContent = title;
    $('detail').textContent = detail || '';
  }

  function setLine(id, text) {
    $('line-' + id).textContent = text;
  }

  function log(text) {
    const box = $('log');
    const time = new Date().toLocaleTimeString('vi-VN');
    box.textContent = (time + '  ' + text + '\n' + box.textContent).split('\n').slice(0, 8).join('\n');
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

  const STATE_VI = {
    new: 'mới', connecting: 'đang nối', connected: 'đã nối',
    disconnected: 'mất kết nối', failed: 'thất bại', closed: 'đã đóng',
  };

  const CANDIDATE_VI = {
    host: 'trực tiếp trong mạng', srflx: 'qua STUN', prflx: 'trực tiếp (phát hiện lúc nối)', relay: 'qua TURN',
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

  // ---------- WebRTC ----------

  let pc = null;
  let control = null;
  let remoteSet = false;
  let pendingIce = [];
  let statsTimer = null;
  let pingSeq = 0;
  const remoteStream = new MediaStream();

  function sendSignal(obj) {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function startPeer(iceServers) {
    pc = new RTCPeerConnection({ iceServers });
    $('screen').srcObject = remoteStream;

    pc.onicecandidate = (e) => {
      if (e.candidate) {
        sendSignal({
          type: 'ice',
          candidate: e.candidate.candidate,
          sdpMid: e.candidate.sdpMid,
          sdpMLineIndex: e.candidate.sdpMLineIndex,
        });
      }
    };

    pc.ontrack = (e) => {
      remoteStream.addTrack(e.track);
      $('screen').play().catch(() => {});
      log('Nhận ' + (e.track.kind === 'video' ? 'hình' : 'tiếng') + ' từ máy tính');
    };

    pc.ondatachannel = (e) => setupChannel(e.channel);

    pc.onconnectionstatechange = () => {
      setLine('peer', 'Kết nối trực tiếp: ' + (STATE_VI[pc.connectionState] || pc.connectionState));
      if (pc.connectionState === 'failed') {
        show('error', 'Không nối trực tiếp được',
          'Hãy kiểm tra điện thoại và máy tính đã dùng cùng một mạng Wi-Fi chưa.');
      }
    };

    statsTimer = setInterval(pollStats, 1000);
  }

  function setupChannel(channel) {
    control = channel;
    channel.onopen = () => {
      setLine('channel', 'Kênh điều khiển: đã mở');
      channel.send(JSON.stringify({ t: 'hello', from: 'phone', device: deviceLabel() }));
    };
    channel.onclose = () => setLine('channel', 'Kênh điều khiển: đã đóng');
    channel.onmessage = (e) => {
      let m;
      try { m = JSON.parse(e.data); } catch (err) { return; }
      if (m.t === 'pong') {
        const rtt = performance.now() - m.ts;
        setLine('rtt', 'Độ trễ ping: ' + rtt.toFixed(0) + ' ms');
      } else {
        log('Máy tính: ' + e.data.slice(0, 80));
      }
    };
  }

  async function onOffer(m) {
    await pc.setRemoteDescription({ type: 'offer', sdp: m.sdp });

    // Làn nào máy tính chỉ nhận (a=recvonly) thì điện thoại sẽ là bên gửi (dùng ở M4)
    const sections = m.sdp.split(/\r?\nm=/).slice(1);
    for (const section of sections) {
      const mid = (section.match(/\na=mid:(\S+)/) || [])[1];
      if (mid === undefined) continue;
      if (/\na=recvonly/.test(section)) {
        const transceiver = pc.getTransceivers().find((t) => t.mid === mid);
        if (transceiver) transceiver.direction = 'sendonly';
      }
    }

    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    sendSignal({ type: 'answer', sdp: pc.localDescription.sdp });

    remoteSet = true;
    for (const c of pendingIce) await addRemoteIce(c);
    pendingIce = [];
  }

  async function addRemoteIce(m) {
    try {
      await pc.addIceCandidate({ candidate: m.candidate, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex });
    } catch (err) {
      log('Bỏ qua một candidate lỗi');
    }
  }

  async function pollStats() {
    if (!pc) return;
    const report = await pc.getStats();
    let video = null;
    let audio = null;
    let pair = null;
    const candidates = {};
    report.forEach((s) => {
      const kind = s.kind || s.mediaType;
      if (s.type === 'inbound-rtp' && kind === 'video') video = s;
      else if (s.type === 'inbound-rtp' && kind === 'audio') audio = s;
      else if (s.type === 'candidate-pair' && s.state === 'succeeded' && (s.nominated || s.selected)) pair = s;
      else if (s.type === 'local-candidate' || s.type === 'remote-candidate') candidates[s.id] = s;
    });

    if (video) {
      const size = (video.frameWidth || '?') + 'x' + (video.frameHeight || '?');
      setLine('video', 'Hình ảnh: ' + size + ', ' + Math.round(video.framesPerSecond || 0) + ' khung hình/giây');
    }
    if (audio) {
      setLine('audio', 'Âm thanh: ' + (audio.packetsReceived > 0
        ? 'đang nhận (' + audio.packetsReceived + ' gói)' : 'chưa có gói nào'));
    }
    if (pair) {
      const local = candidates[pair.localCandidateId];
      const remote = candidates[pair.remoteCandidateId];
      const a = local ? local.candidateType : '?';
      const b = remote ? remote.candidateType : '?';
      setLine('route', 'Đường truyền: ' + (CANDIDATE_VI[a] || a) + ' / ' + (CANDIDATE_VI[b] || b));
    }
  }

  function closePeer() {
    if (statsTimer) { clearInterval(statsTimer); statsTimer = null; }
    if (pc) { pc.close(); pc = null; }
  }

  $('unmute').addEventListener('click', () => {
    const v = $('screen');
    v.muted = false;
    v.play().catch(() => {});
    $('unmute').textContent = 'Đã bật tiếng';
  });

  $('ping').addEventListener('click', () => {
    if (!control || control.readyState !== 'open') {
      log('Kênh điều khiển chưa mở');
      return;
    }
    control.send(JSON.stringify({ t: 'ping', id: ++pingSeq, ts: performance.now() }));
  });

  // ---------- Kết nối máy chủ ----------

  show('connecting', 'Đang kết nối...', '');
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(proto + '//' + location.host + '/ws');

  ws.onopen = () => {
    sendSignal({ type: 'join', sessionId, token, deviceInfo: deviceLabel() });
  };

  // Xử lý lần lượt từng tin nhắn, để tin sau không chạy trước khi tin trước xong
  let chain = Promise.resolve();

  ws.onmessage = (event) => {
    let m;
    try { m = JSON.parse(event.data); } catch (e) { return; }
    chain = chain
      .then(() => handle(m))
      .catch((err) => log('Lỗi: ' + (err && err.message ? err.message : err)));
  };

  async function handle(m) {
    switch (m.type) {
      case 'waiting_approval':
        show('waiting', 'Chờ máy tính cho phép...', 'Hãy bấm Cho phép trên máy tính.');
        break;
      case 'joined':
        show('ok', 'Đã kết nối', 'Đang nối trực tiếp với máy tính...');
        $('media').hidden = false;
        startPeer(m.iceServers || []);
        break;
      case 'offer':
        if (pc) await onOffer(m);
        break;
      case 'ice':
        if (!pc) break;
        if (remoteSet) await addRemoteIce(m);
        else pendingIce.push(m);
        break;
      case 'rejected':
        show('error', 'Máy tính đã từ chối', 'Quét mã QR mới nếu muốn thử lại.');
        break;
      case 'join_rejected':
        show('error', 'Không vào được', REASONS[m.reason] || 'Hãy quét lại mã QR trên máy tính.');
        break;
      case 'bye':
        closePeer();
        show('error', 'Máy tính đã ngắt kết nối', 'Quét mã QR mới để kết nối lại.');
        break;
    }
  }

  ws.onclose = () => {
    const state = document.body.dataset.state;
    if (state === 'connecting' || state === 'waiting' || state === 'ok') {
      closePeer();
      show('error', 'Mất kết nối', 'Hãy quét lại mã QR trên máy tính.');
    }
  };
})();
