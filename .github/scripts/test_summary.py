#!/usr/bin/env python3
"""Render Surefire XML as a markdown table on the workflow summary.

Stdlib only, and deliberately not a third-party action: CI runs on main rather
than on pull requests, so there is no PR for an action's inline annotations to
land on, and the table is the whole of what is wanted.

Never fails the build. Whether the build passed is the Maven step's answer;
this one only reports what happened.
"""
import os
import sys
import glob
import xml.etree.ElementTree as ET

MAX_ROWS = 50
MAX_MESSAGE = 300


def collect(pattern):
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
    broken = []
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # A run killed mid-write leaves truncated XML. Skip it rather than
            # crash: a partial report is still worth summarising.
            continue
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(root.get(key) or 0)
        try:
            totals["time"] += float(root.get("time") or 0)
        except ValueError:
            pass
        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                message = (node.get("message") or node.get("type") or "").strip()
                message = " ".join(message.split())
                if len(message) > MAX_MESSAGE:
                    message = message[:MAX_MESSAGE] + "…"
                broken.append({
                    "cls": (case.get("classname") or "").split(".")[-1],
                    "name": case.get("name") or "",
                    "kind": kind,
                    "message": message or "(no message)",
                })
    return totals, broken


def render(totals, broken):
    out = []
    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    if totals["tests"] == 0:
        return "## Tests\n\nNo test reports found — the build most likely failed before tests ran.\n"

    verdict = "❌ Failing" if broken else "✅ Passing"
    out.append(f"## {verdict}\n")
    out.append("| Total | Passed | Failed | Errors | Skipped | Time |")
    out.append("|------:|-------:|-------:|-------:|--------:|-----:|")
    out.append(
        f"| {totals['tests']} | {passed} | {totals['failures']} | "
        f"{totals['errors']} | {totals['skipped']} | {totals['time']:.1f}s |"
    )

    if broken:
        out.append(f"\n### What broke\n")
        out.append("| Test | Assertion |")
        out.append("|------|-----------|")
        for item in broken[:MAX_ROWS]:
            # Pipes and newlines would split the row; the message is already flattened.
            message = item["message"].replace("|", "\\|")
            out.append(f"| `{item['cls']}.{item['name']}` | {message} |")
        if len(broken) > MAX_ROWS:
            out.append(f"\n_{len(broken) - MAX_ROWS} further failures not listed._")
    return "\n".join(out) + "\n"


def main():
    pattern = sys.argv[1] if len(sys.argv) > 1 else "**/target/surefire-reports/TEST-*.xml"
    totals, broken = collect(pattern)
    summary = render(totals, broken)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(summary)
    print(summary)


if __name__ == "__main__":
    main()
