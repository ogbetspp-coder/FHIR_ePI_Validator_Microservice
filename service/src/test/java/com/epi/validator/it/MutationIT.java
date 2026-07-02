package com.epi.validator.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds specific defects into the cleanest IG example (1.1.0 blister-carton, zero profile
 * errors) and asserts the gate fails with the exact expected rule — proving each detection
 * path end to end, the same defects the extraction agent will produce.
 */
class MutationIT extends AbstractServiceIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode pristine;

    @BeforeAll
    static void loadExample() throws IOException {
        try (InputStream in = new ClassPathResource(
                "examples/1.1.0/Bundle-bundle-epi-type2-example-blister-carton.json").getInputStream()) {
            pristine = MAPPER.readTree(in);
        }
    }

    private JsonNode validate(JsonNode bundle, String epiType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        return rest.postForEntity(
                "/api/v1/epi/validate?validationMode=gate&epiType=" + epiType
                        + "&igVersion=1.1.0&includeOperationOutcome=false",
                new HttpEntity<>(bundle.toString(), headers),
                JsonNode.class).getBody();
    }

    private static List<String> ruleIds(JsonNode envelope) {
        List<String> ids = new ArrayList<>();
        envelope.path("issues").forEach(i -> ids.add(i.path("ruleId").asText()));
        return ids;
    }

    private static ObjectNode copy() {
        return pristine.deepCopy();
    }

    @Test
    void pristineExamplePassesWithWarnings() {
        JsonNode envelope = validate(pristine, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("PASS_WITH_WARNINGS");
        assertThat(envelope.path("stats").path("total").path("errors").asInt()).isZero();
    }

    @Test
    void removedCompositionFailsFirstEntryRule() {
        ObjectNode mutated = copy();
        ((ArrayNode) mutated.get("entry")).remove(0);
        JsonNode envelope = validate(mutated, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-DOC-001");
    }

    @Test
    void blankedSectionFailsSectionContentRule() {
        ObjectNode mutated = copy();
        ObjectNode section = (ObjectNode) mutated.at("/entry/0/resource/section/0");
        section.remove("text");
        section.remove("entry");
        section.remove("section");
        JsonNode envelope = validate(mutated, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-DOC-003");
        // The policy issue carries an actionable location for the repair agent
        boolean located = false;
        for (JsonNode i : envelope.path("issues")) {
            if ("EPI-DOC-003".equals(i.path("ruleId").asText())) {
                assertThat(i.path("location").path("fhirPath").asText()).contains("section[0]");
                assertThat(i.path("source").path("id").asText()).isEqualTo("epi-gate-core");
                assertThat(i.path("rationale").asText()).isNotBlank();
                located = true;
            }
        }
        assertThat(located).isTrue();
    }

    @Test
    void nonDocumentBundleTypeFails() {
        ObjectNode mutated = copy();
        mutated.put("type", "collection");
        JsonNode envelope = validate(mutated, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-DOC-002");
    }

    @Test
    void danglingReferenceFailsReferenceRule() {
        ObjectNode mutated = copy();
        ObjectNode author = (ObjectNode) mutated.at("/entry/0/resource/author/0");
        author.put("reference", "Organization/does-not-exist");
        JsonNode envelope = validate(mutated, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-DOC-005");
    }

    @Test
    void invalidLanguageCodeFails() {
        ObjectNode mutated = copy();
        ((ObjectNode) mutated.at("/entry/0/resource")).put("language", "not a language!");
        JsonNode envelope = validate(mutated, "2");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        boolean languageIssue = false;
        for (JsonNode i : envelope.path("issues")) {
            if (i.path("severity").asText().equals("error")
                    && i.path("message").asText().toLowerCase().contains("language")) {
                languageIssue = true;
            }
        }
        assertThat(languageIssue).isTrue();
    }

    @Test
    void requestedType3WithoutClinicalContentFailsTypeGate() {
        JsonNode envelope = validate(pristine, "3");
        assertThat(envelope.path("verdict").asText()).isEqualTo("FAIL");
        assertThat(ruleIds(envelope)).contains("EPI-TYPE-001");
        assertThat(envelope.path("detectedEpiType").asText()).isEqualTo("2");
        assertThat(envelope.path("effectiveEpiType").asText()).isEqualTo("3");
    }
}
