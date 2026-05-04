package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ActivityVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("ActivityVisibilityMapper")
class ActivityVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ActivityVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(ActivityVisibility v) {
        assertThat(ActivityVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(ActivityVisibilityMapper.toStandard(ActivityVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_ONLY")
    void members_only_maps_to_MEMBERS_ONLY() {
        assertThat(ActivityVisibilityMapper.toStandard(ActivityVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }
}
