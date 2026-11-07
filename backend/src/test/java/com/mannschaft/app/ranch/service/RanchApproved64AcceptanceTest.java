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

/** 承認済み素材の有限集合と保存済みv3の照合を検証する。 */
class RanchApproved64AcceptanceTest {
    private static final Map<Habitat, List<String>> SPECIES = Map.of(
            Habitat.LAND, List.of("ALLOSAURUS", "TRICERATOPS", "SPINOSAURUS", "LONGNECK_SPIKES",
                    "STEGOSAURUS", "BRACHIOSAURUS", "PINACOSAURUS", "TYRANNOSAURUS",
                    "GIGANOTOSAURUS", "IGUANODON", "ANKYLOSAURUS", "PARASAUROLOPHUS"),
            Habitat.SEA, List.of("PLESIOSAURUS", "ARCHELON", "MARINE_LONGJAW"),
            Habitat.AIR, List.of("PTERANODON"));
    private static final List<DinosaurStage> STAGES = List.of(DinosaurStage.BABY,
            DinosaurStage.JUVENILE, DinosaurStage.ADULT);

    @Test
    void v4候補は各種四つで全外見と保存済みv3を厳密に解決する() {
        var catalog = catalog(true, "ranch-isolated");
        for (var entry : SPECIES.entrySet()) {
            var expected = entry.getValue().stream().flatMap(s -> java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> "DEV_CLIP_" + s)).toList();
            assertThat(catalog.candidates(entry.getKey()))
                    .extracting(RanchDevelopmentClipCatalog.Candidate::speciesKey)
                    .containsExactlyInAnyOrderElementsOf(expected);
            for (String species : entry.getValue()) {
                for (DinosaurStage stage : STAGES) {
                    for (RenderStyle style : RenderStyle.values()) {
                        String key = "DEV_CLIP_" + species;
                        assertThat(catalog.find(3L, key, "DEV_BASE_16_CLIPS_V1", stage, style))
                                .hasValueSatisfying(row -> assertThat(row.habitat()).isEqualTo(entry.getKey()));
                        for (int variant = 1; variant <= 4; variant++) {
                            assertThat(catalog.find(4L, key, "DEV_APPEARANCE_" + variant, stage, style))
                                    .hasValueSatisfying(row -> assertThat(row.habitat()).isEqualTo(entry.getKey()));
                        }
                    }
                }
            }
        }
    }

    @Test
    void 不正な組合せと本番混在は解決しない() {
        var catalog = catalog(true, "dev");
        for (String variant : new String[]{null, "", "DEV_APPEARANCE_0", "DEV_APPEARANCE_5", "DEV_BASE_16_CLIPS_V1"}) {
            assertThat(catalog.find(4L, "DEV_CLIP_TRICERATOPS", variant,
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
        for (Long version : new Long[]{null, 2L, 5L}) {
            assertThat(catalog.find(version, "DEV_CLIP_TRICERATOPS", "DEV_APPEARANCE_1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
        assertThat(catalog.find(4L, "DEV_CLIP_UNKNOWN", "DEV_APPEARANCE_1", DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        assertThat(catalog.find(4L, "DEV_CLIP_TRICERATOPS", "DEV_APPEARANCE_1", DinosaurStage.EGG, RenderStyle.PIXEL)).isEmpty();
        assertThat(catalog.find(4L, "DEV_CLIP_TRICERATOPS", "DEV_APPEARANCE_1", null, RenderStyle.PIXEL)).isEmpty();
        assertThat(catalog.find(4L, "DEV_CLIP_TRICERATOPS", "DEV_APPEARANCE_1", DinosaurStage.BABY, null)).isEmpty();
        for (var blocked : List.of(catalog(false, "dev"), catalog(true, "prod", "dev"),
                catalog(true, "production", "ranch-isolated"), catalog(true, "staging"))) {
            for (Habitat habitat : Habitat.values()) assertThat(blocked.candidates(habitat)).isEmpty();
            assertThat(blocked.find(4L, "DEV_CLIP_TRICERATOPS", "DEV_APPEARANCE_1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
            assertThat(blocked.find(3L, "DEV_CLIP_TRICERATOPS", "DEV_BASE_16_CLIPS_V1",
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        }
    }

    private RanchDevelopmentClipCatalog catalog(boolean enabled, String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new RanchDevelopmentClipCatalog(enabled, environment, new ObjectMapper());
    }
}
