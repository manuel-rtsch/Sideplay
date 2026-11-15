#!/usr/bin/env python3
"""Report wall-clock length of Maestro flow runs from commands.json artifacts.

Maestro writes one commands.json per flow under --test-output-dir. Each entry has
metadata.timestamp (epoch ms) and metadata.duration (ms), including nested
runFlow children. The flow's wall time is the span from the first command start
to the last command end; summing durations would double-count parents and
children.

Usage:
  scripts/flow-times.py build/maestro-suite
  scripts/flow-times.py build/maestro-suite --max-seconds 45
  scripts/flow-times.py build/maestro-suite --verbose

With --max-seconds the script exits non-zero if any flow exceeds the limit, so it
can gate a build or a recording batch.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path



def load(commands_file: Path) -> dict:
    entries = json.loads(commands_file.read_text())
    spans = []
    totals: dict[str, int] = {}
    for entry in entries:
        meta = entry.get("metadata") or {}
        timestamp = meta.get("timestamp")
        duration = meta.get("duration")
        if timestamp is not None and duration is not None:
            spans.append((timestamp, timestamp + duration))
        command = entry.get("command") or {}
        if command:
            kind = next(iter(command))
            totals[kind] = totals.get(kind, 0) + (duration or 0)
        # Nested runFlow children are already included in the parent's duration;
        # their timestamps still matter for the wall-clock span, so collect them.
        for nested in _nested(entry):
            nested_meta = nested.get("metadata") or {}
            if nested_meta.get("timestamp") is not None and nested_meta.get("duration") is not None:
                spans.append(
                    (nested_meta["timestamp"], nested_meta["timestamp"] + nested_meta["duration"])
                )
    wall_ms = (max(end for _, end in spans) - min(start for start, _ in spans)) if spans else 0
    return {"wall_ms": wall_ms, "totals": totals, "commands": len(entries)}


def _nested(entry: dict):
    command = entry.get("command") or {}
    for key in ("runFlowCommand", "repeatCommand"):
        block = command.get(key)
        if isinstance(block, dict):
            for child in block.get("commands") or []:
                yield child
                yield from _nested(child)


def discover(paths: list[str]) -> list[Path]:
    found: list[Path] = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            found.extend(sorted(path.glob("**/commands.json")))
        elif path.name == "commands.json":
            found.append(path)
        else:
            raise SystemExit(f"not a commands.json or a directory: {path}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="test-output directories or commands.json files")
    parser.add_argument("--max-seconds", type=float, default=None)
    parser.add_argument("--verbose", action="store_true", help="show the costliest command kinds")
    args = parser.parse_args()

    files = discover(args.paths)
    if not files:
        print("no commands.json found", file=sys.stderr)
        return 2

    rows = []
    for commands_file in files:
        flow = commands_file.parent.name
        data = load(commands_file)
        rows.append((flow, data))

    width = max(len(name) for name, _ in rows)
    print(f"{'flow'.ljust(width)}  wall   cmds")
    print(f"{'-' * width}  -----  ----")
    failures = []
    for name, data in rows:
        seconds = data["wall_ms"] / 1000
        print(f"{name.ljust(width)}  {seconds:5.1f}s  {data['commands']:4d}")
        if args.max_seconds is not None and seconds > args.max_seconds:
            failures.append((name, seconds))

    if args.verbose:
        print()
        for name, data in rows:
            print(name)
            ranked = sorted(data["totals"].items(), key=lambda item: item[1], reverse=True)
            for kind, duration in ranked:
                if duration <= 0:
                    continue
                label = kind.replace("Command", "").replace("tapOnElement", "tap")
                print(f"  {label:<28} {duration / 1000:6.1f}s")

    if failures:
        print()
        for name, seconds in failures:
            print(
                f"FAIL {name}: {seconds:.1f}s exceeds {args.max_seconds:.1f}s",
                file=sys.stderr,
            )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
