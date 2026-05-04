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
    @DisplayName("TEAM_ONLY -> MEMBERS_ONLY")
    void mapsTeamOnly() {
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.TEAM_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("ORG_WIDE -> ORGANIZATION_WIDE")
    void mapsOrgWide() {
        assertThat(OrgVisibilityMapper.toStandard(OrgVisibility.ORG_WIDE))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }
}
