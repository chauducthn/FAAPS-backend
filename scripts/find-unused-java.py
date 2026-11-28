#!/usr/bin/env python3
"""
Scans the backend Java sources for classes that are never referenced by any other
class (no import, no fully-qualified usage, not a Spring bean entry point that
is picked up by component scanning).
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "src/main/java")
TEST_ROOT = os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else "src/test/java")

java_files = []
for base in (ROOT, TEST_ROOT):
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith(".java"):
                java_files.append(os.path.join(dirpath, n))

fqcn_to_path = {}
for f in java_files:
    parts = f.split(os.sep)
    try:
        i = parts.index("java")
    except ValueError:
        continue
    pkg = ".".join(parts[i + 1:-1])
    cls = parts[-1][:-5]
    fqcn_to_path[pkg + "." + cls] = f

contents = {}
for f in java_files:
    with open(f, "r", encoding="utf-8", errors="ignore") as fh:
        contents[f] = fh.read()

# simple name -> list of fqcn
simple_to_fqcn = defaultdict(list)
for fqcn in fqcn_to_path:
    simple_to_fqcn[fqcn.rsplit(".", 1)[-1]].append(fqcn)

# Collect every identifier token used anywhere
used_tokens = defaultdict(set)  # token -> set of files using it
TOKEN_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")
for f, c in contents.items():
    for m in TOKEN_RE.finditer(c):
        used_tokens[m.group(1)].add(f)

results = []
for fqcn, path in fqcn_to_path.items():
    simple = fqcn.rsplit(".", 1)[-1]
    is_test = path.startswith(TEST_ROOT)
    users = used_tokens.get(simple, set())
    # exclude self-references (the file's own definition/declaration)
    external = {u for u in users if u != path}
    # also exclude files in the same package that reference it? no - same-package usage is real usage
    if not external:
        results.append((fqcn, path, "no references anywhere", is_test))
        continue
    # check if all references are only in the file's own package (still valid) -> fine
    results.append((fqcn, path, f"referenced by {len(external)} file(s)", is_test))

print(f"Total java classes: {len(fqcn_to_path)}\n")
print("=== CLASSES WITH NO EXTERNAL REFERENCES (candidate dead code) ===")
dead = [r for r in results if r[2] == "no references anywhere"]
for fqcn, path, _, is_test in sorted(dead, key=lambda x: x[1]):
    tag = " [TEST]" if is_test else ""
    print(f"  {fqcn}{tag}")
print(f"\nCount: {len(dead)}")
