package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.gallery.AlbumVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link AlbumVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("AlbumVisibilityMapper")
class AlbumVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(AlbumVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(AlbumVisibility v) {
        assertThat(AlbumVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("ALL_MEMBERS -> SCOPE_AFFILIATED（挙動不変・名称正準化 W3）")
    void mapsAllMembers() {
        // 挙動不変: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.ALL_MEMBERS))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE -> SUPPORTERS_AND_ABOVE")
    void mapsSupportersAndAbove() {
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("ADMIN_ONLY -> ADMINS_AND_ABOVE（挙動不変・名称正準化 W4）")
    void mapsAdminOnly() {
        // 挙動不変: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.ADMIN_ONLY))
            .isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }
}
