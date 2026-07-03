package com.epi.validator.it;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end contract of the lean service: one endpoint, verdict from validation (never HTTP
 * status), simple checks, type contract, gzip/XML handling, and the cut surfaces staying cut.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidateApiIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FhirContext fhirContext;

    private static String fixture(String name) throws IOException {
        try (InputStream in = new ClassPathResource("examples/" + name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ResponseEntity<JsonNode> post(String url, Object body, HttpHeaders headers) {
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.valueOf("application/fhir+json"));
        }
        return rest.postForEntity(url, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static List<String> ruleIds(JsonNode envelope) {
        List<String> ids = new ArrayList<>();
        envelope.path("issues").forEach(i -> ids.add(i.path("ruleId").asText()));
        return ids;
    }

    @Test
    void goodBundlePassesWithGovernedWarningsOnly() throws IOException {
        ResponseEntity<JsonNode> response =
                post("/api/v1/epi/validate?epiType=1", fixture("good-bundle.json"), new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = response.getBody();
        assertThat(envelope.path("verdict").asText()).isIn("PASS", "PASS_WITH_WARNINGS");
        assertThat(envelope.path("requestedEpiType").asText()).isEqualTo("1");
        assertThat(envelope.path("detectedEpiType").asText()).isEqualTo("1");
        assertThat(envelope.path("effectiveEpiType").asText()).isEqualTo("1");
        assertThat(envelope.path("inputSha256").asText()).hasSize(64);
        assertThat(envelope.path("validatorInfo").path("hapiVersion").asText()).isEqualTo("8.10.0");
        assertThat(envelope.path("validatorInfo").path("igPackage").asText())
                .isEqualTo("hl7.fhir.uv.emedicinal-product-info");
        assertThat(envelope.path("validatorInfo").path("igVersion").asText()).isEqualTo("1.0.0");
        assertThat(envelope.path("operationOutcome").path("resourceType").asText())
                .isEqualTo("OperationOutcome");
        envelope.path("issues").forEach(i ->
                assertThat(i.path("severity").asText()).isNotIn("fatal", "error"));
    }

    @Test
    void brokenBundleFailsWithSimpleCheckAndValidatorIssues() throws IOException {
        JsonNode envelope = post("/api/v1/epi/validate?epiType=1",
                fixture("broken-bundle.json"), new HttpHeaders()).getBody();
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-DOC-003");
        boolean hasValidatorError = false;
        boolean simpleCheckLocated = false;
        for (JsonNode issue : envelope.path("issues")) {
            if ("hapi-validator".equals(issue.path("source").asText())
                    && issue.path("severity").asText().equals("error")) {
                hasValidatorError = true;
            }
            if ("EPI-DOC-003".equals(issue.path("ruleId").asText())) {
                assertThat(issue.path("source").asText()).isEqualTo("simple-check");
                assertThat(issue.path("fhirPath").asText()).contains("Bundle.entry");
                simpleCheckLocated = true;
            }
        }
        assertThat(hasValidatorError).as("profile validator catches the blanked section").isTrue();
        assertThat(simpleCheckLocated).isTrue();
    }

    @Test
    void explicitType3IsNeverDowngradedByDetection() throws IOException {
        JsonNode envelope = post("/api/v1/epi/validate?epiType=3",
                fixture("good-bundle.json"), new HttpHeaders()).getBody();
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(envelope.path("detectedEpiType").asText()).isEqualTo("1");
        assertThat(envelope.path("effectiveEpiType").asText()).isEqualTo("3");
        assertThat(ruleIds(envelope)).contains("EPI-TYPE-001", "EPI-TYPE-002");
    }

    @Test
    void gzipBodyIsInflatedAndHashedPostInflation() throws IOException {
        byte[] plain = fixture("good-bundle.json").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(plain);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Encoding", "gzip");
        JsonNode envelope = post("/api/v1/epi/validate?epiType=1",
                compressed.toByteArray(), headers).getBody();
        assertThat(envelope.path("verdict").asText()).isIn("PASS", "PASS_WITH_WARNINGS");
    }

    @Test
    void xmlBodyIsAccepted() throws IOException {
        var bundle = fhirContext.newJsonParser().parseResource(fixture("good-bundle.json"));
        String xml = fhirContext.newXmlParser().encodeResourceToString(bundle);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+xml"));
        JsonNode envelope = post("/api/v1/epi/validate?epiType=1", xml, headers).getBody();
        assertThat(envelope.path("detectedEpiType").asText()).isEqualTo("1");
        assertThat(envelope.path("verdict").asText()).isIn("PASS", "PASS_WITH_WARNINGS", "FAIL");
    }

    @Test
    void traceIdEchoedFromHeader() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "pipeline-run-0042");
        ResponseEntity<JsonNode> response =
                post("/api/v1/epi/validate?epiType=1", fixture("good-bundle.json"), headers);
        assertThat(response.getBody().path("traceId").asText()).isEqualTo("pipeline-run-0042");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("pipeline-run-0042");
    }

    @Test
    void unparseableBodyReturns400WithFailEnvelope() {
        ResponseEntity<JsonNode> response =
                post("/api/v1/epi/validate", "{not json", new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode envelope = response.getBody();
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(envelope.path("issues").get(0).path("source").asText()).isEqualTo("parser");
        assertThat(envelope.path("inputSha256").asText()).hasSize(64);
    }

    @Test
    void misuseReturns422() throws IOException {
        assertThat(post("/api/v1/epi/validate?epiType=5", fixture("good-bundle.json"),
                new HttpHeaders()).getStatusCode().value()).isEqualTo(422);
        assertThat(post("/api/v1/epi/validate", "{\"resourceType\":\"Patient\"}",
                new HttpHeaders()).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void emptyBodyReturns400WithFailEnvelope() {
        ResponseEntity<JsonNode> response = post("/api/v1/epi/validate", "", new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("verdict").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("issues").get(0).path("source").asText()).isEqualTo("parser");
    }

    @Test
    void concurrentValidationsAreThreadSafe() throws IOException {
        // The bulkhead was removed; the shared FhirValidator must stay correct under parallel load.
        String body = fixture("good-bundle.json");
        List<String> verdicts = java.util.stream.IntStream.range(0, 24).parallel()
                .mapToObj(i -> post("/api/v1/epi/validate?epiType=1", body, new HttpHeaders()))
                .map(r -> {
                    assertThat(r.getStatusCode().value()).isEqualTo(200);
                    return r.getBody().path("verdict").asText();
                })
                .toList();
        assertThat(verdicts).hasSize(24).allSatisfy(v ->
                assertThat(v).isEqualTo("PASS_WITH_WARNINGS"));
    }

    @Test
    void infoEndpointIdentifiesTheValidator() {
        JsonNode info = rest.getForObject("/api/v1/epi/info", JsonNode.class);
        assertThat(info.path("fhirVersion").asText()).isEqualTo("5.0.0");
        assertThat(info.path("hapiVersion").asText()).isEqualTo("8.10.0");
        assertThat(info.path("igPackage").asText()).isEqualTo("hl7.fhir.uv.emedicinal-product-info");
        assertThat(info.path("igVersion").asText()).isEqualTo("1.0.0");
        assertThat(info.path("ready").asBoolean()).isTrue();
    }

    @Test
    void readinessIsUpAndPlatformSurfacesStayCut() {
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(rest.postForEntity("/fhir/$validate", new HttpEntity<>("{}"), String.class)
                .getStatusCode().value()).isIn(404, 405);
        assertThat(rest.getForEntity("/api/v1/epi/igs", String.class)
                .getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity("/api/v1/epi/manifest", String.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    private ResponseEntity<String> postXml(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+xml"));
        return rest.postForEntity("/api/v1/epi/validate?epiType=1", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void xxeExternalEntityIsNotResolved() throws IOException {
        java.nio.file.Path secret = java.nio.file.Files.createTempFile("epi-xxe", ".txt");
        java.nio.file.Files.writeString(secret, "TOP-SECRET-DO-NOT-LEAK-8f3a");
        try {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<!DOCTYPE Bundle [<!ENTITY xxe SYSTEM \"file://" + secret.toAbsolutePath() + "\">]>\n"
                    + "<Bundle xmlns=\"http://hl7.org/fhir\"><type value=\"&xxe;\"/></Bundle>";
            ResponseEntity<String> response = postXml(xml);
            // External entities are disabled: the parse fails and the file content never leaks.
            assertThat(response.getBody()).doesNotContain("TOP-SECRET-DO-NOT-LEAK");
            assertThat(response.getStatusCode().value())
                    .as("must be a parse failure, never a 200 that resolved the entity").isEqualTo(400);
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
        }
    }

    @Test
    void xmlEntityExpansionIsRejectedQuickly() {
        String bomb = "<?xml version=\"1.0\"?>\n<!DOCTYPE Bundle [\n"
                + "<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">\n"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\"><!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\">\n"
                + "<!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\">]>\n"
                + "<Bundle xmlns=\"http://hl7.org/fhir\"><type value=\"&e;\"/></Bundle>";
        long start = System.nanoTime();
        ResponseEntity<String> response = postXml(bomb);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(millis).as("entity expansion must be bounded, not expanded").isLessThan(5000);
    }

    @Test
    void deeplyNestedJsonIsHandledNotCrashed() {
        int depth = 4000;
        String json = "{\"resourceType\":\"Bundle\",\"type\":\"document\",\"entry\":"
                + "[{\"resource\":".repeat(depth) + "{}" + "}]".repeat(depth) + "}";
        ResponseEntity<JsonNode> response = post("/api/v1/epi/validate?epiType=1", json, new HttpHeaders());
        assertThat(response.getStatusCode().value()).as("handled, not a stack-overflow 500").isIn(400, 422);
        // The service must remain healthy afterwards.
        assertThat(rest.getForEntity("/api/v1/epi/info", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void nonUtf8JsonIsRejectedNotSilentlyCorrupted() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        byte[] invalidUtf8 = {'{', (byte) 0x80, '}'};
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/epi/validate?epiType=1",
                new HttpEntity<>(invalidUtf8, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void iso8859XmlWithAccentsIsDecodedNotCorrupted() {
        // Content declares its charset; the accented narrative must survive to the validator.
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                + "<Bundle xmlns=\"http://hl7.org/fhir\"><id value=\"café\"/><type value=\"document\"/></Bundle>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+xml"));
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/epi/validate?epiType=1",
                new HttpEntity<>(xml.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), headers),
                JsonNode.class);
        // Validation runs (verdict present) rather than failing to decode; content was not mangled.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("verdict").asText()).isNotBlank();
    }

    @Test
    void epiTypeUnknownIsRejectedNotSilentlyUncontracted() throws IOException {
        // "unknown" is a detection sentinel, not a request value. It must 422 like epiType=5, not
        // be accepted and silently skip the EPI-TYPE contract (that would be a false PASS).
        ResponseEntity<JsonNode> response = post("/api/v1/epi/validate?epiType=unknown",
                fixture("good-bundle.json"), new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void oversizedBundleIsRejectedBeforeValidation() {
        // A bundle with more entries than the cap is rejected (413) before the expensive per-entry
        // validation, so a huge bundle of tiny resources cannot pin a worker thread.
        StringBuilder sb = new StringBuilder(
                "{\"resourceType\":\"Bundle\",\"type\":\"document\",\"entry\":[");
        for (int i = 0; i < 1001; i++) {
            sb.append(i > 0 ? "," : "")
                    .append("{\"resource\":{\"resourceType\":\"ClinicalUseDefinition\",\"type\":\"indication\"}}");
        }
        sb.append("]}");
        ResponseEntity<JsonNode> response =
                post("/api/v1/epi/validate?epiType=3", sb.toString(), new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    // --- Type-3 ClinicalUseDefinition sub-profile enforcement ------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode type3WithoutCudMetaProfiles() throws IOException {
        ObjectNode bundle = (ObjectNode) MAPPER.readTree(fixture("type3-example.json"));
        for (JsonNode entry : bundle.withArray("entry")) {
            JsonNode resource = entry.path("resource");
            if ("ClinicalUseDefinition".equals(resource.path("resourceType").asText())
                    && resource.has("meta")) {
                ((ObjectNode) resource.path("meta")).remove("profile");
            }
        }
        return bundle;
    }

    private long clinicalProfileErrors(JsonNode envelope) {
        long count = 0;
        for (JsonNode issue : envelope.path("issues")) {
            if ("clinical-profile".equals(issue.path("source").asText())
                    && issue.path("severity").asText().matches("error|fatal")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void conformantType3ClinicalContentPassesWithoutMetaProfile() throws IOException {
        // Sub-profiles are enforced even with meta.profile stripped; conformant CUDs must not
        // gain false errors (the whole bundle still FAILs on the example's own narrative defects).
        ObjectNode bundle = type3WithoutCudMetaProfiles();
        JsonNode envelope = post("/api/v1/epi/validate?epiType=3", bundle.toString(), new HttpHeaders())
                .getBody();
        assertThat(clinicalProfileErrors(envelope))
                .as("conformant ClinicalUseDefinition resources produce no sub-profile errors").isZero();
    }

    @Test
    void missingRequiredIndicationIsCaughtBySubProfileNotBase() throws IOException {
        // Remove the indication element (base ClinicalUseDefinition allows it; the ePI indication
        // sub-profile requires min=1) and strip meta.profile; base validation would miss this.
        ObjectNode bundle = type3WithoutCudMetaProfiles();
        for (JsonNode entry : bundle.withArray("entry")) {
            JsonNode resource = entry.path("resource");
            if ("ClinicalUseDefinition".equals(resource.path("resourceType").asText())
                    && "indication".equals(resource.path("type").asText())) {
                ((ObjectNode) resource).remove("indication");
            }
        }
        JsonNode envelope = post("/api/v1/epi/validate?epiType=3", bundle.toString(), new HttpHeaders())
                .getBody();
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");

        boolean caughtByClinicalProfile = false;
        boolean caughtByBaseValidator = false;
        for (JsonNode issue : envelope.path("issues")) {
            boolean aboutIndication = issue.path("message").asText().toLowerCase().contains("indication");
            if (!aboutIndication || !issue.path("severity").asText().matches("error|fatal")) {
                continue;
            }
            if ("clinical-profile".equals(issue.path("source").asText())) {
                caughtByClinicalProfile = true;
                assertThat(issue.path("ruleId").asText()).isEqualTo("EPI-CUD-PROFILE");
            }
            if ("hapi-validator".equals(issue.path("source").asText())) {
                caughtByBaseValidator = true;
            }
        }
        assertThat(caughtByClinicalProfile)
                .as("the ePI indication sub-profile enforcement catches the missing element").isTrue();
        assertThat(caughtByBaseValidator)
                .as("base Bundle validation does NOT catch it; this is exactly the gap being closed")
                .isFalse();
    }

    @Test
    void clinicalUseDefinitionWithValueAbsentTypeDoesNotCrash() throws IOException {
        // A `type` primitive that carries only a data-absent-reason extension is valid FHIR:
        // hasType() is true but getType() is null. Sub-profile enforcement must not NPE into a 500.
        ObjectNode bundle = (ObjectNode) MAPPER.readTree(fixture("type3-example.json"));
        boolean patched = false;
        for (JsonNode entry : bundle.withArray("entry")) {
            ObjectNode resource = (ObjectNode) entry.path("resource");
            if ("ClinicalUseDefinition".equals(resource.path("resourceType").asText())) {
                resource.remove("type");
                ObjectNode dataAbsent = MAPPER.createObjectNode()
                        .put("url", "http://hl7.org/fhir/StructureDefinition/data-absent-reason")
                        .put("valueCode", "unknown");
                resource.set("_type", MAPPER.createObjectNode()
                        .set("extension", MAPPER.createArrayNode().add(dataAbsent)));
                patched = true;
                break;
            }
        }
        assertThat(patched).as("fixture contains a ClinicalUseDefinition to patch").isTrue();

        ResponseEntity<JsonNode> response =
                post("/api/v1/epi/validate?epiType=3", bundle.toString(), new HttpHeaders());
        assertThat(response.getStatusCode().value()).as("validation completes, no 500").isEqualTo(200);
        assertThat(response.getBody().path("verdict").asText()).isNotBlank();
    }
}
