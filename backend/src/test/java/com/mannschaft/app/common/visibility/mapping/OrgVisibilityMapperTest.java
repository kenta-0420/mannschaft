package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.actionmemo.enums.OrgVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link OrgVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("OrgVisibilityMapper")
class OrgVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(OrgVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(OrgVisibility v) {
        assertThat(OrgVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("TEAM_ONLY -> SCOPE_AFFILIATED（W5 所属者全員・挙動保存）")
    void mapsTeamOnly() {
        // 判定根拠: F02.5 アクションメモのチームタイムラインは「チームメンバーのみ」だが、
        // 応援者(SUPPORTER)を区別する記述が設計書・enum に無く内輪確証が取れない。
        // 過剰制限を避けるため直接所属者全員 = SCOPE_AFFILIATED へ正準化し挙動を保存（= isMemberOf）。
        // ※応援者除外の内輪とすべきかは要マスター裁可（W5 報告）。
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.TEAM_ONLY))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }

    @Test
    @DisplayName("ORG_WIDE -> ORGANIZATION_WIDE")
    void mapsOrgWide() {
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.ORG_WIDE))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }
}
