package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link JobMatchingVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("JobMatchingVisibilityMapper")
class JobMatchingVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(VisibilityScope.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(VisibilityScope v) {
        assertThat(JobMatchingVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("TEAM_MEMBERS -> MEMBERS_ONLY")
    void mapsTeamMembers() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.TEAM_MEMBERS))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("TEAM_MEMBERS_SUPPORTERS -> SUPPORTERS_AND_ABOVE (マスター裁可 C-2)")
    void mapsTeamMembersSupporters() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.TEAM_MEMBERS_SUPPORTERS))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("JOBBER_INTERNAL -> CUSTOM (Resolver 内で個別実装)")
    void mapsJobberInternal() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.JOBBER_INTERNAL))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("JOBBER_PUBLIC_BOARD -> PUBLIC")
    void mapsJobberPublicBoard() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.JOBBER_PUBLIC_BOARD))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("ORGANIZATION_SCOPE -> ORGANIZATION_WIDE")
    void mapsOrganizationScope() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.ORGANIZATION_SCOPE))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }

    @Test
    @DisplayName("CUSTOM_TEMPLATE -> CUSTOM_TEMPLATE")
    void mapsCustomTemplate() {
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.CUSTOM_TEMPLATE))
            .isEqualTo(StandardVisibility.CUSTOM_TEMPLATE);
    }
}
