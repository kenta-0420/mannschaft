package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.event.entity.EventVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link EventVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("EventVisibilityMapper")
class EventVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(EventVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(EventVisibility v) {
        assertThat(EventVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(EventVisibilityMapper.toStandard(EventVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_ONLY")
    void members_only_maps_to_MEMBERS_ONLY() {
        assertThat(EventVisibilityMapper.toStandard(EventVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("SUPPORTERS_AND_ABOVE → StandardVisibility.SUPPORTERS_AND_ABOVE")
    void supporters_and_above_maps_to_SUPPORTERS_AND_ABOVE() {
        assertThat(EventVisibilityMapper.toStandard(EventVisibility.SUPPORTERS_AND_ABOVE))
            .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }
}
