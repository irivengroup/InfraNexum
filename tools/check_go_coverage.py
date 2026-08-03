
from __future__ import annotations

import re
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        raise SystemExit("usage: check_go_coverage.py <summary-file> <minimum-percent>")
    path = Path(argv[0])
    minimum = float(argv[1])
    text = path.read_text(encoding="utf-8")
    match = re.search(r"^total:\s+\(statements\)\s+([0-9.]+)%$", text, re.MULTILINE)
    if not match:
        raise SystemExit("Go coverage total not found")
    actual = float(match.group(1))
    if actual < minimum:
        raise SystemExit(f"Go coverage {actual:.1f}% is below required {minimum:.1f}%")
    print(f"Go coverage {actual:.1f}% >= {minimum:.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
