package com.epi.validator.audit;

import ca.uhn.fhir.util.VersionUtil;
import com.epi.validator.policy.PolicyEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Builds the {@link BuildManifest} once at startup from the vendored package lockfile
 * (sealed into the jar at build time), git build info, and the running engine versions,
 * and computes its canonical SHA-256.
 */
@Component
public class ManifestService {

    private static final String FHIR_VERSION = "5.0.0";

    private final BuildManifest manifest;
    private final String manifestSha256;

    public ManifestService(PolicyEngine policyEngine) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        try {
            JsonNode lock;
            try (InputStream in = new ClassPathResource("manifest/packages.lock.json").getInputStream()) {
                lock = mapper.readTree(in);
            }
            List<BuildManifest.IgPackageEntry> packages = new ArrayList<>();
            for (JsonNode pkg : lock.get("packages")) {
                packages.add(new BuildManifest.IgPackageEntry(
                        pkg.path("name").asText(),
                        pkg.path("version").asText(),
                        pkg.path("sha256").asText(),
                        pkg.path("url").asText(),
                        pkg.path("vendoredAt").asText(),
                        pkg.path("targetFile").asText()));
            }
            List<BuildManifest.PolicyPackEntry> packs = policyEngine.activePacks().stream()
                    .map(p -> new BuildManifest.PolicyPackEntry(p.id(), p.version()))
                    .toList();
            this.manifest = new BuildManifest(
                    buildId(),
                    VersionUtil.getVersion(),
                    FHIR_VERSION,
                    packages,
                    packs);
            byte[] canonical = mapper.writer().writeValueAsBytes(manifest);
            this.manifestSha256 = sha256Hex(canonical);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot build validation manifest from manifest/packages.lock.json", e);
        }
    }

    private static String buildId() {
        try (InputStream in = new ClassPathResource("git.properties").getInputStream()) {
            Properties props = new Properties();
            props.load(in);
            String commit = props.getProperty("git.commit.id.abbrev", "unknown");
            String time = props.getProperty("git.commit.time", "");
            return commit + (time.isBlank() ? "" : "@" + time);
        } catch (IOException e) {
            return "unknown";
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public BuildManifest manifest() {
        return manifest;
    }

    public String manifestSha256() {
        return manifestSha256;
    }

    /** The manifest entry for a vendored package file (matched by target file name). */
    public BuildManifest.IgPackageEntry entryForTargetFile(String classpathLocation) {
        String fileName = classpathLocation.substring(classpathLocation.lastIndexOf('/') + 1);
        return manifest.igPackages().stream()
                .filter(p -> p.targetFile().equals(fileName))
                .findFirst()
                .orElse(null);
    }
}
