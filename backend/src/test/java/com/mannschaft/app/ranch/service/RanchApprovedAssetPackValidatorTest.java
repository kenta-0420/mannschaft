package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実承認packの限定変異で、geometry・action・phase・hashの不正登録を検証する。 */
class RanchApprovedAssetPackValidatorTest {
    private static final String ROOT = "ranch/approved/asset-packs/ranch-approved-20261108-v1/";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void action欠落や余分なfieldと未承認描画profileを拒否する() throws Exception {
        var missing = manifest();
        ((ObjectNode) first(missing).path("clips")).remove("happy");
        rejected(missing);
        var extra = manifest();
        first(extra).put("unknown", true);
        rejected(extra);
        var profile = manifest();
        first(profile).put("rendererProfile", "UNKNOWN");
        rejected(profile);
    }

    @Test
    void 改変phaseとgeometryと参照hashを拒否する() throws Exception {
        var phase = manifest();
        var clip = (ObjectNode) first(phase).path("clips").path("idle");
        clip.put("durationMs", 0);
        rejected(phase);
        var geometry = manifest();
        first(geometry).put("frameWidth", 112);
        rejected(geometry);
        var hash = manifest();
        first(hash).put("sourceSha256", "0".repeat(64));
        rejected(hash);
    }

    @Test
    void 相対path越境とfallback不整合を拒否する() throws Exception {
        var path = manifest();
        first(path).put("filePath", "../foreign.png");
        rejected(path);
        var fallback = manifest();
        first(fallback).put("fallbackPath", first(fallback).path("filePath").asText());
        first(fallback).put("fallbackSha256", first(fallback).path("sourceSha256").asText());
        rejected(fallback);
    }

    private ObjectNode first(ObjectNode manifest) {
        return (ObjectNode) manifest.path("entries").get(0);
    }

    private ObjectNode manifest() throws Exception {
        try (var source = new ClassPathResource(ROOT + "manifest.json").getInputStream()) {
            return (ObjectNode) json.readTree(source);
        }
    }

    private void rejected(ObjectNode manifest) {
        assertThatThrownBy(() -> RanchApprovedAssetPackValidator.validate(manifest, ROOT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
