#!/usr/bin/env python3
"""Validate the canonical GraalPy install ZIP."""

from __future__ import annotations

import argparse
import hashlib
import json
import io
import zipfile
from pathlib import Path
from xml.etree import ElementTree


PLUGIN_ROOT = "plugins/transforms/graalpy"
PLUGIN_ARTIFACT_ID = "hop-transform-graalpy"
ICON_PATH = "ch/so/agi/hop/python/transform/graalpy/icons/graalpy.svg"


def project_version(pom: Path) -> str:
    root = ElementTree.parse(pom).getroot()
    namespace = "{http://maven.apache.org/POM/4.0.0}"
    version = root.findtext(f"{namespace}version")
    if not version:
        raise SystemExit(f"Could not resolve project.version from {pom}")
    return version


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def exactly_one_zip(root: Path, pattern: str) -> Path:
    matches = sorted(root.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one ZIP matching {pattern!r}, found {len(matches)}")
    return matches[0]


def validate_zip(zip_path: Path, version: str) -> dict[str, object]:
    expected_name = f"{PLUGIN_ARTIFACT_ID}-{version}.zip"
    if zip_path.name != expected_name:
        raise SystemExit(f"Expected ZIP {expected_name}, found {zip_path.name}")

    expected_plugin_jar = f"{PLUGIN_ROOT}/{PLUGIN_ARTIFACT_ID}-{version}.jar"
    with zipfile.ZipFile(zip_path) as archive:
        names = archive.namelist()
        files = [name for name in names if not name.endswith("/")]
        for name in files:
            if name.startswith("/") or ".." in Path(name).parts:
                raise SystemExit(f"Unsafe ZIP path: {name}")

        plugin_jars = [
            name
            for name in files
            if name.startswith(f"{PLUGIN_ROOT}/")
            and name.count("/") == PLUGIN_ROOT.count("/") + 1
            and name.endswith(".jar")
        ]
        if plugin_jars != [expected_plugin_jar]:
            raise SystemExit(f"Expected exactly one plugin JAR {expected_plugin_jar}, found {plugin_jars}")
        if f"{PLUGIN_ROOT}/version.xml" not in files:
            raise SystemExit("The ZIP does not contain version.xml")

        runtime_jars = [
            name for name in files if name.startswith(f"{PLUGIN_ROOT}/lib/") and name.endswith(".jar")
        ]
        if not runtime_jars:
            raise SystemExit("The ZIP does not contain GraalPy runtime JARs")
        if any(name.endswith("-tests.jar") for name in runtime_jars):
            raise SystemExit("The ZIP contains a test JAR")
        forbidden_runtime = [
            name
            for name in runtime_jars
            if Path(name).name.startswith(("hop-", "swt", "jts-", "sogeo-geometry"))
        ]
        if forbidden_runtime:
            raise SystemExit(f"The ZIP embeds Hop/SWT runtime libraries: {forbidden_runtime}")

        plugin_bytes = archive.read(expected_plugin_jar)
        with zipfile.ZipFile(io.BytesIO(plugin_bytes)) as plugin:
            plugin_names = plugin.namelist()
            required = {
                "META-INF/jandex.idx",
                ICON_PATH,
                "ch/so/agi/hop/python/transform/graalpy/GraalPyTransformMeta.class",
            }
            missing = sorted(required - set(plugin_names))
            if missing:
                raise SystemExit(f"Plugin JAR is missing: {missing}")
            forbidden_prefixes = ("org/apache/hop/", "org/eclipse/swt/")
            forbidden = [
                name for name in plugin_names if name.endswith(".class") and name.startswith(forbidden_prefixes)
            ]
            if forbidden:
                raise SystemExit(f"Plugin JAR embeds Hop/SWT classes: {forbidden[:5]}")

        return {
            "zipFile": str(zip_path),
            "sha256": sha256(zip_path),
            "pluginJar": expected_plugin_jar,
            "pluginJarSha256": hashlib.sha256(plugin_bytes).hexdigest(),
            "runtimeJars": sorted(runtime_jars),
        }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", type=Path)
    parser.add_argument("--pom", type=Path, default=Path("pom.xml"))
    args = parser.parse_args()

    version = project_version(args.pom)
    zip_path = args.zip or exactly_one_zip(
        Path("assemblies/assemblies-transform-graalpy/target"), "hop-transform-graalpy-*.zip"
    )
    result = {
        "schemaVersion": 1,
        "artifactId": PLUGIN_ARTIFACT_ID,
        "version": version,
        "hopVersion": "2.19.0",
        **validate_zip(zip_path, version),
    }
    output = Path("target/package-verification.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
