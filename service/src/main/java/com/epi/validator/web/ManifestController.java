package com.epi.validator.web;

import com.epi.validator.audit.BuildManifest;
import com.epi.validator.audit.ManifestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The immutable build manifest and its hash, as echoed in every validation audit block. */
@RestController
@Tag(name = "Introspection")
public class ManifestController {

    private final ManifestService manifestService;

    public ManifestController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    @Operation(summary = "Immutable build manifest: validator build, pinned IG packages, policy packs")
    @GetMapping(value = "/api/v1/epi/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> manifest() {
        BuildManifest manifest = manifestService.manifest();
        return Map.of(
                "manifestSha256", manifestService.manifestSha256(),
                "manifest", manifest);
    }
}
