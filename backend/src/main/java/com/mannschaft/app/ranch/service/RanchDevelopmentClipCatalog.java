package com.mannschaft.app.ranch.service;

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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * 16基礎種の開発用IDを固定する。正式診断・出生対応や公開素材の承認を表さない。
 * null・未登録の組合せは解決せず、保存済み個体のIDを変更する責務は持たない。
 */
@Component
public final class RanchDevelopmentClipCatalog {
    public static final long CATALOG_VERSION = 3L;
    public static final String VARIANT_KEY = "DEV_BASE_16_CLIPS_V1";
    private static final int SPECIES_COUNT = 16;
    private static final String RESOURCE = "ranch/development/base16-v3.json";
    private static final String MANIFEST_SHA256 =
            "bcc001841b2efaf75678376112303af216e0ae805781619bafc522a145efa28c";
    private final List<Candidate> species;

    public RanchDevelopmentClipCatalog(
            @Value("${mannschaft.ranch.development-clips:false}") boolean requested,
            Environment environment, ObjectMapper json) {
        // 許可profileが混在しても、本番profileが一つでもあれば開発IDを選定・公開しない。
        boolean enabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"))
                && environment.acceptsProfiles(Profiles.of("dev | test | ranch-isolated"));
        species = enabled ? readCatalog(json) : List.of();
    }

    public List<Candidate> candidates(Habitat habitat) {
        return habitat == null ? List.of()
                : species.stream().filter(candidate -> candidate.habitat() == habitat).toList();
    }

    /** 卵素材を含まない開発packのため、孵化後の完全一致だけを許す。 */
    public Optional<Candidate> find(Long catalogVersion, String speciesKey, String variantKey,
                                    DinosaurStage stage, RenderStyle style) {
        if (catalogVersion == null || catalogVersion != CATALOG_VERSION
                || !VARIANT_KEY.equals(variantKey)
                || (stage != DinosaurStage.BABY && stage != DinosaurStage.JUVENILE && stage != DinosaurStage.ADULT)
                || (style != RenderStyle.PIXEL && style != RenderStyle.PAINT_2D)) {
            return Optional.empty();
        }
        return species.stream().filter(candidate -> candidate.speciesKey().equals(speciesKey)).findFirst();
    }

    private static List<Candidate> readCatalog(ObjectMapper json) {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            var root = json.readTree(input);
            if (!root.path("catalogVersion").isIntegralNumber()
                    || root.path("catalogVersion").longValue() != CATALOG_VERSION
                    || !VARIANT_KEY.equals(root.path("variantKey").asText())
                    || !MANIFEST_SHA256.equals(root.path("manifestSha256").asText())
                    || !root.path("species").isArray() || root.path("species").size() != SPECIES_COUNT) {
                throw new IllegalStateException("開発クリップカタログの固定契約が不正です");
            }
            var rows = json.readerForListOf(Candidate.class).<List<Candidate>>readValue(root.path("species"));
            var sourceKeys = new HashSet<String>();
            var speciesKeys = new HashSet<String>();
            for (Candidate row : rows) {
                if (row.sourceSpeciesKey() == null || row.sourceSpeciesKey().isBlank()
                        || row.speciesKey() == null || !row.speciesKey().startsWith("DEV_CLIP_")
                        || row.speciesKey().length() > 60 || row.habitat() == null
                        || !sourceKeys.add(row.sourceSpeciesKey()) || !speciesKeys.add(row.speciesKey())) {
                    throw new IllegalStateException("開発クリップカタログのIDまたは生息地が不正です");
                }
            }
            return List.copyOf(rows);
        } catch (IOException exception) {
            throw new IllegalStateException("開発クリップカタログを読み込めません", exception);
        }
    }

    /** sourceSpeciesKeyはpackの識別子であり、正式種や診断結果への対応は意味しない。 */
    public record Candidate(String sourceSpeciesKey, String speciesKey, Habitat habitat) { }
}
