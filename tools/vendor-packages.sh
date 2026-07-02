#!/usr/bin/env bash
# Vendor FHIR IG packages per tools/packages.lock.json.
#
# Modes:
#   (default)          download every package, verify SHA-256 against the lockfile, fail on mismatch
#   --verify           offline: verify the committed .tgz files match the lockfile (no network)
#   --refresh <name>   re-download one package and PRINT its new SHA-256 (does not edit the lockfile;
#                      update the lockfile by hand so the change is explicit in review)
#
# This script is run manually when updating pins — never from CI or the Docker build.
# CI and Docker consume only the committed .tgz files (air-gapped, reproducible builds).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCKFILE="$REPO_ROOT/tools/packages.lock.json"
PKG_DIR="$REPO_ROOT/$(python3 -c "import json;print(json.load(open('$LOCKFILE'))['packageDir'])")"

mode="download"
refresh_target=""
case "${1:-}" in
  --verify) mode="verify" ;;
  --refresh) mode="refresh"; refresh_target="${2:?--refresh requires a package targetFile or name}" ;;
  "") ;;
  *) echo "Unknown argument: $1" >&2; exit 2 ;;
esac

entries() {
  python3 - "$LOCKFILE" <<'EOF'
import json, sys
for p in json.load(open(sys.argv[1]))["packages"]:
    print(f'{p["targetFile"]}\t{p["url"]}\t{p["sha256"]}\t{p["name"]}\t{p["version"]}')
EOF
}

sha() { sha256sum "$1" | cut -d' ' -f1; }

fail=0
while IFS=$'\t' read -r target url expected name version; do
  path="$PKG_DIR/$target"
  case "$mode" in
    verify)
      if [[ ! -f "$path" ]]; then echo "MISSING  $target"; fail=1; continue; fi
      actual="$(sha "$path")"
      if [[ "$actual" == "$expected" ]]; then echo "OK       $target"
      else echo "MISMATCH $target"; echo "  expected $expected"; echo "  actual   $actual"; fail=1; fi
      ;;
    download)
      echo "Downloading $name#$version -> $target"
      tmp="$(mktemp)"
      curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$url"
      actual="$(sha "$tmp")"
      if [[ "$actual" != "$expected" ]]; then
        echo "SHA-256 MISMATCH for $target (source drifted — this is the drift guard)" >&2
        echo "  expected $expected" >&2
        echo "  actual   $actual" >&2
        echo "  If the change is intentional, use --refresh and update the lockfile by hand." >&2
        rm -f "$tmp"; fail=1; continue
      fi
      mkdir -p "$PKG_DIR"; mv "$tmp" "$path"; echo "OK       $target"
      ;;
    refresh)
      if [[ "$target" != "$refresh_target" && "$name" != "$refresh_target" ]]; then continue; fi
      echo "Refreshing $name#$version from $url"
      tmp="$(mktemp)"
      curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$url"
      actual="$(sha "$tmp")"
      pkg_date="$(tar -xzOf "$tmp" package/package.json | python3 -c 'import json,sys;print(json.load(sys.stdin).get("date",""))')"
      mkdir -p "$PKG_DIR"; mv "$tmp" "$path"
      echo "  new sha256:      $actual"
      echo "  new packageDate: $pkg_date"
      echo "  -> update tools/packages.lock.json manually, re-run the test suite, commit both."
      ;;
  esac
done < <(entries)

if [[ "$mode" != "refresh" ]]; then
  [[ $fail -eq 0 ]] && echo "All packages ${mode}ed successfully." || { echo "FAILED." >&2; exit 1; }
fi
