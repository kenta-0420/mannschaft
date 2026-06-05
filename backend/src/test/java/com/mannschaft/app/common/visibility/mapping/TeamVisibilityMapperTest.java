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
 * の変換規則を検証する。</p>
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
    @DisplayName("ORGANIZATION_ONLY は StandardVisibility.ORGANIZATION_WIDE に変換される")
    void organization_only_maps_to_organization_wide() {
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.ORGANIZATION_ONLY))
                .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }

    @Test
    @DisplayName("PRIVATE は StandardVisibility.SCOPE_AFFILIATED に変換される（招待制・非公開チームはメンバー閲覧可 / 挙動不変・名称正準化 W3）")
    void private_maps_to_members_only() {
        // 挙動不変: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
        assertThat(TeamVisibilityMapper.toStandard(TeamEntity.Visibility.PRIVATE))
                .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }
}
