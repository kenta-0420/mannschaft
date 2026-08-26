package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamVisibilityMapper} 単体テスト。
 *
 * <p>F00 Phase D-3 — {@link TeamEntity.Visibility} → {@link StandardVisibility}
 * の変換規則を検証する（ロールベース設計）。</p>
 */
@DisplayName("TeamVisibilityMapper — 単体テスト")
class TeamVisibilityMapperTest {

    @Test
    @DisplayName("PUBLIC は StandardVisibility.PUBLIC に変換される")
    void public_maps_to_public() {
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.PUBLIC))
                .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("GUESTS_AND_ABOVE は StandardVisibility.SCOPE_AFFILIATED に変換される（GUEST以上の全所属メンバー閲覧可）")
    void guests_and_above_maps_to_scope_affiliated() {
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.GUESTS_AND_ABOVE))
                .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE は StandardVisibility.SUPPORTERS_AND_ABOVE に変換される")
    void supporters_and_above_maps_to_supporters_and_above() {
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.SUPPORTERS_AND_ABOVE))
                .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("MEMBERS_AND_ABOVE は StandardVisibility.MEMBERS_AND_ABOVE に変換される（サポーター・ゲスト除外）")
    void members_and_above_maps_to_members_and_above() {
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.MEMBERS_AND_ABOVE))
                .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }
}
