"""Measure repeated packaged disposable-file checks; never operates on user files.

Requires psutil. Process-tree RSS is a sampled sum (shared pages may be counted
twice); I/O counters include runtime loading and are not physical disk traffic.
"""
import argparse
import json
import math
import platform
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

import psutil


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("executable", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--runs", type=int, default=12)
    args = parser.parse_args()
    if not 2 <= args.runs <= 30:
        parser.error("runs must be between 2 and 30")
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    for number in range(args.runs):
        folder = args.output / f"run-{number + 1:02d}"
        folder.mkdir(exist_ok=True)
        with (folder / "console.txt").open("w", encoding="utf-8") as console:
            start = time.monotonic()
            child = subprocess.Popen([str(args.executable.resolve()), "--self-test", str(folder.resolve())], stdout=console, stderr=subprocess.STDOUT)
            counters = {}
            peak_rss = 0
            while child.poll() is None:
                try:
                    root = psutil.Process(child.pid)
                    processes = [root, *root.children(recursive=True)]
                except psutil.Error:
                    processes = []
                rss = 0
                for process in processes:
                    try:
                        cpu = process.cpu_times()
                        io = process.io_counters()
                        counters[process.pid] = (cpu.user + cpu.system, io.read_bytes, io.write_bytes)
                        rss += process.memory_info().rss
                    except psutil.Error:
                        pass
                peak_rss = max(peak_rss, rss)
                time.sleep(0.02)
                if time.monotonic() - start > 30:
                    child.kill()
                    raise RuntimeError("Packaged check exceeded 30 seconds")
        report = (folder / "desktop-self-test.txt").read_text(encoding="utf-8")
        if child.returncode != 0 or "FAIL |" in report or report.count("PASS |") != 5:
            raise RuntimeError(f"Packaged run {number + 1} failed")
        def value(label):
            match = re.search(r"^" + re.escape(label) + r": (\d+)$", report, re.M)
            if not match:
                raise ValueError(label)
            return int(match.group(1))
        events = json.loads((folder / "desktop-observations.json").read_text(encoding="utf-8"))
        first_alert = value("First observed alert epoch ms")
        pause = value("Cooperative pause acknowledgement epoch ms")
        def observed(at):
            sizes = {}
            for event in events:
                if event["atMs"] <= at and event["kind"] != "OVERFLOW":
                    sizes[event["path"]] = max(sizes.get(event["path"], 0), event.get("bytes", 0))
            return {"paths": len(sizes), "wholeFileSizeBytes": sum(sizes.values())}
        rows.append({"run": number + 1, "alertMs": value("First write to alert ms"),
                     "pauseMs": value("First write to pause acknowledgement ms"),
                     "writesAtAlert": value("Evaluator worker writes timestamped before/equal alert"),
                     "writesAtPause": value("Evaluator worker writes timestamped before/equal pause acknowledgement"),
                     "observedAtAlert": observed(first_alert), "observedAtPause": observed(pause),
                     "wallSeconds": time.monotonic() - start, "sampledPeakProcessTreeRssBytes": peak_rss,
                     "sampledCpuSeconds": sum(x[0] for x in counters.values()),
                     "sampledReadBytes": sum(x[1] for x in counters.values()),
                     "sampledWriteBytes": sum(x[2] for x in counters.values()), "passedChecks": 5})
        print(f"Run {number + 1}/{args.runs}: alert {rows[-1]['alertMs']} ms; pause {rows[-1]['pauseMs']} ms", flush=True)
    def percentile(key, fraction):
        return sorted(row[key] for row in rows)[math.ceil(len(rows) * fraction) - 1]
    result = {"recordedAtUtc": datetime.now(timezone.utc).isoformat(), "platform": platform.platform(),
              "logicalProcessors": psutil.cpu_count(), "physicalMemoryBytes": psutil.virtual_memory().total,
              "method": "Sequential cold executable launches of one fixed 24-file synthetic lab. Nearest-rank percentiles. 20 ms process-tree sampling. Not real malware or interactive UI latency.",
              "limits": "RSS sums may double-count shared pages. Sampling misses short-lived processes and final counters. CPU/I/O include startup, hash verification, restoration and tamper checks; not steady-state overhead. Reported file sizes are not exact overwritten bytes or permanent loss. Labs are retained for inspection.",
              "alertP50Ms": percentile("alertMs", .5), "alertP95Ms": percentile("alertMs", .95),
              "pauseP50Ms": percentile("pauseMs", .5), "pauseP95Ms": percentile("pauseMs", .95),
              "runs": rows}
    (args.output / "measurements.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "runs"}, indent=2))


if __name__ == "__main__":
    main()
