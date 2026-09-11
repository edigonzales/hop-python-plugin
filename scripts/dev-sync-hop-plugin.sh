#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [HOP_HOME]"
  exit 1
fi

if [[ $# -eq 1 ]]; then
  HOP_HOME="$1"
else
  HOP_HOME="${HOP_HOME:-}"
fi

if [[ -z "${HOP_HOME}" ]]; then
  echo "HOP_HOME is not set. Pass it as argument or export HOP_HOME."
  exit 1
fi

if [[ ! -d "${HOP_HOME}" ]]; then
  echo "HOP_HOME does not exist: ${HOP_HOME}"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

PLUGIN_DIR="${HOP_HOME}/plugins/transforms/graalpy"
ZIP_PATH="assemblies/assemblies-transform-graalpy/target/hop-transform-graalpy-0.1.0-SNAPSHOT.zip"

mvn -U -B -ntp -q -DskipTests package

if [[ ! -f "${ZIP_PATH}" ]]; then
  echo "Plugin ZIP not found: ${ZIP_PATH}"
  exit 1
fi

rm -rf "${PLUGIN_DIR}"
mkdir -p "${PLUGIN_DIR}"
unzip -q -o "${ZIP_PATH}" -d "${HOP_HOME}"

echo "GraalPy transform plugin synced to ${PLUGIN_DIR}"
