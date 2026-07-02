import pytest


@pytest.fixture
def envelope() -> dict:
    """A representative validation envelope as returned by POST /api/v1/epi/validate."""
    return {
        "verdict": "FAIL",
        "validationMode": "gate",
        "igVersion": "1.1.0",
        "requestedEpiType": "3",
        "detectedEpiType": "2",
        "effectiveEpiType": "3",
        "profilesValidatedAgainst": [
            "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/bundle-epi-type3"
        ],
        "stats": {
            "parser": {"errors": 0, "warnings": 0},
            "fhir": {"errors": 0, "warnings": 2},
            "ig": {"errors": 1, "warnings": 0},
            "policy": {"errors": 1, "warnings": 0},
            "total": {"fatal": 0, "errors": 2, "warnings": 2, "information": 1},
            "durationMillis": 431,
        },
        "issues": [
            {
                "severity": "error",
                "code": "structure",
                "ruleId": "Bundle_BUNDLE_Entry_NotFound",
                "layer": "ig",
                "location": {
                    "fhirPath": "Bundle.entry[0].resource.section[3].entry[0]",
                    "jsonPointer": "/entry/0/resource/section/3/entry/0",
                    "line": 812,
                    "column": 13,
                },
                "message": "Cannot resolve reference urn:uuid:x in the document bundle",
                "profileUrl": "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Composition-uv-epi",
                "source": {"type": "profile-validator", "id": "hapi", "version": "8.10.0"},
                "allowlisted": False,
                "rationale": None,
                "suggestion": None,
            },
            {
                "severity": "error",
                "code": "business-rule",
                "ruleId": "EPI-TYPE-001",
                "layer": "policy",
                "location": {
                    "fhirPath": "Bundle", "jsonPointer": "/", "line": None, "column": None,
                },
                "message": "Requested ePI Type 3 but no clinical content found",
                "profileUrl": None,
                "source": {"type": "policy-pack", "id": "epi-gate-core", "version": "0.1.0"},
                "allowlisted": False,
                "rationale": "Type 3 contract requires machine-readable clinical content",
                "suggestion": None,
            },
            {
                "severity": "warning",
                "code": "structure",
                "ruleId": "Terminology_TX_System_NotKnown",
                "layer": "fhir",
                "location": {
                    "fhirPath": "Bundle.entry[1]", "jsonPointer": "/entry/1",
                    "line": 3, "column": 1,
                },
                "message": "CodeSystem 'https://spor.ema.europa.eu/v1/lists/1' is unknown",
                "profileUrl": None,
                "source": {"type": "profile-validator", "id": "hapi", "version": "8.10.0"},
                "allowlisted": True,
                "rationale": "Checked upstream by SPOR adapter",
                "suggestion": None,
            },
        ],
        "traceId": "pipeline-run-0042",
        "audit": {
            "manifestSha256": "a" * 64,
            "hapiVersion": "8.10.0",
            "igPackage": {
                "id": "hl7.fhir.uv.emedicinal-product-info",
                "version": "1.1.0-cibuild-20260701",
                "sha256": "b" * 64,
            },
            "policyPacks": [{"id": "epi-gate-core", "version": "0.1.0"}],
            "warningPolicyMode": "pass-with-warnings",
            "validationMode": "gate",
            "input": {
                "sha256": "c" * 64,
                "contentType": "application/fhir+json",
                "sizeBytes": 27958,
            },
            "operationOutcomeSha256": "d" * 64,
        },
        "operationOutcome": {"resourceType": "OperationOutcome", "issue": []},
    }


@pytest.fixture
def passing_envelope(envelope: dict) -> dict:
    passing = dict(envelope)
    passing["verdict"] = "PASS_WITH_WARNINGS"
    passing["issues"] = [i for i in envelope["issues"] if i["severity"] == "warning"]
    passing["stats"] = {
        **envelope["stats"],
        "ig": {"errors": 0, "warnings": 0},
        "policy": {"errors": 0, "warnings": 0},
        "total": {"fatal": 0, "errors": 0, "warnings": 2, "information": 1},
    }
    return passing
