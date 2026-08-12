package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.tournament.TournamentVisibility;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link TournamentVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("TournamentVisibilityMapper")
class TournamentVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(TournamentVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(TournamentVisibility v) {
        assertThat(TournamentVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE → StandardVisibility.SUPPORTERS_AND_ABOVE（同名写像）")
    void supporters_maps_to_SUPPORTERS_AND_ABOVE() {
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("MEMBERS_AND_ABOVE → StandardVisibility.MEMBERS_AND_ABOVE（同名写像・応援者除外）")
    void members_maps_to_MEMBERS_AND_ABOVE() {
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.MEMBERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("ADMINS_AND_ABOVE → StandardVisibility.ADMINS_AND_ABOVE（同名写像）")
    void admins_maps_to_ADMINS_AND_ABOVE() {
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.ADMINS_AND_ABOVE))
            .isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }

    @Test
    @DisplayName("SCOPE_AFFILIATED → StandardVisibility.SCOPE_AFFILIATED（旧 MEMBERS_ONLY 相当の正準値）")
    void scope_affiliated_maps_to_SCOPE_AFFILIATED() {
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.SCOPE_AFFILIATED))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }

    @Test
    @DisplayName("PARTICIPANTS_ONLY → StandardVisibility.CUSTOM（大会専用軸・正準対応値なし）")
    void participants_only_maps_to_CUSTOM() {
        // PARTICIPANTS_ONLY は「参加チーム関係者のみ」という大会専用セマンティクスのため
        // 正準に対応値が無く CUSTOM に写像し、Resolver の evaluateCustom で個別判定する。
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.PARTICIPANTS_ONLY))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    // -------------------------------------------------------------------
    // CMP-028 Phase C: toFunctional（逆写像）
    // -------------------------------------------------------------------

    @Test
    @DisplayName("toFunctional: PUBLIC のみ → TournamentVisibility.PUBLIC のみ")
    void toFunctional_PUBLICのみ() {
        assertThat(TournamentVisibilityMapper.toFunctional(Set.of(StandardVisibility.PUBLIC)))
                .containsExactly(TournamentVisibility.PUBLIC);
    }

    @Test
    @DisplayName("toFunctional: ラダー5値すべて → 対応する5つの TournamentVisibility")
    void toFunctional_ラダー全値() {
        assertThat(TournamentVisibilityMapper.toFunctional(Set.of(
                StandardVisibility.PUBLIC,
                StandardVisibility.SUPPORTERS_AND_ABOVE,
                StandardVisibility.MEMBERS_AND_ABOVE,
                StandardVisibility.ADMINS_AND_ABOVE,
                StandardVisibility.SCOPE_AFFILIATED)))
                .containsExactlyInAnyOrder(
                        TournamentVisibility.PUBLIC,
                        TournamentVisibility.SUPPORTERS_AND_ABOVE,
                        TournamentVisibility.MEMBERS_AND_ABOVE,
                        TournamentVisibility.ADMINS_AND_ABOVE,
                        TournamentVisibility.SCOPE_AFFILIATED);
    }

    /**
     * PARTICIPANTS_ONLY（CUSTOM）は行依存判定のため resolveVisibleLevels のラダー集合には現れず、
     * toFunctional の入力に CUSTOM が混ざっても無視される（呼び出し側が EXISTS 述語を別途 OR する）。
     */
    @Test
    @DisplayName("toFunctional: CUSTOM（行依存値）は無視される（対応する TournamentVisibility が無いため）")
    void toFunctional_行依存値は無視() {
        assertThat(TournamentVisibilityMapper.toFunctional(
                Set.of(StandardVisibility.PUBLIC, StandardVisibility.CUSTOM)))
                .containsExactly(TournamentVisibility.PUBLIC);
    }

    @Test
    @DisplayName("toFunctional: 空集合 → 空集合")
    void toFunctional_空集合() {
        assertThat(TournamentVisibilityMapper.toFunctional(Set.of())).isEmpty();
        assertThat(TournamentVisibilityMapper.toFunctional(null)).isEmpty();
    }
}
