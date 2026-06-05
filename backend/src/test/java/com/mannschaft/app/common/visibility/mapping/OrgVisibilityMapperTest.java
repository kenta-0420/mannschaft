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
    @DisplayName("TEAM_ONLY -> MEMBERS_AND_ABOVE（W5 内輪・マスター御裁可 2026-06-05・応援者除外）")
    void mapsTeamOnly() {
        // 判定根拠: アクションメモ（業務行動メモ＝仕事の進捗の見える化・チームタイムライン投稿）は
        // 「メンバー限定の内輪」でありマスター御裁可により応援者(SUPPORTER)には見せない。
        // 出力先を MEMBERS_AND_ABOVE（hasRoleOrAbove("MEMBER") / SUPPORTER・GUEST 除外）へ。
        // 機能 enum 名・DB 値は据え置き（④A）。挙動変更: 直接所属の SUPPORTER は不可視に。
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.TEAM_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("ORG_WIDE -> ORGANIZATION_WIDE")
    void mapsOrgWide() {
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.ORG_WIDE))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }
}
