"""Prints a summary of the JUnit results as GitHub annotations (shown on the pull request's Checks page).

Usage: python3 test_summary.py <folder with TEST-*.xml>
One notice with the totals, one notice per test class, and a warning or error for each skipped
or failed test. Exits with 1 if there are no results at all.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

folder = sys.argv[1]
files = sorted(glob.glob(os.path.join(folder, "TEST-*.xml")))
if not files:
    print(f"::error title=Unit tests::No test results found in {folder}")
    sys.exit(1)

total = passed = skipped = failed = 0
for path in files:
    suite = ET.parse(path).getroot()
    name = suite.get("name", "?").rsplit(".", 1)[-1]
    s_total = s_skipped = s_failed = 0
    for case in suite.iter("testcase"):
        s_total += 1
        test = f"{name}.{case.get('name')}"
        if case.find("skipped") is not None:
            s_skipped += 1
            msg = (case.find("skipped").get("message") or "").replace("\n", " ")[:200]
            print(f"::warning title=Skipped test::{test}: {msg}")
        elif case.find("failure") is not None or case.find("error") is not None:
            s_failed += 1
            el = case.find("failure") if case.find("failure") is not None else case.find("error")
            msg = (el.get("message") or "").replace("\n", " ")[:300]
            print(f"::error title=Failed test::{test}: {msg}")
    s_passed = s_total - s_skipped - s_failed
    print(f"::notice title={name}::{s_passed} passed, {s_skipped} skipped, {s_failed} failed")
    total += s_total
    passed += s_passed
    skipped += s_skipped
    failed += s_failed

print(f"::notice title=Unit tests::{total} tests: {passed} passed, {skipped} skipped, {failed} failed")
