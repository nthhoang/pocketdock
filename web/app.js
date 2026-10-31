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
    box.textContent = (time + '  ' + text + '\n' + box.textContent).split('\n').slice(0, 10).join('\n');
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

  const QUALITY = {
    480: { w: 854, h: 480 },
    720: { w: 1280, h: 720 },
    1080: { w: 1920, h: 1080 },
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

  // ---------- trạng thái ----------

  let pc = null;
  let control = null;
  let remoteSet = false;
  let pendingIce = [];
  let statsTimer = null;
  let heartbeatTimer = null;
  let pingSeq = 0;
  let hbSeq = 0;
  const remoteStream = new MediaStream();

  // Hai làn điện thoại → máy tính, lấy từ offer của máy tính
  const lanes = { video: null, audio: null };
  let camTrack = null;
  let micTrack = null;
  let camWanted = false;
  let micWanted = false;
  let facing = 'user';

  let wakeLock = null;
  let wakeWanted = true;
  let hiddenAt = null;

  function sendSignal(obj) {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function sendControl(obj) {
    if (control && control.readyState === 'open') {
      try {
        control.send(JSON.stringify(obj));
        return true;
      } catch (e) {
        return false;
      }
    }
    return false;
  }

  function isConnected() {
    return pc && pc.connectionState === 'connected';
  }

  function refresh() {
    const connected = isConnected();
    $('cam').disabled = !(connected && lanes.video);
    $('mic').disabled = !(connected && lanes.audio);
    $('flip').disabled = !camTrack;
    $('cam').textContent = camTrack ? 'Tắt webcam' : 'Bật webcam';
    $('mic').textContent = micTrack ? 'Tắt micro' : 'Bật micro';
    $('wake').textContent = 'Giữ màn hình sáng: ' + (wakeWanted ? 'bật' : 'tắt');
  }

  // ---------- WebRTC ----------

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
      const s = pc.connectionState;
      setLine('peer', 'Kết nối trực tiếp: ' + (STATE_VI[s] || s));
      log('Kết nối trực tiếp: ' + (STATE_VI[s] || s));
      sendControl({ t: 'ev', ev: 'pc', state: s });
      if (s === 'failed') {
        show('error', 'Không nối trực tiếp được',
          'Hãy kiểm tra điện thoại và máy tính đã dùng cùng một mạng Wi-Fi chưa.');
      }
      refresh();
    };

    statsTimer = setInterval(pollStats, 1000);
  }

  function setupChannel(channel) {
    control = channel;
    channel.onopen = () => {
      setLine('channel', 'Kênh điều khiển: đã mở');
      channel.send(JSON.stringify({ t: 'hello', from: 'phone', device: deviceLabel() }));
      // Nhịp tim 2 giây một lần: máy tính dùng để biết điện thoại còn sống hay không
      heartbeatTimer = setInterval(() => sendControl({ t: 'hb', n: ++hbSeq }), 2000);
    };
    channel.onclose = () => {
      setLine('channel', 'Kênh điều khiển: đã đóng');
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    };
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

    // Làn nào máy tính chỉ nhận (a=recvonly) thì điện thoại sẽ là bên gửi
    const sections = m.sdp.split(/\r?\nm=/).slice(1);
    for (const section of sections) {
      const mid = (section.match(/\na=mid:(\S+)/) || [])[1];
      if (mid === undefined) continue;
      if (/\na=recvonly/.test(section)) {
        const transceiver = pc.getTransceivers().find((t) => t.mid === mid);
        if (transceiver) {
          transceiver.direction = 'sendonly';
          const kind = transceiver.receiver.track.kind;
          if (kind === 'video') lanes.video = transceiver;
          else if (kind === 'audio') lanes.audio = transceiver;
        }
      }
    }
    log('Làn gửi: ' + (lanes.video ? 'hình ' : '') + (lanes.audio ? 'tiếng' : ''));

    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    sendSignal({ type: 'answer', sdp: pc.localDescription.sdp });

    remoteSet = true;
    for (const c of pendingIce) await addRemoteIce(c);
    pendingIce = [];
    refresh();
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
      setLine('video', 'Hình ảnh nhận: ' + size + ', ' + Math.round(video.framesPerSecond || 0) + ' khung hình/giây');
    }
    if (audio) {
      setLine('audio', 'Âm thanh nhận: ' + (audio.packetsReceived > 0
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

  // ---------- webcam ----------

  async function startCamera() {
    const q = QUALITY[$('quality').value];
    // iPhone chỉ mở được một camera một lúc, nên tắt cái cũ trước
    if (camTrack) { camTrack.onended = null; camTrack.stop(); camTrack = null; }

    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: facing },
        width: { ideal: q.w },
        height: { ideal: q.h },
        frameRate: { ideal: 30 },
      },
      audio: false,
    });
    const track = stream.getVideoTracks()[0];
    track.onended = () => {
      log('Hệ thống đã dừng camera');
      camTrack = null;
      setLine('cam', 'Webcam: bị hệ thống dừng');
      sendControl({ t: 'webcam', on: false, reason: 'ended' });
      refresh();
    };
    await lanes.video.sender.replaceTrack(track);
    camTrack = track;
    camWanted = true;
    $('preview').srcObject = new MediaStream([track]);

    const s = track.getSettings();
    const size = (s.width || '?') + 'x' + (s.height || '?');
    setLine('cam', 'Webcam: đang bật ' + size + ' @' + Math.round(s.frameRate || 0));
    sendControl({
      t: 'webcam', on: true, w: s.width, h: s.height,
      fps: Math.round(s.frameRate || 0), facing,
    });
    if (wakeWanted) requestWakeLock();
    refresh();
  }

  async function stopCamera() {
    camWanted = false;
    if (lanes.video) await lanes.video.sender.replaceTrack(null);
    if (camTrack) { camTrack.onended = null; camTrack.stop(); camTrack = null; }
    $('preview').srcObject = null;
    setLine('cam', 'Webcam: tắt');
    sendControl({ t: 'webcam', on: false });
    refresh();
  }

  // ---------- micro ----------

  async function startMic() {
    if (micTrack) { micTrack.onended = null; micTrack.stop(); micTrack = null; }
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false,
    });
    const track = stream.getAudioTracks()[0];
    track.onended = () => {
      log('Hệ thống đã dừng micro');
      micTrack = null;
      setLine('mic', 'Micro: bị hệ thống dừng');
      sendControl({ t: 'mic', on: false, reason: 'ended' });
      refresh();
    };
    await lanes.audio.sender.replaceTrack(track);
    micTrack = track;
    micWanted = true;
    setLine('mic', 'Micro: đang bật');
    sendControl({ t: 'mic', on: true });
    if (wakeWanted) requestWakeLock();
    refresh();
  }

  async function stopMic() {
    micWanted = false;
    if (lanes.audio) await lanes.audio.sender.replaceTrack(null);
    if (micTrack) { micTrack.onended = null; micTrack.stop(); micTrack = null; }
    setLine('mic', 'Micro: tắt');
    sendControl({ t: 'mic', on: false });
    refresh();
  }

  // ---------- giữ màn hình sáng ----------

  async function requestWakeLock() {
    if (!('wakeLock' in navigator)) {
      setLine('wake', 'Giữ màn hình sáng: trình duyệt không hỗ trợ');
      return;
    }
    if (wakeLock) return;
    try {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => {
        wakeLock = null;
        setLine('wake', 'Giữ màn hình sáng: đã bị hệ thống nhả');
        log('Wake Lock bị nhả');
        sendControl({ t: 'ev', ev: 'wake_released' });
      });
      setLine('wake', 'Giữ màn hình sáng: đang bật');
      sendControl({ t: 'ev', ev: 'wake_on' });
    } catch (e) {
      wakeLock = null;
      setLine('wake', 'Giữ màn hình sáng: lỗi ' + e.name);
    }
  }

  async function releaseWakeLock() {
    if (wakeLock) {
      try { await wakeLock.release(); } catch (e) { /* bỏ qua */ }
      wakeLock = null;
    }
    setLine('wake', 'Giữ màn hình sáng: tắt');
  }

  // ---------- khi trang bị ẩn (khóa màn hình, chuyển app) ----------

  async function resumeCapture() {
    try {
      if (camWanted && (!camTrack || camTrack.readyState === 'ended')) await startCamera();
      if (micWanted && (!micTrack || micTrack.readyState === 'ended')) await startMic();
    } catch (e) {
      log('Không tự bật lại được: ' + e.name + ' (hãy bấm nút để bật lại)');
      sendControl({ t: 'ev', ev: 'resume_failed', name: e.name });
    }
  }

  document.addEventListener('visibilitychange', async () => {
    if (document.visibilityState === 'hidden') {
      hiddenAt = Date.now();
      // Gửi được hay không còn tùy trình duyệt kịp tạm dừng chưa
      sendControl({ t: 'ev', ev: 'hidden' });
    } else {
      const away = hiddenAt ? Math.round((Date.now() - hiddenAt) / 1000) : 0;
      hiddenAt = null;
      log('Quay lại sau ' + away + ' giây');
      setLine('peer', 'Kết nối trực tiếp: ' + (pc ? (STATE_VI[pc.connectionState] || pc.connectionState) : 'không có'));
      sendControl({
        t: 'ev', ev: 'visible', away,
        pc: pc ? pc.connectionState : 'none',
        ice: pc ? pc.iceConnectionState : 'none',
        channel: control ? control.readyState : 'none',
        cam: camTrack ? camTrack.readyState : 'off',
        mic: micTrack ? micTrack.readyState : 'off',
        wake: wakeLock ? 'on' : 'off',
      });
      if (wakeWanted) await requestWakeLock();
      await resumeCapture();
      refresh();
    }
  });

  window.addEventListener('pagehide', () => sendControl({ t: 'ev', ev: 'pagehide' }));

  // ---------- nút bấm ----------

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

  $('cam').addEventListener('click', async () => {
    try {
      if (camTrack) await stopCamera();
      else await startCamera();
    } catch (e) {
      camWanted = false;
      log('Webcam lỗi: ' + e.name + ' ' + e.message);
      setLine('cam', 'Webcam: lỗi ' + e.name);
    }
    refresh();
  });

  $('flip').addEventListener('click', async () => {
    facing = facing === 'user' ? 'environment' : 'user';
    try { await startCamera(); } catch (e) { log('Đổi camera lỗi: ' + e.name); }
  });

  $('quality').addEventListener('change', async () => {
    if (!camTrack) return;
    try { await startCamera(); } catch (e) { log('Đổi chất lượng lỗi: ' + e.name); }
  });

  $('mic').addEventListener('click', async () => {
    try {
      if (micTrack) await stopMic();
      else await startMic();
    } catch (e) {
      micWanted = false;
      log('Micro lỗi: ' + e.name + ' ' + e.message);
      setLine('mic', 'Micro: lỗi ' + e.name);
    }
    refresh();
  });

  $('wake').addEventListener('click', async () => {
    wakeWanted = !wakeWanted;
    if (wakeWanted) await requestWakeLock();
    else await releaseWakeLock();
    refresh();
  });

  function closePeer() {
    if (statsTimer) { clearInterval(statsTimer); statsTimer = null; }
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    if (camTrack) { camTrack.onended = null; camTrack.stop(); camTrack = null; }
    if (micTrack) { micTrack.onended = null; micTrack.stop(); micTrack = null; }
    releaseWakeLock();
    if (pc) { pc.close(); pc = null; }
    refresh();
  }

  // ---------- kết nối máy chủ ----------

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
