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
    @DisplayName("ALL_MEMBERS -> MEMBERS_ONLY")
    void mapsAllMembers() {
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.ALL_MEMBERS))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE -> SUPPORTERS_AND_ABOVE")
    void mapsSupportersAndAbove() {
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("ADMIN_ONLY -> ADMINS_ONLY")
    void mapsAdminOnly() {
        assertThat(AlbumVisibilityMapper.toStandard(AlbumVisibility.ADMIN_ONLY))
            .isEqualTo(StandardVisibility.ADMINS_ONLY);
    }
}
