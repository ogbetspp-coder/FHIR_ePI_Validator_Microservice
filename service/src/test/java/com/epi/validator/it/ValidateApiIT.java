package com.epi.validator.it;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP-contract round trips: gzip, XML, trace propagation, audit fields, error statuses. */
class ValidateApiIT extends AbstractServiceIT {

    @Autowired
    private FhirContext fhirContext;

    private static String example() throws IOException {
        try (InputStream in = new ClassPathResource(
                "examples/1.0.0/Bundle-bundlepackageleaflet75type1.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void gzipBodyIsInflatedTransparently() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(example().getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("Content-Encoding", "gzip");
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate?epiType=1&includeOperationOutcome=false",
                new HttpEntity<>(compressed.toByteArray(), headers),
                JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("verdict").asText()).isNotBlank();
        // audit reflects the post-inflation bytes
        assertThat(response.getBody().path("audit").path("input").path("sizeBytes").asLong())
                .isEqualTo(example().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void xmlBodyIsAccepted() throws IOException {
        var bundle = fhirContext.newJsonParser().parseResource(example());
        String xml = fhirContext.newXmlParser().encodeResourceToString(bundle);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+xml"));
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate?epiType=1&includeOperationOutcome=false",
                new HttpEntity<>(xml, headers),
                JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("detectedEpiType").asText()).isEqualTo("1");
        // XML re-encoding loses raw JSON line positions but must not lose the verdict contract
        assertThat(response.getBody().path("verdict").asText()).isIn("PASS", "PASS_WITH_WARNINGS", "FAIL");
    }

    @Test
    void inboundTraceIdIsEchoedInEnvelopeAndHeader() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("X-Trace-Id", "pipeline-run-0042");
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate?epiType=1&includeOperationOutcome=false",
                new HttpEntity<>(example(), headers),
                JsonNode.class);
        assertThat(response.getBody().path("traceId").asText()).isEqualTo("pipeline-run-0042");
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("pipeline-run-0042");
    }

    @Test
    void operationOutcomeOmittedOnRequest() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        JsonNode with = rest.postForEntity("/api/v1/epi/validate?epiType=1",
                new HttpEntity<>(example(), headers), JsonNode.class).getBody();
        JsonNode without = rest.postForEntity("/api/v1/epi/validate?epiType=1&includeOperationOutcome=false",
                new HttpEntity<>(example(), headers), JsonNode.class).getBody();
        assertThat(with.path("operationOutcome").path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(without.has("operationOutcome")).isFalse();
        // OperationOutcome hash is part of the audit evidence whenever validation ran
        assertThat(with.path("audit").path("operationOutcomeSha256").asText()).hasSize(64);
    }

    @Test
    void unknownIgVersionIs422() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate?igVersion=9.9.9",
                new HttpEntity<>(example(), headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().path("message").asText()).contains("9.9.9");
    }

    @Test
    void nonBundleResourceIs422() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate",
                new HttpEntity<>("{\"resourceType\":\"Patient\"}", headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().path("message").asText()).contains("Patient");
    }

    @Test
    void fhirNativeValidateReturnsOperationOutcomeWith200EvenOnErrors() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/fhir/$validate",
                new HttpEntity<>(example(), headers), JsonNode.class);
        // The STU1 type1 example carries known errors, yet FHIR semantics demand 200 + OO
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(response.getBody().path("issue").size()).isGreaterThan(0);
    }
}
