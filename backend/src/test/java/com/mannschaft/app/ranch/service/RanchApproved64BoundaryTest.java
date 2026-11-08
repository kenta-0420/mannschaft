package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.RenderStyle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 保存版と新外見の交差、および空の識別子を拒否する。 */
class RanchApproved64BoundaryTest {
    @Test
    void 旧版と新外見の交差および空の種は拒否する() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("ranch-isolated");
        var catalog = new RanchDevelopmentClipCatalog(true, environment, new ObjectMapper());
        assertThat(catalog.candidates(null)).isEmpty();
        for (int variant = 1; variant <= 4; variant++) {
            String key = "DEV_APPEARANCE_" + variant;
            assertThat(catalog.find(3L, "DEV_CLIP_TRICERATOPS", key,
                    DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
            for (String species : new String[]{null, "", " "}) {
                assertThat(catalog.find(4L, species, key, DinosaurStage.BABY, RenderStyle.PIXEL)).isEmpty();
            }
        }
    }
}
