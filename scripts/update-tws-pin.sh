#!/usr/bin/env bash
# Bump package.nix + TWS-NIX.md to IBKR's current Offline Stable installer.
# Prefetches with --executable so the NAR hash matches fetchurl { executable = true; }.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

INSTALLER_URL="https://download2.interactivebrokers.com/installers/tws/stable-standalone/tws-stable-standalone-linux-x64.sh"
VERSION_URL="https://download2.interactivebrokers.com/installers/tws/stable-standalone/version.json"

json="$(curl -fsSL "${VERSION_URL}")"
new_version="$(printf '%s' "${json}" | sed -n 's/.*"buildVersion":"\([^"]*\)".*/\1/p')"
if [[ -z "${new_version}" ]]; then
  echo "Could not parse buildVersion from version.json:" >&2
  printf '%s\n' "${json}" >&2
  exit 1
fi

old_version="$(sed -n 's/^[[:space:]]*version = "\([^"]*\)";/\1/p' package.nix | head -1)"
if [[ -z "${old_version}" ]]; then
  echo "Could not read version from package.nix" >&2
  exit 1
fi

if [[ "${new_version}" == "${old_version}" ]]; then
  echo "TWS Offline Stable already pinned at ${old_version}; nothing to do."
  exit 0
fi

echo "Updating TWS Offline Stable ${old_version} -> ${new_version}"
echo "Prefetching installer (executable NAR hash)..."
raw="$(nix-prefetch-url --executable --type sha256 "${INSTALLER_URL}")"
sri="$(nix hash to-sri "sha256:${raw}")"

python3 - "${new_version}" "${sri}" <<'PY'
import pathlib, sys

version, sri = sys.argv[1], sys.argv[2]
pkg = pathlib.Path("package.nix").read_text()
pkg_new, n = __import__("re").subn(
    r'^(\s*)version = "[^"]*";',
    rf'\1version = "{version}";',
    pkg,
    count=1,
    flags=__import__("re").M,
)
if n != 1:
    raise SystemExit("failed to replace version in package.nix")
pkg_new, n = __import__("re").subn(
    r'^(\s*)hash = "sha256-[^"]*";',
    rf'\1hash = "{sri}";',
    pkg_new,
    count=1,
    flags=__import__("re").M,
)
if n != 1:
    raise SystemExit("failed to replace hash in package.nix")
pathlib.Path("package.nix").write_text(pkg_new)

doc = pathlib.Path("TWS-NIX.md").read_text()
doc, n = __import__("re").subn(
    r"(- TWS Offline Stable: `)[^`]+(`)",
    rf"\g<1>{version}\2",
    doc,
    count=1,
)
if n != 1:
    raise SystemExit("failed to replace version in TWS-NIX.md")
doc, n = __import__("re").subn(
    r"(- Installer SHA-256: `)[^`]+(`)",
    rf"\g<1>{sri}\2",
    doc,
    count=1,
)
if n != 1:
    raise SystemExit("failed to replace hash in TWS-NIX.md")
pathlib.Path("TWS-NIX.md").write_text(doc)
PY

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "TWS_VERSION=${new_version}" >> "${GITHUB_ENV}"
fi

echo "Pinned ${new_version} hash ${sri}"
