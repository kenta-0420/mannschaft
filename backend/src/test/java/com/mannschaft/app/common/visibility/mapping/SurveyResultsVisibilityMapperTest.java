package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.survey.ResultsVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link SurveyResultsVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("SurveyResultsVisibilityMapper")
class SurveyResultsVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ResultsVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(ResultsVisibility v) {
        assertThat(SurveyResultsVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("AFTER_RESPONSE -> CUSTOM (時間軸条件、Resolver 個別実装)")
    void mapsAfterResponse() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.AFTER_RESPONSE))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("AFTER_CLOSE -> CUSTOM (時間軸条件、Resolver 個別実装)")
    void mapsAfterClose() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.AFTER_CLOSE))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("ADMINS_ONLY -> ADMINS_ONLY")
    void mapsAdminsOnly() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.ADMINS_ONLY))
            .isEqualTo(StandardVisibility.ADMINS_ONLY);
    }

    @Test
    @DisplayName("VIEWERS_ONLY -> CUSTOM (限定リスト、Resolver 個別実装)")
    void mapsViewersOnly() {
        assertThat(SurveyResultsVisibilityMapper.toStandard(ResultsVisibility.VIEWERS_ONLY))
            .isEqualTo(StandardVisibility.CUSTOM);
    }
}
