"""Shows Gradle's error report and Kotlin compile errors as GitHub annotations.

Usage: python3 build_errors.py gradle.log
The annotations appear on the pull request's Checks page (and are readable through the API),
so a failed build can be understood without opening the full log. GitHub keeps at most 10 error
annotations per step, so Gradle's "What went wrong" comes first, then compile errors.
"""
import re
import sys

lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
out = []

# Gradle's "What went wrong" blocks, joined into one line each.
i = 0
while i < len(lines):
    if lines[i].startswith("* What went wrong:"):
        block = []
        i += 1
        while i < len(lines) and not lines[i].startswith("* Try:") and len(block) < 25:
            if lines[i].strip():
                block.append(lines[i].strip())
            i += 1
        out.append(("error", "What went wrong", " | ".join(block)[:1800]))
    i += 1

# Kotlin / Java compile errors, then warnings printed by the build script.
out += [("error", "Compile", ln) for ln in lines if re.match(r"^(e: |ERROR:)", ln)]
out += [("warning", "Build", ln) for ln in lines if re.match(r"^(w: file|WARNING: MarketWatcher)", ln)]

if not out:
    out = [("error", "Log tail", ln) for ln in lines[-30:]]
for level, title, text in out[:40]:
    print(f"::{level} title={title}::{text[:1800]}")
