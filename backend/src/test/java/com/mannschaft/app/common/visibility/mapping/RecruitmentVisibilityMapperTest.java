package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link RecruitmentVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("RecruitmentVisibilityMapper")
class RecruitmentVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(RecruitmentVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(RecruitmentVisibility v) {
        assertThat(RecruitmentVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC -> PUBLIC")
    void mapsPublic() {
        assertThat(RecruitmentVisibilityMapper.toStandard(RecruitmentVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("SCOPE_ONLY -> MEMBERS_ONLY")
    void mapsScopeOnly() {
        assertThat(RecruitmentVisibilityMapper.toStandard(RecruitmentVisibility.SCOPE_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("SUPPORTERS_ONLY -> SUPPORTERS_AND_ABOVE (マスター裁可 C-2)")
    void mapsSupportersOnly() {
        assertThat(RecruitmentVisibilityMapper.toStandard(RecruitmentVisibility.SUPPORTERS_ONLY))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("CUSTOM_TEMPLATE -> CUSTOM_TEMPLATE")
    void mapsCustomTemplate() {
        assertThat(RecruitmentVisibilityMapper.toStandard(RecruitmentVisibility.CUSTOM_TEMPLATE))
            .isEqualTo(StandardVisibility.CUSTOM_TEMPLATE);
    }
}
