#!/usr/bin/env python3
"""Agentic pipeline demo: validate -> LLM feedback -> fix -> revalidate.

Simulates the repair loop of the extraction pipeline:

1. An "extraction agent" produced a candidate ePI Type 2 bundle with two realistic defects
   (a section whose content was lost, and a reference to an entry that does not exist).
2. The bundle is POSTed to the validation gate (validationMode=gate, explicit epiType).
3. On failure, ``GateFailed.feedback`` — a numbered, located defect list — is exactly what
   would be injected into the repair LLM's prompt. Here ``apply_stub_fix`` stands in for
   that LLM and repairs the defects the feedback points at.
4. Revalidate until the gate passes (max 3 attempts).

Mapping onto LangGraph (or any agent framework):

    builder = StateGraph(PipelineState)
    builder.add_node("extract",  extract_from_pdf)          # produces candidate bundle
    builder.add_node("validate", validate_node)             # client.validate_bundle(...)
    builder.add_node("fix",      fix_node)                  # LLM prompt includes
                                                            # result.to_llm_feedback()
    builder.add_edge("extract", "validate")
    builder.add_conditional_edges(
        "validate",
        lambda s: "done" if s["result"].passed else "fix",  # gate on verdict, never HTTP
        {"done": END, "fix": "fix"},
    )
    builder.add_edge("fix", "validate")                     # loop until gate passes

The validator stays the deterministic, non-AI control point: the agent proposes,
this service accepts or rejects, and its audit block records exactly what was checked.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
import uuid
from pathlib import Path

from epi_validator_client import EpiValidatorClient, GateFailed, ValidationResult, gate

EXAMPLE = Path(__file__).parent / "bundles" / "1.1.0" / "Bundle-bundle-epi-type2-example-blister-carton.json"


def seed_defects(bundle: dict) -> tuple[dict, dict]:
    """Damage the bundle the way a lossy extraction run would; keep originals for the stub fix."""
    broken = copy.deepcopy(bundle)
    originals = {
        "section": copy.deepcopy(broken["entry"][0]["resource"]["section"][0]),
        "author_reference": broken["entry"][0]["resource"]["author"][0]["reference"],
    }
    # Defect 1: the leaflet section lost its narrative and entries (extraction loss)
    section = broken["entry"][0]["resource"]["section"][0]
    section.pop("text", None)
    section.pop("entry", None)
    section.pop("section", None)
    # Defect 2: the author points at an organization that never made it into the bundle
    broken["entry"][0]["resource"]["author"][0]["reference"] = "Organization/lost-in-extraction"
    return broken, originals


def apply_stub_fix(bundle: dict, result: ValidationResult, originals: dict) -> dict:
    """Stand-in for the repair LLM: fix exactly what the feedback points at.

    A real pipeline would send ``result.to_llm_feedback()`` to the model along with the
    bundle and let it emit corrections; here we repair deterministically so the demo is
    reproducible offline.
    """
    fixed = copy.deepcopy(bundle)
    rule_ids = {issue.rule_id for issue in result.errors}
    if "EPI-DOC-003" in rule_ids:
        print("  [fix-agent] restoring lost section content (EPI-DOC-003)")
        fixed["entry"][0]["resource"]["section"][0] = copy.deepcopy(originals["section"])
    if "EPI-DOC-005" in rule_ids:
        print("  [fix-agent] repairing dangling author reference (EPI-DOC-005)")
        fixed["entry"][0]["resource"]["author"][0]["reference"] = originals["author_reference"]
    return fixed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--max-attempts", type=int, default=3)
    args = parser.parse_args()

    trace_id = f"demo-{uuid.uuid4().hex[:12]}"
    print(f"Pipeline demo against {args.base_url} (traceId={trace_id})")

    client = EpiValidatorClient(args.base_url)
    print("Waiting for validator readiness (validators warm up on start)...")
    client.wait_ready()

    candidate, originals = seed_defects(json.loads(EXAMPLE.read_text()))
    verdict_trail: list[str] = []

    for attempt in range(1, args.max_attempts + 1):
        print(f"\n=== Attempt {attempt}: validating candidate ePI (gate mode, epiType=2) ===")
        result = client.validate_bundle(
            candidate,
            epi_type="2",              # the contracted type — the gate never guesses
            validation_mode="gate",
            ig_version="1.1.0",
            include_operation_outcome=False,
            trace_id=trace_id,
        )
        verdict_trail.append(result.verdict.value)
        try:
            gate(result)
            print(f"Gate PASSED with verdict {result.verdict.value} "
                  f"({result.stats.total.warnings} governed warnings)")
            print(f"Audit: manifest {result.audit.manifest_sha256[:12]}…, "
                  f"IG {result.audit.ig_package.id}@{result.audit.ig_package.version}, "
                  f"input sha256 {result.audit.input.sha256[:12]}…")
            print(f"\nVerdict trail: {' -> '.join(verdict_trail)}")
            print("Demo complete: the agentic loop converged.")
            return 0
        except GateFailed as failure:
            print("Gate FAILED — this feedback would go to the repair LLM:")
            print("-" * 72)
            print(failure.feedback)
            print("-" * 72)
            candidate = apply_stub_fix(candidate, failure.result, originals)

    print(f"\nVerdict trail: {' -> '.join(verdict_trail)}")
    print(f"Did not converge within {args.max_attempts} attempts", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
