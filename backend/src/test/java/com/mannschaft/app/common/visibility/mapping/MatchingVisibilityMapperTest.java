package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.matching.MatchVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link MatchingVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("MatchingVisibilityMapper")
class MatchingVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(MatchVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(MatchVisibility v) {
        assertThat(MatchingVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PLATFORM -> PUBLIC")
    void mapsPlatform() {
        assertThat(MatchingVisibilityMapper.toStandard(MatchVisibility.PLATFORM))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("ORGANIZATION -> ORGANIZATION_WIDE")
    void mapsOrganization() {
        assertThat(MatchingVisibilityMapper.toStandard(MatchVisibility.ORGANIZATION))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }
}
