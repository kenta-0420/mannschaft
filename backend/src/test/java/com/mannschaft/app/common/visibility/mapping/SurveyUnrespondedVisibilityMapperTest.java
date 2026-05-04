package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.survey.UnrespondedVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link SurveyUnrespondedVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("SurveyUnrespondedVisibilityMapper")
class SurveyUnrespondedVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(UnrespondedVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(UnrespondedVisibility v) {
        assertThat(SurveyUnrespondedVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("HIDDEN -> PRIVATE")
    void mapsHidden() {
        assertThat(SurveyUnrespondedVisibilityMapper.toStandard(UnrespondedVisibility.HIDDEN))
            .isEqualTo(StandardVisibility.PRIVATE);
    }

    @Test
    @DisplayName("CREATOR_AND_ADMIN -> CUSTOM (作成者または ADMIN、Resolver 個別実装)")
    void mapsCreatorAndAdmin() {
        assertThat(SurveyUnrespondedVisibilityMapper.toStandard(UnrespondedVisibility.CREATOR_AND_ADMIN))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("ALL_MEMBERS -> MEMBERS_ONLY")
    void mapsAllMembers() {
        assertThat(SurveyUnrespondedVisibilityMapper.toStandard(UnrespondedVisibility.ALL_MEMBERS))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }
}
