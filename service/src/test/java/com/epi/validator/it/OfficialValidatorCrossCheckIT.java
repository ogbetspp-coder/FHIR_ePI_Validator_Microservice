package com.epi.validator.it;

import com.epi.validator.model.Issue;
import com.epi.validator.model.ValidationResponse;
import com.epi.validator.service.ValidationService;
import org.hl7.fhir.r5.elementmodel.Manager.FhirFormat;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.utilities.validation.ValidationMessage.IssueSeverity;
import org.hl7.fhir.validation.ValidationEngine;
import org.hl7.fhir.validation.instance.InstanceValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks this service's structural verdict against the <b>official HL7 FHIR validator</b>,
 * {@code org.hl7.fhir.validation.ValidationEngine}, the exact reference engine behind
 * validator.fhir.org and validator_cli, pinned to the same core version this service embeds
 * (6.9.4). Both are configured with the same offline policy the gate uses (no external
 * terminology server, example URLs permitted) so the comparison isolates profile conformance
 * from the deliberately-lenient offline-terminology behaviour (see README known limitations).
 *
 * <p>The assertion: for each example bundle, the official validator's error count equals the
 * error count from this service's {@code hapi-validator} layer. It proves the gate reaches the
 * same accept/reject decision as the reference validator.
 *
 * <p>Gated behind {@code -Dcrosscheck=true} (a dedicated CI job) because it downloads the FHIR
 * R5 core package and loads a second validator context; it is not part of the hermetic build.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "crosscheck", matches = "true")
class OfficialValidatorCrossCheckIT {

    private static final String PROFILE =
            "http://hl7.org/fhir/uv/emedicinal-product-info/StructureDefinition/Bundle-uv-epi";
    private static final String[] PACKAGES = {
            "hl7.terminology.r5-5.0.0.tgz",
            "hl7.fhir.uv.extensions.r5-1.0.0.tgz",
            "hl7.fhir.uv.emedicinal-product-info-1.0.0.tgz"};

    private static ValidationEngine officialEngine;

    @Autowired
    private ValidationService validationService;

    @BeforeAll
    static void buildOfficialEngine() throws Exception {
        ValidationEngine engine = new ValidationEngine.ValidationEngineBuilder()
                .withVersion("5.0.0")
                .withNoTerminologyServer()
                .withCanRunWithoutTerminologyServer(true)
                .fromSource("hl7.fhir.r5.core#5.0.0");
        for (String pkg : PACKAGES) {
            String path = new ClassPathResource("packages/" + pkg).getFile().getAbsolutePath();
            engine.getIgLoader().loadIg(new ArrayList<>(), new java.util.HashMap<>(), path, false);
        }
        officialEngine = engine;
    }

    private int officialErrorCount(byte[] body) throws Exception {
        InstanceValidator validator = officialEngine.getValidator(FhirFormat.JSON);
        validator.setNoTerminologyChecks(true); // offline terminology is validated separately by design
        validator.setAllowExamples(true);        // the IG examples use example.org placeholder URLs
        List<ValidationMessage> messages = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(body)) {
            validator.validate(null, messages, in, FhirFormat.JSON, PROFILE);
        }
        return (int) messages.stream()
                .filter(m -> m.getLevel() == IssueSeverity.ERROR || m.getLevel() == IssueSeverity.FATAL)
                .count();
    }

    private int serviceValidatorErrorCount(byte[] body) {
        ValidationResponse response = validationService.validate(
                body, "application/fhir+json", "auto", "crosscheck");
        return (int) response.issues().stream()
                .filter(i -> Issue.SOURCE_VALIDATOR.equals(i.source()) && i.isError())
                .count();
    }

    private byte[] example(String name) throws Exception {
        try (InputStream in = new ClassPathResource("examples/" + name).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void goodBundleAgreesWithOfficialValidator() throws Exception {
        byte[] body = example("good-bundle.json");
        int official = officialErrorCount(body);
        int service = serviceValidatorErrorCount(body);
        assertThat(official).as("conformant bundle has no structural errors in the official validator").isZero();
        assertThat(service)
                .as("this service agrees with the official validator on the good bundle (both %d)", official)
                .isEqualTo(official);
    }

    @Test
    void brokenBundleAgreesWithOfficialValidator() throws Exception {
        byte[] body = example("broken-bundle.json");
        int official = officialErrorCount(body);
        int service = serviceValidatorErrorCount(body);
        assertThat(official).as("defective bundle is rejected by the official validator").isPositive();
        assertThat(service)
                .as("this service agrees with the official validator on the broken bundle (both %d)", official)
                .isEqualTo(official);
    }
}
