#!/usr/bin/env python3
"""Apply the ImageShare GPL header to selected Kotlin files.

This helper is intentionally conservative: pass explicit file paths and review
the resulting diff before committing.
"""

from __future__ import annotations

import argparse
from pathlib import Path


HEADER = """/*
 * ImageShare
 * Copyright (C) 2024-2026 adsamcik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

"""


def apply_header(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")
    if "GNU General Public License" in content[:1000]:
        return False
    path.write_text(HEADER + content, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()

    changed = 0
    for file_path in args.files:
        if file_path.suffix != ".kt":
            raise SystemExit(f"Refusing non-Kotlin file: {file_path}")
        if apply_header(file_path):
            changed += 1

    print(f"Updated {changed} file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
