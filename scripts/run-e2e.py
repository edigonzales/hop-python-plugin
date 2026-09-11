#!/usr/bin/env python3
"""Run the GraalPy transform from an installed Hop plugin ZIP."""

from __future__ import annotations

import argparse
import csv
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--hop-home", type=Path, required=True)
    parser.add_argument("--plugin-zip", type=Path, required=True)
    args = parser.parse_args()

    hop_run = args.hop_home / "hop-run.sh"
    if not hop_run.is_file():
        raise SystemExit(f"Hop launcher not found: {hop_run}")
    if not args.plugin_zip.is_file():
        raise SystemExit(f"Plugin ZIP not found: {args.plugin_zip}")

    with tempfile.TemporaryDirectory(prefix="hop-python-e2e-") as temp:
        root = Path(temp)
        input_dir = root / "input"
        output_dir = root / "output"
        input_dir.mkdir()
        output_dir.mkdir()
        (input_dir / "values.csv").write_text("value\n2\n5\n", encoding="utf-8")

        with zipfile.ZipFile(args.plugin_zip) as archive:
            names = archive.namelist()
            expected_root = "plugins/transforms/graalpy/"
            if not any(name.startswith(expected_root) for name in names):
                raise SystemExit(f"Plugin ZIP does not contain {expected_root}")
            archive.extractall(args.hop_home)

        definition = Path(__file__).resolve().parents[1] / "e2e" / "graalpy.hpl"
        command = [
            str(hop_run),
            "-r",
            "local",
            "-f",
            str(definition),
            "-p",
            f"E2E_INPUT_DIR={input_dir}",
            "-p",
            f"E2E_OUTPUT_DIR={output_dir}",
        ]
        completed = subprocess.run(command, text=True, capture_output=True)
        print(completed.stdout, end="")
        print(completed.stderr, end="")
        if completed.returncode != 0:
            raise SystemExit(f"hop-run failed with exit code {completed.returncode}")

        output_file = output_dir / "graalpy.csv"
        if not output_file.is_file():
            raise SystemExit(f"Expected E2E output was not created: {output_file}")
        with output_file.open(newline="", encoding="utf-8") as stream:
            rows = list(csv.DictReader(stream, delimiter=";"))
        actual = [(row.get("value"), row.get("doubled")) for row in rows]
        expected = [("2", "4"), ("5", "10")]
        if actual != expected:
            raise SystemExit(f"Unexpected GraalPy result: expected {expected}, found {actual}")
        print("Installed Hop GraalPy E2E OK")


if __name__ == "__main__":
    main()
