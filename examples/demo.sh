#!/usr/bin/env bash
# Live demo: validate a few ePI bundles against a running validator and show the verdicts.
# Point it at your Cloud Run URL (or leave blank for a local instance on :8080):
#
#   ./examples/demo.sh https://epi-validator-XXXX-ew.a.run.app
#
# Needs only Python 3 (no dependencies). Each step prints the verdict and the located issues.
set -euo pipefail
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BASE="${1:-${BASE_URL:-http://localhost:8080}}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
run() { python3 examples/validate_bundle.py "$@" --base-url "$BASE" || true; }

say "Validator we are talking to ($BASE):"
curl -sS "$BASE/api/v1/epi/info"; echo

say "1. A valid ePI leaflet  ->  expect PASS_WITH_WARNINGS"
echo "   Official HL7 Type 1 example (cleaned). The warnings are offline terminology"
echo "   (EDQM / MedDRA code systems are not distributable, so unknown codes are warnings)."
run examples/good-bundle.json --epi-type 1

say "2. The same leaflet with two AI-style defects  ->  expect FAIL"
echo "   An empty document section, and an author reference to an Organization that is not"
echo "   in the bundle. The validator locates both, by line."
run examples/broken-bundle.json --epi-type 1

say "3. The valid leaflet mislabelled as a richer Type 3  ->  expect FAIL"
echo "   Type 3 requires machine-readable clinical/product data this leaflet does not contain,"
echo "   so the type contract is rejected (EPI-TYPE-001/002)."
run examples/good-bundle.json --epi-type 3

say "Done."
echo "Bonus: the unmodified official IG examples in examples/ig/ also FAIL, because they"
echo "contain real reference and narrative defects the official engine flags. Try:"
echo "   python3 examples/validate_bundle.py examples/ig/official-type1.json --epi-type 1 --base-url $BASE"
