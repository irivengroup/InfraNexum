#!/usr/bin/env python3
"""Print uncovered JaCoCo lines/branches from every generated module report."""
from __future__ import annotations

import pathlib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
reports = sorted(ROOT.glob("src/**/target/site/jacoco/jacoco.xml"))
if not reports:
    print("No JaCoCo XML reports found.")
    raise SystemExit(0)

for report in reports:
    tree = ET.parse(report)
    root = tree.getroot()
    module = report.parents[3].relative_to(ROOT)
    gaps: list[tuple[str, int, int, int]] = []
    for package in root.findall("package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            source_name = source.get("name", "")
            for line in source.findall("line"):
                missed_instructions = int(line.get("mi", "0"))
                missed_branches = int(line.get("mb", "0"))
                if missed_instructions or missed_branches:
                    gaps.append((f"{package_name}/{source_name}", int(line.get("nr", "0")), missed_instructions, missed_branches))
    print(f"=== JaCoCo gaps: {module} ({len(gaps)} lines) ===")
    for path, line, missed_i, missed_b in gaps[:250]:
        print(f"{path}:{line}: missed-instructions={missed_i} missed-branches={missed_b}")
    if len(gaps) > 250:
        print(f"... {len(gaps) - 250} additional gap lines omitted")
