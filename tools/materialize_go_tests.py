"""Create an ephemeral Go workspace with repository-external test files.

InfraNexum keeps production code below ``src/`` and tests below ``tests/``.
Go normally requires same-package ``*_test.go`` files to live beside the package
sources. This utility materializes a disposable workspace so those tests retain
same-package access without polluting the production source tree.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


class GoTestWorkspaceBuilder:
    """Materialize production Go sources and external tests into a temporary tree."""

    def __init__(self, source: Path, tests: Path, output: Path) -> None:
        self.source = source.resolve()
        self.tests = tests.resolve()
        self.output = output.resolve()

    def materialize(self) -> int:
        """Copy sources, inject only ``*_test.go`` files, and return their count."""
        if not self.source.is_dir():
            raise ValueError(f"Go source directory does not exist: {self.source}")
        if not self.tests.is_dir():
            raise ValueError(f"Go test directory does not exist: {self.tests}")
        if self.output.exists():
            raise ValueError(f"output directory already exists: {self.output}")

        unexpected = [
            path
            for path in self.tests.rglob("*")
            if path.is_file() and not path.name.endswith("_test.go")
        ]
        if unexpected:
            rendered = unexpected[0].relative_to(self.tests).as_posix()
            raise ValueError(f"external Go test tree contains non-test file: {rendered}")

        shutil.copytree(self.source, self.output, symlinks=False)
        count = 0
        for test_file in sorted(self.tests.rglob("*_test.go")):
            relative = test_file.relative_to(self.tests)
            destination = self.output / relative
            if destination.exists():
                raise ValueError(
                    "production source tree already contains a test file: "
                    f"{relative.as_posix()}"
                )
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(test_file, destination)
            count += 1
        if count == 0:
            raise ValueError("external Go test tree contains no *_test.go files")
        return count


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--tests", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        count = GoTestWorkspaceBuilder(args.source, args.tests, args.output).materialize()
    except (OSError, ValueError) as error:
        print(f"materialize-go-tests: {error}")
        return 2
    print(f"materialize-go-tests: {count} test files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
