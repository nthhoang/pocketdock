#!/usr/bin/env python3
# Chuong trinh phu GIA de thu tren Ubuntu: ghi ra stdout tieng "tut" 440 Hz,
# 16-bit, 48 kHz, 2 kenh, tung khuc 10 ms (1920 byte), deu dan theo dong ho.
# Dung de thu duong ong dan Java -> WebRTC khi chua co may Windows.
import math
import struct
import sys
import time

RATE = 48000
FRAMES = 480
freq = float(sys.argv[1]) if len(sys.argv) > 1 else 440.0

out = sys.stdout.buffer
sys.stderr.write("fake-helper: phat tieng %.0f Hz\n" % freq)
sys.stderr.flush()
start = time.monotonic()
n = 0
i = 0
while True:
    parts = []
    for k in range(FRAMES):
        v = int(math.sin(2.0 * math.pi * freq * ((n + k) % RATE) / RATE) * 0.2 * 32767)
        parts.append(struct.pack("<hh", v, v))
    n += FRAMES
    try:
        out.write(b"".join(parts))
        out.flush()
    except (BrokenPipeError, OSError):
        break
    i += 1
    delay = start + i * 0.01 - time.monotonic()
    if delay > 0:
        time.sleep(delay)
