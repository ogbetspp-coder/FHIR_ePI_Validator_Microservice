package com.epi.validator.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the IG's own example bundles against a committed expectations baseline.
 *
 * <p>The IG examples are NOT clean against their own profiles (verified empirically — the
 * STU1 type2 example alone carries 76 profile errors from unresolvable narrative links and
 * unreachable entries). The baseline records the known counts; a change means either a
 * package refresh or an engine behavior change, and must be reviewed, not absorbed.
 */
class ValidateIgExamplesIT extends AbstractServiceIT {

    record ExampleCase(String igVersion, String file, String epiType,
                       int expectedErrors, int expectedWarnings, String expectedVerdict) {
        @Override
        public String toString() {
            return igVersion + "/" + file;
        }
    }

    @SuppressWarnings("unchecked")
    static List<ExampleCase> baselineCases() throws IOException {
        try (InputStream in = new ClassPathResource("expected-baselines.yaml").getInputStream()) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            List<ExampleCase> cases = new ArrayList<>();
            for (Map.Entry<String, Object> igEntry : root.entrySet()) {
                Map<String, Object> files = (Map<String, Object>) igEntry.getValue();
                for (Map.Entry<String, Object> fileEntry : files.entrySet()) {
                    Map<String, Object> expectation = (Map<String, Object>) fileEntry.getValue();
                    cases.add(new ExampleCase(
                            igEntry.getKey(),
                            fileEntry.getKey(),
                            String.valueOf(expectation.get("epiType")),
                            (int) expectation.get("errors"),
                            (int) expectation.get("warnings"),
                            String.valueOf(expectation.get("verdict"))));
                }
            }
            return cases;
        }
    }

    @ParameterizedTest
    @MethodSource("baselineCases")
    void exampleMatchesCommittedBaseline(ExampleCase example) throws IOException {
        String body;
        try (InputStream in = new ClassPathResource(
                "examples/" + example.igVersion() + "/" + example.file()).getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/v1/epi/validate?validationMode=gate&epiType=" + example.epiType()
                        + "&igVersion=" + example.igVersion() + "&includeOperationOutcome=false",
                new HttpEntity<>(body, headers),
                JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode envelope = response.getBody();
        JsonNode total = envelope.path("stats").path("total");
        assertThat(total.path("fatal").asInt() + total.path("errors").asInt())
                .as("error count drift for %s — review package/engine change before updating the baseline", example)
                .isEqualTo(example.expectedErrors());
        assertThat(total.path("warnings").asInt())
                .as("warning count drift for %s", example)
                .isEqualTo(example.expectedWarnings());
        assertThat(envelope.path("verdict").asText()).isEqualTo(example.expectedVerdict());
        assertThat(envelope.path("effectiveEpiType").asText()).isEqualTo(example.epiType());
        assertThat(envelope.path("audit").path("manifestSha256").asText()).hasSize(64);
    }
}
