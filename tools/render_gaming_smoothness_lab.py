#!/usr/bin/env python3
"""
Render the GamingSmoothnessLab CSV into tiny synthetic motion videos.

These videos are NOT recordings of a physical Android device and are never
presented as such. They visualize the lab's measured/predicted presentation
cadence: a 60 Hz ideal motion path is quantized by presented_fps, so a 15 fps
result visibly freezes for multiple output frames while a 50-60 fps result
looks substantially smoother.

No third-party Python packages are required. ffmpeg consumes raw RGB frames.
"""

import csv
import math
import os
import shutil
import subprocess
import sys
from collections import defaultdict

WIDTH = 320
HEIGHT = 180
OUT_FPS = 30
ROW_MS = 500


def read_trace(path):
    groups = defaultdict(list)
    with open(path, newline="", encoding="utf-8") as f:
        for raw in csv.DictReader(f):
            row = {
                "scenario": raw["scenario"],
                "time_ms": int(raw["time_ms"]),
                "bandwidth_kbps": float(raw["bandwidth_kbps"]),
                "rtt_ms": int(raw["rtt_ms"]),
                "loss": float(raw["loss"]),
                "source_fps": float(raw["source_fps"]),
                "target_fps": int(raw["target_fps"]),
                "send_fps": float(raw["send_fps"]),
                "decode_fps": float(raw["decode_fps"]),
                "present_fps": float(raw["present_fps"]),
                "short_edge": int(raw["short_edge"]),
                "queue_ms": float(raw["queue_ms"]),
                "jitter_ms": float(raw["jitter_ms"]),
                "max_gap_ms": int(raw["max_gap_ms"]),
                "post_ack_ms": int(raw["post_ack_ms"]),
                "glass_ratio": float(raw["glass_ratio"]),
                "lag": raw["lag"].lower() == "true",
            }
            groups[row["scenario"]].append(row)
    for rows in groups.values():
        rows.sort(key=lambda x: x["time_ms"])
    return groups


def row_at(rows, elapsed_ms):
    index = max(0, min(len(rows) - 1, int(elapsed_ms // ROW_MS)))
    return rows[index]


def rect(frame, x0, y0, x1, y1, rgb):
    x0 = max(0, min(WIDTH, int(x0)))
    x1 = max(0, min(WIDTH, int(x1)))
    y0 = max(0, min(HEIGHT, int(y0)))
    y1 = max(0, min(HEIGHT, int(y1)))
    if x1 <= x0 or y1 <= y0:
        return
    r, g, b = rgb
    for y in range(y0, y1):
        start = (y * WIDTH + x0) * 3
        end = (y * WIDTH + x1) * 3
        frame[start:end] = bytes((r, g, b)) * (x1 - x0)


def make_frame(row, elapsed_s):
    frame = bytearray((14, 16, 20) * (WIDTH * HEIGHT))

    # 60 Hz ideal path, quantized by the presented cadence. This makes skipped
    # presentation opportunities visible as repeated positions.
    presented = max(1.0, row["present_fps"])
    visible_t = math.floor(elapsed_s * presented) / presented
    cycle = (visible_t * 0.42) % 2.0
    phase = cycle if cycle <= 1.0 else 2.0 - cycle
    x = 14 + int(phase * (WIDTH - 46))
    rect(frame, x, 58, x + 28, 86, (235, 235, 235))

    # Ideal 60 Hz marker: if the white box trails this marker, the simulated
    # display is visibly behind the freshest motion state.
    ideal_cycle = (elapsed_s * 0.42) % 2.0
    ideal_phase = ideal_cycle if ideal_cycle <= 1.0 else 2.0 - ideal_cycle
    ideal_x = 14 + int(ideal_phase * (WIDTH - 46))
    rect(frame, ideal_x + 12, 42, ideal_x + 16, 52, (80, 180, 255))

    # Bottom telemetry bars: target, present, queue, loss.
    bar_left = 12
    bar_width = WIDTH - 24
    base_y = 112
    metrics = [
        (min(1.0, row["target_fps"] / 60.0), (70, 170, 255)),
        (min(1.0, row["present_fps"] / 60.0), (80, 220, 120)),
        (min(1.0, row["queue_ms"] / 200.0), (245, 185, 65)),
        (min(1.0, row["loss"] / 0.15), (235, 90, 90)),
    ]
    for i, (ratio, color) in enumerate(metrics):
        y = base_y + i * 13
        rect(frame, bar_left, y, bar_left + bar_width, y + 7, (38, 42, 50))
        rect(frame, bar_left, y, bar_left + int(bar_width * ratio), y + 7, color)

    # A red border is a direct visual lag alarm from the lab threshold.
    if row["lag"]:
        rect(frame, 0, 0, WIDTH, 4, (235, 70, 70))
        rect(frame, 0, HEIGHT - 4, WIDTH, HEIGHT, (235, 70, 70))
        rect(frame, 0, 0, 4, HEIGHT, (235, 70, 70))
        rect(frame, WIDTH - 4, 0, WIDTH, HEIGHT, (235, 70, 70))

    return frame


def render_scenario(name, rows, out_dir):
    duration_s = rows[-1]["time_ms"] / 1000.0
    total_frames = max(1, int(math.ceil(duration_s * OUT_FPS)))
    out_path = os.path.join(out_dir, f"{name}.mp4")

    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{WIDTH}x{HEIGHT}", "-r", str(OUT_FPS), "-i", "-",
        "-an", "-c:v", "libx264", "-preset", "veryfast",
        "-crf", "24", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        out_path,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    try:
        for index in range(total_frames):
            elapsed_s = index / OUT_FPS
            row = row_at(rows, elapsed_s * 1000.0)
            proc.stdin.write(make_frame(row, elapsed_s))
    finally:
        if proc.stdin:
            proc.stdin.close()
    code = proc.wait()
    if code != 0:
        raise SystemExit(f"ffmpeg failed for {name}: {code}")
    return out_path


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: render_gaming_smoothness_lab.py TRACE_CSV OUTPUT_DIR")

    trace_path, out_dir = sys.argv[1], sys.argv[2]
    if not os.path.exists(trace_path):
        print(f"trace missing: {trace_path}; nothing to render", file=sys.stderr)
        return 0
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg is required for smoothness-lab video artifacts")

    os.makedirs(out_dir, exist_ok=True)
    groups = read_trace(trace_path)
    for name, rows in groups.items():
        path = render_scenario(name, rows, out_dir)
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
