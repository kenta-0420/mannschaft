package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.RenderStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 16基礎種の開発用ID境界。正式診断・出生対応や公開素材の承認を表さない。 */
@Component
public final class RanchDevelopmentClipCatalog {
    public static final long CATALOG_VERSION = 3L;
    public static final String VARIANT_KEY = "DEV_BASE_16_CLIPS_V1";

    public RanchDevelopmentClipCatalog(
            @Value("${mannschaft.ranch.development-clips:false}") boolean requested,
            Environment environment, ObjectMapper json) { }

    public List<Candidate> candidates(Habitat habitat) { return List.of(); }

    public Optional<Candidate> find(Long catalogVersion, String speciesKey, String variantKey,
                                     DinosaurStage stage, RenderStyle style) {
        return Optional.empty();
    }

    public record Candidate(String sourceSpeciesKey, String speciesKey, Habitat habitat) { }
}
