package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.RenderStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** 開発用IDの選定と保存済み旧版の厳密照合を行う。正式対応や公開承認は表さない。 */
@Component
public final class RanchDevelopmentClipCatalog {
    public static final long CATALOG_VERSION = 4L;
    private static final long LEGACY_VERSION = 3L;
    private static final String LEGACY_VARIANT = "DEV_BASE_16_CLIPS_V1";
    private static final List<String> VARIANTS = List.of("DEV_APPEARANCE_1", "DEV_APPEARANCE_2",
            "DEV_APPEARANCE_3", "DEV_APPEARANCE_4");
    private static final String CARE_SHA = "109c6116c99cc73737f1f7fbd23fb166a2b17cac74e11b16f5b95be717a7a63d";
    private static final String WALK_SHA = "de0783e63ab6b759df313be2e46d00045257365c27fa80ec8e96b953c9aaad23";
    private static final String LEGACY_SHA = "bcc001841b2efaf75678376112303af216e0ae805781619bafc522a145efa28c";
    private final List<Candidate> candidates;
    private final List<Candidate> legacy;

    public RanchDevelopmentClipCatalog(
            @Value("${mannschaft.ranch.development-clips:false}") boolean requested,
            Environment environment, ObjectMapper json) {
        boolean enabled = requested && !environment.acceptsProfiles(Profiles.of("prod", "production"))
                && environment.acceptsProfiles(Profiles.of("dev | test | ranch-isolated"));
        if (enabled) {
            var oldRoot = read(json, "ranch/development/base16-v3.json");
            var newRoot = read(json, "ranch/development/base16-v4.json");
            if (!oldRoot.path("catalogVersion").isIntegralNumber()
                    || oldRoot.path("catalogVersion").longValue() != LEGACY_VERSION
                    || !LEGACY_VARIANT.equals(oldRoot.path("variantKey").asText())
                    || !LEGACY_SHA.equals(oldRoot.path("manifestSha256").asText())
                    || !newRoot.path("catalogVersion").isIntegralNumber()
                    || newRoot.path("catalogVersion").longValue() != CATALOG_VERSION
                    || !CARE_SHA.equals(newRoot.path("sourceCareSha256").asText())
                    || !WALK_SHA.equals(newRoot.path("walkHubSha256").asText())
                    || !newRoot.path("variantKeys").isArray()
                    || !json.valueToTree(VARIANTS).equals(newRoot.path("variantKeys"))) {
                throw new IllegalStateException("開発クリップカタログの固定契約が不正です");
            }
            var oldSpecies = species(json, oldRoot);
            var newSpecies = species(json, newRoot);
            if (!new HashSet<>(oldSpecies).equals(new HashSet<>(newSpecies))) {
                throw new IllegalStateException("開発クリップの種が旧版と一致しません");
            }
            legacy = expand(oldSpecies, List.of(LEGACY_VARIANT), LEGACY_VERSION);
            candidates = expand(newSpecies, VARIANTS, CATALOG_VERSION);
        } else {
            legacy = List.of();
            candidates = List.of();
        }
    }

    public List<Candidate> candidates(Habitat habitat) {
        return habitat == null ? List.of()
                : candidates.stream().filter(candidate -> candidate.habitat() == habitat).toList();
    }

    /** 卵を含まず、版・種・外見・孵化後段階・画風の完全一致だけを許す。 */
    public Optional<Candidate> find(Long version, String speciesKey, String variantKey,
                                    DinosaurStage stage, RenderStyle style) {
        if (version == null || (stage != DinosaurStage.BABY && stage != DinosaurStage.JUVENILE
                && stage != DinosaurStage.ADULT) || (style != RenderStyle.PIXEL && style != RenderStyle.PAINT_2D)) {
            return Optional.empty();
        }
        List<Candidate> rows = version == LEGACY_VERSION ? legacy : version == CATALOG_VERSION ? candidates : List.of();
        return rows.stream().filter(row -> row.speciesKey().equals(speciesKey)
                && row.variantKey().equals(variantKey)).findFirst();
    }

    private static JsonNode read(ObjectMapper json, String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return json.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("開発クリップカタログを読み込めません", exception);
        }
    }

    private static List<Species> species(ObjectMapper json, JsonNode root) {
        var node = root.path("species");
        if (!node.isArray() || node.size() != 16) throw new IllegalStateException("開発クリップの種数が不正です");
        try {
            var rows = json.readerForListOf(Species.class).<List<Species>>readValue(node);
            var sourceKeys = new HashSet<String>();
            var speciesKeys = new HashSet<String>();
            for (Species row : rows) {
                if (row.sourceSpeciesKey() == null || row.sourceSpeciesKey().isBlank()
                        || row.speciesKey() == null || !row.speciesKey().startsWith("DEV_CLIP_")
                        || row.speciesKey().length() > 60 || row.habitat() == null
                        || !sourceKeys.add(row.sourceSpeciesKey()) || !speciesKeys.add(row.speciesKey())) {
                    throw new IllegalStateException("開発クリップのIDまたは生息地が不正です");
                }
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw new IllegalStateException("開発クリップの種を読み込めません", exception);
        }
    }

    private static List<Candidate> expand(List<Species> rows, List<String> variants, long version) {
        var result = new ArrayList<Candidate>();
        for (Species row : rows) {
            for (String variant : variants) {
                result.add(new Candidate(row.sourceSpeciesKey(), row.speciesKey(), row.habitat(), variant, version));
            }
        }
        return List.copyOf(result);
    }

    public record Species(String sourceSpeciesKey, String speciesKey, Habitat habitat) { }
    /** sourceSpeciesKeyはpack識別子であり、正式種や診断結果への対応を意味しない。 */
    public record Candidate(String sourceSpeciesKey, String speciesKey, Habitat habitat,
                            String variantKey, long catalogVersion) { }
}
