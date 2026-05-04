package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ConfirmableUnconfirmedVisibilityMapper} の exhaustive 単体テスト。
 *
 * <p>設計書 §13.3 — Mapper 網羅性を CI で保証する。
 */
@DisplayName("ConfirmableUnconfirmedVisibilityMapper")
class ConfirmableUnconfirmedVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(UnconfirmedVisibility.class)
    @DisplayName("全ての値が non-null な StandardVisibility に対応する")
    void every_value_maps_to_some_standard(UnconfirmedVisibility v) {
        assertThat(ConfirmableUnconfirmedVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("HIDDEN -> PRIVATE")
    void mapsHidden() {
        assertThat(ConfirmableUnconfirmedVisibilityMapper.toStandard(UnconfirmedVisibility.HIDDEN))
            .isEqualTo(StandardVisibility.PRIVATE);
    }

    @Test
    @DisplayName("CREATOR_AND_ADMIN -> CUSTOM (送信者本人または ADMIN/DEPUTY_ADMIN、Resolver 個別実装)")
    void mapsCreatorAndAdmin() {
        assertThat(ConfirmableUnconfirmedVisibilityMapper.toStandard(UnconfirmedVisibility.CREATOR_AND_ADMIN))
            .isEqualTo(StandardVisibility.CUSTOM);
    }

    @Test
    @DisplayName("ALL_MEMBERS -> MEMBERS_ONLY")
    void mapsAllMembers() {
        assertThat(ConfirmableUnconfirmedVisibilityMapper.toStandard(UnconfirmedVisibility.ALL_MEMBERS))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }
}
