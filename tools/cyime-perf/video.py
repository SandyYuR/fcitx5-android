"""Map video PTS with an editor-owned marker and associate changes with real touches."""

from bisect import bisect_left
import json
import math
import statistics
import struct
import subprocess


def describe(values):
    values = sorted(values)
    if not values:
        return {"n": 0}
    return {"n": len(values), "median_ms": statistics.median(values),
            "p90_ms": values[math.ceil(len(values) * .90) - 1],
            "min_ms": values[0], "max_ms": values[-1]}


def read_frames(video, rectangles, frame_count, width, height):
    def scaled(rect):
        return tuple(round(v * (width / 480 if i % 2 == 0 else height / 960))
                     for i, v in enumerate(rect))

    groups = list(rectangles)
    filters = [f"[0:v]split={len(groups)}" + "".join(f"[s{i}]" for i in range(len(groups)))]
    for i, key in enumerate(groups):
        left, top, right, bottom = scaled(rectangles[key])
        w, h = right - left, bottom - top
        if min(w, h) <= 0 or left < 0 or top < 0 or right > width or bottom > height:
            raise ValueError(f"Crop outside video for {key}")
        filters.append(f"[s{i}]crop={w}:{h}:{left}:{top},scale=96:12:flags=area[p{i}]")
    filters.append("".join(f"[p{i}]" for i in range(len(groups))) + f"vstack=inputs={len(groups)},format=rgb24[v]")
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-threads", "1", "-i", str(video),
                                    "-filter_complex_threads", "1", "-filter_complex", ";".join(filters),
                                    "-map", "[v]", "-vsync", "0", "-f", "rawvideo", "pipe:1"])
    length = 96 * 12 * 3
    stride = length * len(groups)
    if len(data) != stride * frame_count:
        raise ValueError("Decoded video and PTS frame counts differ")
    view = memoryview(data)
    return {key: [view[start + i * length:start + (i + 1) * length]
                  for start in range(0, len(data), stride)] for i, key in enumerate(groups)}


def changes(frames):
    return [sum(abs(previous[i] - current[i]) + abs(previous[i + 1] - current[i + 1])
                + abs(previous[i + 2] - current[i + 2]) >= 60 for i in range(0, len(current), 3))
            for previous, current in zip(frames, frames[1:])]


def associate(times, changes, start, end, threshold):
    for index in range(max(1, bisect_left(times, start)), bisect_left(times, end)):
        if changes[index - 1] >= threshold:
            return round(times[index] - start, 3)
    return None


