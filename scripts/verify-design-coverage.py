#!/usr/bin/env python3
"""Fails if any design-doc edge case (E1-E24, section 7) or failure mode (F1-F13, section 8) has no test tagged @Covers.

Turns "every row of the catalog gets a test" (design doc section 17) into a mechanical check.
Usage: python3 scripts/verify-design-coverage.py   (exit code 1 on any gap)
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TESTS = ROOT / "url-shortener-service" / "src" / "test"
REQUIRED = [f"E{i}" for i in range(1, 25)] + [f"F{i}" for i in range(1, 14)]

# Rows that are properties of the deployment topology, not of the code. Each needs a written rationale and a doc pointer.
INFRA_ONLY = {
    "F10": "multi-AZ / replica promotion is infrastructure; documented in docs/architecture-diagrams.md (deployment topology) "
           "and .claude/plugins/release-checklist.md. The application-level half (replica fallback) is tested under F4.",
}

covered = {}
for f in TESTS.rglob("*.java"):
    text = f.read_text()
    for m in re.finditer(r"@Covers\(\s*\{?([^})]*)\}?\s*\)", text):
        for tag in re.findall(r'"([EF]\d+)"', m.group(1)):
            covered.setdefault(tag, set()).add(f.name)

missing = [r for r in REQUIRED if r not in covered and r not in INFRA_ONLY]
print(f"{'row':5} tests")
for r in REQUIRED:
    if r in covered:
        print(f"{r:5} {len(covered[r]):2} file(s): {', '.join(sorted(covered[r]))}")
    elif r in INFRA_ONLY:
        print(f"{r:5} INFRA-ONLY: {INFRA_ONLY[r]}")
    else:
        print(f"{r:5} ** NO TEST **")
if missing:
    print(f"\nFAIL: {len(missing)} design row(s) without a test: {', '.join(missing)}")
    sys.exit(1)
print(f"\nOK: all {len(REQUIRED)} rows covered ({len(INFRA_ONLY)} infrastructure-only).")
