package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import java.util.Set;
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
    @DisplayName("TEAM_MEMBERS -> SCOPE_AFFILIATED（挙動不変・名称正準化 W3）")
    void mapsTeamMembers() {
        // 挙動不変: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
        assertThat(JobMatchingVisibilityMapper.toStandard(VisibilityScope.TEAM_MEMBERS))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
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

    // -------------------------------------------------------------------
    // CMP-028 Phase C: toFunctional（逆写像）
    // -------------------------------------------------------------------

    @Test
    @DisplayName("toFunctional: PUBLIC のみ → JOBBER_PUBLIC_BOARD のみ")
    void toFunctional_PUBLICのみ() {
        assertThat(JobMatchingVisibilityMapper.toFunctional(Set.of(StandardVisibility.PUBLIC)))
                .containsExactly(VisibilityScope.JOBBER_PUBLIC_BOARD);
    }

    @Test
    @DisplayName("toFunctional: ラダー4値すべて → 対応する4つの VisibilityScope")
    void toFunctional_ラダー全値() {
        assertThat(JobMatchingVisibilityMapper.toFunctional(Set.of(
                StandardVisibility.PUBLIC,
                StandardVisibility.SCOPE_AFFILIATED,
                StandardVisibility.SUPPORTERS_AND_ABOVE,
                StandardVisibility.ORGANIZATION_WIDE)))
                .containsExactlyInAnyOrder(
                        VisibilityScope.JOBBER_PUBLIC_BOARD,
                        VisibilityScope.TEAM_MEMBERS,
                        VisibilityScope.TEAM_MEMBERS_SUPPORTERS,
                        VisibilityScope.ORGANIZATION_SCOPE);
    }

    /**
     * JOBBER_INTERNAL（CUSTOM）・CUSTOM_TEMPLATE はどちらも行依存判定
     * （前者はロール照合、後者はテンプレート評価）のため resolveVisibleLevels の
     * ラダー集合には現れず、toFunctional の入力に混ざっても無視される。
     */
    @Test
    @DisplayName("toFunctional: CUSTOM / CUSTOM_TEMPLATE（行依存値）は無視される")
    void toFunctional_行依存値は無視() {
        assertThat(JobMatchingVisibilityMapper.toFunctional(
                Set.of(StandardVisibility.PUBLIC, StandardVisibility.CUSTOM,
                        StandardVisibility.CUSTOM_TEMPLATE)))
                .containsExactly(VisibilityScope.JOBBER_PUBLIC_BOARD);
    }

    @Test
    @DisplayName("toFunctional: 空集合 → 空集合")
    void toFunctional_空集合() {
        assertThat(JobMatchingVisibilityMapper.toFunctional(Set.of())).isEmpty();
        assertThat(JobMatchingVisibilityMapper.toFunctional(null)).isEmpty();
    }
}