def analyze(directory, bounds, words, cadence):
    log = json.loads((directory / "touches.json").read_text())
    host = json.loads((directory / "candidate-editor.json").read_text())
    events = log["touches"]
    expected = [word for _, word in words]
    commits = [event for event in host["commits"] if event["text"]]
    expected_keys = [key for code, _ in words for key in list(code) + ["select"]]
    if [event["key"] for event in events] != expected_keys or [event["text"] for event in commits] != expected or host["text"] != "".join(expected):
        raise ValueError("Touch or exact commit sequence differs from corpus")
    if any(not e["down_ns"] <= e["down_return_ns"] <= e["up_ns"] <= e["up_return_ns"]
           or e["up_ns"] - e["down_ns"] < 20_000_000 for e in events):
        raise ValueError("Invalid injection clock or the actual key hold was shorter than 20 ms")
    if any(b["down_ns"] < a["up_return_ns"] for a, b in zip(events, events[1:])):
        raise ValueError("Injection clock moved backwards between touches")
    intervals = [(b["down_ns"] - a["down_ns"]) / 1e6 for a, b in zip(events, events[1:])
                 if a["round"] == b["round"] and b["key"] != "select"]
    cadence_error = describe([abs(interval - cadence) for interval in intervals])
    (directory / "cadence.json").write_text(json.dumps({"requested_ms": cadence,
        "intervals_ms": intervals, "absolute_error": cadence_error}, indent=2) + "\n")
    if cadence_error["n"] and cadence_error["p90_ms"] > 10:
        raise ValueError(f"Touch cadence missed {cadence} ms: {cadence_error}")
    video = directory / "run.mp4"
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_frames", "-show_streams", "-show_entries", "frame=best_effort_timestamp_time:stream=width,height",
        "-of", "json", str(video)]))
    (directory / "video-pts.json").write_text(json.dumps(probe))
    pts = [float(frame["best_effort_timestamp_time"]) * 1000 for frame in probe["frames"]]
    if not pts or any(a >= b for a, b in zip(pts, pts[1:])):
        raise ValueError("Invalid video PTS order")
    width, height = (probe["streams"][0][key] for key in ("width", "height"))
    png = (directory / "before.png").read_bytes()
    if png[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", png[16:24]) != (480, 960) or abs(width / 480 - height / 960) > .01:
        raise ValueError("Recording and calibrated 480x960 screen geometry differ")
    x, y = log["markers"]["x"], log["markers"]["y"]
    crops = {key: rect for key, rect in bounds.items() if key != "select"}
    crops["marker"] = (x - 8, y - 8, x + 8, y + 8)
    frames = read_frames(video, crops, len(pts), width, height)
    states = [frame[0] > frame[2] for frame in frames.pop("marker")]
    # Require both marker groups; the departure from first to second group covers the whole replay.
    transitions = [i for i in range(1, len(states)) if states[i] != states[i - 1]]
    marks = log["markers"]["frame_ns"]
    if len(transitions) != 12 or len(marks) != 12:
        raise ValueError(f"Expected 12 visible clock markers, saw {len(transitions)}")
    offsets = [mark / 1e6 - pts[index] for mark, index in zip(marks, transitions)]
    # Keep at least five markers at each end within two 60 Hz frames of
    # the local median. Save every sample so dropped markers remain visible.
    centers = (statistics.median(offsets[:6]), statistics.median(offsets[6:]))
    excluded = [i for i, sample in enumerate(offsets) if abs(sample - centers[i // 6]) > 30]
    drift = centers[1] - centers[0]
    if sum(i < 6 for i in excluded) > 1 or sum(i >= 6 for i in excluded) > 1 or abs(drift) > 34:
        raise ValueError(f"Unstable video clock: {offsets}")
    offset = statistics.median(sample for i, sample in enumerate(offsets) if i not in excluded)
    times = [point + offset for point in pts]
    deltas = {key: changes(region) for key, region in frames.items()}
    deltas["frame_ms"] = times
    (directory / "pixel-changes.json").write_text(json.dumps(deltas))
    rows = []
    selection = iter(commits)
    for i, event in enumerate(events):
        key = event["key"]
        down, up = event["down_ns"] / 1e6, event["up_ns"] / 1e6
        row = {"key": key, "round": event["round"], "hold_ms": up - down,
               "schedule_lateness_ms": (event["down_ns"] - event["due_ns"]) / 1e6}
        if key == "select":
            commit_time = next(selection)["at_ns"] / 1e6
            if commit_time < down or (i + 1 < len(events) and commit_time >= events[i + 1]["down_ns"] / 1e6):
                raise ValueError("Commit cannot be attributed to its selection")
            row.update(selection_down_to_commit=commit_time - down, selection_up_to_commit=commit_time - up)
        else:
            stop = min(events[i + 1]["down_ns"] / 1e6, up + 230)
            row["down_to_feedback"] = associate(times, deltas[key], down, up, 12)
            row["up_to_candidate"] = associate(times, deltas["candidate"], up, stop, 7)
        rows.append(row)

    def metric(name, group):
        return describe([row[name] for row in group if row.get(name) is not None])

    letters = [row for row in rows if row["key"] != "select"]
    metrics = {key: metric(key, rows) for key in ("down_to_feedback", "up_to_candidate", "selection_down_to_commit",
                                                   "selection_up_to_commit", "hold_ms", "schedule_lateness_ms")}
    metrics["warm_up_to_candidate"] = metric("up_to_candidate", letters[1:])
    metrics["down_interval"] = describe(intervals)
    if not metrics["up_to_candidate"]["n"]:
        raise ValueError("No candidate visual changes were detected in letter windows")
    return {"letters": len(letters), "correct_commits": len(commits), "metrics": metrics,
            "first_letter": letters[0], "video_clock": {"offset_ms": offset, "drift_ms": drift,
                                                        "samples_ms": offsets, "excluded_samples": excluded},
            "video_frames": len(pts), "rows": rows}
