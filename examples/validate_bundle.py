#!/usr/bin/env python3
"""Validate a FHIR ePI bundle against the validator service. Stdlib only — no dependencies.

Usage:
    python3 examples/validate_bundle.py examples/good-bundle.json --epi-type 1
    python3 examples/validate_bundle.py examples/broken-bundle.json --epi-type 1

Exits 0 on PASS / PASS_WITH_WARNINGS, 1 on FAIL, 2 on transport/misuse errors.
Gate on the envelope's verdict — never on HTTP status.
"""

import argparse
import contextlib
import json
import signal
import sys
import urllib.error
import urllib.request

# Behave well in shell pipelines (e.g. `... | head`)
with contextlib.suppress(AttributeError, ValueError):
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("bundle", help="path to a FHIR Bundle JSON file")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--epi-type", default="auto",
                        help="1|2|3|auto — production callers pass the contracted type")
    args = parser.parse_args()

    with open(args.bundle, "rb") as f:
        body = f.read()

    url = f"{args.base_url}/api/v1/epi/validate?epiType={args.epi_type}"
    request = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/fhir+json"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            envelope = json.load(response)
    except urllib.error.HTTPError as e:
        if e.code == 400:  # unparseable input still returns the normal envelope
            envelope = json.load(e)
        else:
            print(f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}", file=sys.stderr)
            return 2
    except urllib.error.URLError as e:
        print(f"Cannot reach validator at {args.base_url}: {e.reason}", file=sys.stderr)
        return 2

    print(f"verdict: {envelope['verdict']}  "
          f"(requested {envelope['requestedEpiType']}, detected {envelope['detectedEpiType']}, "
          f"effective {envelope['effectiveEpiType']})")
    relevant = [i for i in envelope["issues"] if i["severity"] in ("fatal", "error", "warning")]
    for n, issue in enumerate(relevant[:15], start=1):
        location = issue.get("fhirPath") or ""
        line = f" (line {issue['line']})" if issue.get("line") else ""
        print(f"{n:2}. [{issue['severity']}/{issue['source']}] {location}{line}: "
              f"{issue['message'][:140]}")
    if len(relevant) > 15:
        print(f"    ... and {len(relevant) - 15} more issue(s)")
    return 0 if envelope["verdict"] != "FAIL" else 1


if __name__ == "__main__":
    sys.exit(main())
