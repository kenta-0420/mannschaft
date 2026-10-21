package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.RenderStyle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CLIP01/02/04: 開発版の明示IDと公開境界を実カタログで検証する。 */
class RanchDevelopmentClipCatalogTest {
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "LAND", List.of("ALLOSAURUS", "TRICERATOPS", "SPINOSAURUS", "LONGNECK_SPIKES",
                    "STEGOSAURUS", "BRACHIOSAURUS", "PINACOSAURUS", "TYRANNOSAURUS",
                    "GIGANOTOSAURUS", "IGUANODON", "ANKYLOSAURUS", "PARASAUROLOPHUS"),
            "SEA", List.of("PLESIOSAURUS", "ARCHELON", "MARINE_LONGJAW"),
            "AIR", List.of("PTERANODON"));

    @Test
    void 明示16種が生息地別に揃い孵化後96通りだけ一致する() {
        var catalog = catalog(true, "ranch-isolated");
        int combinations = 0;
        for (Habitat habitat : Habitat.values()) {
            assertThat(catalog.candidates(habitat)).extracting(RanchDevelopmentClipCatalog.Candidate::speciesKey)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED.get(habitat.name()).stream()
                            .map(key -> "DEV_CLIP_" + key).toList());
            for (String species : EXPECTED.get(habitat.name())) {
                for (DinosaurStage stage : List.of(DinosaurStage.BABY, DinosaurStage.JUVENILE,
                        DinosaurStage.ADULT)) {
                    for (RenderStyle style : RenderStyle.values()) {
                        var found = catalog.find(3L, "DEV_CLIP_" + species, "DEV_BASE_16_CLIPS_V1",
                                stage, style).orElseThrow();
                        assertThat(found.habitat()).isEqualTo(habitat);
                        assertThat(found.sourceSpeciesKey()).isEqualTo(species.toLowerCase(java.util.Locale.ROOT)
                                .replace('_', '-'));
                        combinations++;
                    }
                }
            }
        }
        assertThat(combinations).isEqualTo(96);
    }

    @Test
    void 明示optinと許可profileが揃う場合だけ候補を返す() {
        for (String allowed : List.of("dev", "test", "ranch-isolated")) {
            assertThat(catalog(true, allowed).candidates(Habitat.LAND)).hasSize(12);
            assertThat(catalog(false, allowed).candidates(Habitat.LAND)).isEmpty();
        }
        for (String[] blocked : List.of(new String[]{}, new String[]{"staging"},
                new String[]{"prod"}, new String[]{"production"}, new String[]{"prod", "dev"},
                new String[]{"production", "test"}, new String[]{"prod", "ranch-isolated"})) {
            var catalog = catalog(true, blocked);
            for (Habitat habitat : Habitat.values()) assertThat(catalog.candidates(habitat)).isEmpty();
            assertThat(catalog.find(3L, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
    }

    @Test
    void nullと未知IDと旧版と卵は解決しない() {
        var catalog = catalog(true, "dev");
        assertThat(catalog.candidates(null)).isEmpty();
        assertThat(catalog.find(null, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        for (Long version : List.of(1L, 2L, 4L)) {
            assertThat(catalog.find(version, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
        for (String species : new String[]{null, "", "TRICERATOPS", "DEV_TRICERATOPS", "DEV_CLIP_UNKNOWN"}) {
            assertThat(catalog.find(3L, species, "DEV_BASE_16_CLIPS_V1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
        for (String variant : new String[]{null, "", "BASE_ONLY", "DEV_ORANGE_96_WALK_V1"}) {
            assertThat(catalog.find(3L, "DEV_CLIP_TRICERATOPS", variant,
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
        for (DinosaurStage stage : new DinosaurStage[]{null, DinosaurStage.EGG}) {
            assertThat(catalog.find(3L, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                    stage, RenderStyle.PIXEL)).isEmpty();
        }
        assertThat(catalog.find(3L, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                DinosaurStage.BABY, null)).isEmpty();
    }

    private RanchDevelopmentClipCatalog catalog(boolean enabled, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new RanchDevelopmentClipCatalog(enabled, environment, new ObjectMapper());
    }
}
