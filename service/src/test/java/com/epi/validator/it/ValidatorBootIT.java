package com.epi.validator.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorBootIT extends AbstractServiceIT {

    @Test
    void readinessIsUpAfterWarmup() {
        JsonNode health = rest.getForObject("/actuator/health/readiness", JsonNode.class);
        assertThat(health.path("status").asText()).isEqualTo("UP");
    }

    @Test
    void igsEndpointListsBothConfiguredIgs() {
        JsonNode igs = rest.getForObject("/api/v1/epi/igs", JsonNode.class);
        assertThat(igs).hasSize(2);
        assertThat(igs.get(0).path("igId").asText()).isEqualTo("1.0.0");
        assertThat(igs.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(igs.get(0).path("ready").asBoolean()).isTrue();
        assertThat(igs.get(1).path("igId").asText()).isEqualTo("1.1.0");
        assertThat(igs.get(1).path("bundleProfiles").path("2").asText())
                .isEqualTo("http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/bundle-epi-type2");
        assertThat(igs.get(1).path("dependencies")).hasSize(2);
    }

    @Test
    void manifestExposesPinnedPackagesAndPolicyPacks() {
        JsonNode manifest = rest.getForObject("/api/v1/epi/manifest", JsonNode.class);
        assertThat(manifest.path("manifestSha256").asText()).hasSize(64);
        JsonNode m = manifest.path("manifest");
        assertThat(m.path("hapiVersion").asText()).isEqualTo("8.10.0");
        assertThat(m.path("igPackages")).hasSize(6);
        m.path("igPackages").forEach(p -> assertThat(p.path("sha256").asText()).hasSize(64));
        assertThat(m.path("policyPacks").get(0).path("id").asText()).isEqualTo("epi-gate-core");
    }

    @Test
    void swaggerAndOpenApiAreServed() {
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }
}
