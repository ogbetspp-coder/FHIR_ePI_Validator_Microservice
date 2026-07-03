package com.epi.validator.web;

import com.epi.validator.model.ValidatorInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** "What validator am I talking to?" Package pins and readiness, nothing more. */
@RestController
@Tag(name = "ePI validation")
public class InfoController {

    private final ValidatorInfo validatorInfo;
    private final ApplicationAvailability availability;

    public InfoController(ValidatorInfo validatorInfo, ApplicationAvailability availability) {
        this.validatorInfo = validatorInfo;
        this.availability = availability;
    }

    @Operation(summary = "Validator identity: FHIR version, engine version, pinned IG package, readiness")
    @GetMapping(value = "/api/v1/epi/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fhirVersion", validatorInfo.fhirVersion());
        info.put("hapiVersion", validatorInfo.hapiVersion());
        info.put("igPackage", validatorInfo.igPackage());
        info.put("igVersion", validatorInfo.igVersion());
        info.put("ready", availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC);
        return info;
    }
}
