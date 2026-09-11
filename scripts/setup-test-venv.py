#!/usr/bin/env python3
"""Provision a pinned, disposable GraalPy distribution and pure-Python test venv."""
import argparse
import hashlib
import platform
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

VERSION = "25.3.4.1"
BASE = "https://github.com/oracle/graalpython/releases/download/graal-25.3.4/"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    root = args.directory.resolve()
    root.mkdir(parents=True, exist_ok=True)
    os_name = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}[platform.system()]
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "amd64", "AMD64": "amd64"}[platform.machine()]
    suffix = ".zip" if os_name == "windows" else ".tar.gz"
    name = f"graalpy3.13-community-{VERSION}-{os_name}-{arch}{suffix}"
    archive = root / name
    if not archive.exists():
        urllib.request.urlretrieve(BASE + name, archive)
    checksum = urllib.request.urlopen(BASE + name + ".sha256", timeout=60).read().decode().split()[0]
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise SystemExit("GraalPy archive checksum mismatch")
    distribution = root / name.removesuffix(suffix).replace("graalpy3.13-community", "graalpy-community3.13")
    if not distribution.exists():
        if suffix == ".zip":
            with zipfile.ZipFile(archive) as z:
                z.extractall(root)
        else:
            with tarfile.open(archive) as tar:
                if hasattr(tarfile, "data_filter"):
                    tar.extractall(root, filter="data")
                else:
                    # Older macOS system Python; the archive is checksum-verified above.
                    for member in tar.getmembers():
                        destination = (root / member.name).resolve()
                        if not destination.is_relative_to(root):
                            raise SystemExit("Unsafe archive path")
                        if member.issym() or member.islnk():
                            link = ((destination.parent if member.issym() else root) / member.linkname).resolve()
                            if not link.is_relative_to(root):
                                raise SystemExit("Unsafe archive link")
                    tar.extractall(root)
    executable = distribution / "bin" / ("graalpy.exe" if os_name == "windows" else "graalpy")
    venv = root / "venv"
    subprocess.run([str(executable), "-m", "venv", str(venv)], check=True, timeout=180)
    # On Windows python.exe is the venv redirector; graalpy.exe is a copied
    # distribution launcher that searches for its JVM relative to Scripts.
    python = venv / ("Scripts/python.exe" if os_name == "windows" else "bin/graalpy")
    subprocess.run([str(python), "-m", "pip", "install", "packaging==25.0"], check=True, timeout=180)
    subprocess.run([sys.executable, "-m", "venv", "--without-pip", str(root / "cpython-venv")], check=True, timeout=60)
    print(f"GraalPy test venv: {venv}")


if __name__ == "__main__":
    main()
