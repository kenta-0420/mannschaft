package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.RenderStyle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 単一pilot以外を静止fallbackへ倒し、本番や無設定で素材を公開しない。 */
class RanchDevelopmentVisualCatalogTest {
    @Test
    void exactBabyPixelPilotOnlyInExplicitDevelopmentFixture() {
        var isolated = new MockEnvironment();
        isolated.setActiveProfiles("ranch-isolated");
        var catalog = new RanchDevelopmentVisualCatalog(true, isolated);
        var visual = catalog.find(2L, "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL).orElseThrow();
        assertThat(visual.assetKey()).isEqualTo("dev-triceratops-orange-pixel96-walk-v1");
        assertThat(visual.sha256()).isEqualTo(
                "8ad7de84f1d0f495f9aeda86175f9c914fd0cbb788079b4f3c7e101f91358117");
        assertThat(visual.cellPixels()).isEqualTo(96);
        assertThat(visual.frames()).isEqualTo(8);
        assertThat(catalog.find(2L, "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.JUVENILE, RenderStyle.PIXEL)).isEmpty();
        assertThat(catalog.find(2L, "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.ADULT, RenderStyle.PIXEL)).isEmpty();
        var paint = catalog.find(2L, "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PAINT_2D).orElseThrow();
        assertThat(paint.assetKey()).isEqualTo("dev-triceratops-orange-paint2d-walk-v1");
        assertThat(paint.sha256()).isEqualTo(
                "f7d5d8822ee2e41b8b41200bb0547195586c1ea8a558b294d38b445450322c19");
        assertThat(paint.frames()).isEqualTo(8);
        assertThat(paint.xBoundaries()).containsExactly(0, 444, 887, 1331, 1774);
        assertThat(paint.yBoundaries()).containsExactly(0, 444, 887);
        assertThat(catalog.find(2L, "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.JUVENILE, RenderStyle.PAINT_2D)).isEmpty();
        assertThat(catalog.find(1L, "S01", "V1", DinosaurStage.BABY,
                RenderStyle.PIXEL)).isEmpty();
    }

    @Test
    void productionAndDefaultOffNeverExposePilot() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThat(new RanchDevelopmentVisualCatalog(true, prod).find(2L,
                "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        assertThat(new RanchDevelopmentVisualCatalog(false, new MockEnvironment()).find(2L,
                "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        assertThat(new RanchDevelopmentVisualCatalog(true, new MockEnvironment()).find(2L,
                "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        var staging = new MockEnvironment();
        staging.setActiveProfiles("staging");
        assertThat(new RanchDevelopmentVisualCatalog(true, staging).find(2L,
                "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
        var mixed = new MockEnvironment();
        mixed.setActiveProfiles("prod", "ranch-isolated");
        assertThat(new RanchDevelopmentVisualCatalog(true, mixed).find(2L,
                "DEV_TRICERATOPS", "DEV_ORANGE_96_WALK_V1",
                DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
    }
}
