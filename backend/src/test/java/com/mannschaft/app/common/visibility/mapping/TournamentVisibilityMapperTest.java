package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.tournament.TournamentVisibility;
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
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W5 内輪・応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // 判定根拠: 設計書 F08.7 §権限と役割で SUPPORTER は「公開設定の大会の...閲覧のみ」。
        // MEMBERS_ONLY 大会は応援者に見せない内輪 → 応援者除外の MEMBERS_AND_ABOVE。
        // 挙動変更: SUPPORTER は MEMBERS_ONLY の大会を閲覧できなくなる。
        assertThat(TournamentVisibilityMapper.toStandard(TournamentVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }
}
