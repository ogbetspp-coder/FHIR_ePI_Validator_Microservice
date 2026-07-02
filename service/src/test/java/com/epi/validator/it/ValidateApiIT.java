package com.epi.validator.it;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
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
}
