package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.schedule.ScheduleVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ScheduleVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 *
 * <p>注意: {@code ORGANIZATION} は {@link StandardVisibility#ORGANIZATION_WIDE} に
 * 写像される（名称が異なる点に留意）。
 */
@DisplayName("ScheduleVisibilityMapper")
class ScheduleVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(ScheduleVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(ScheduleVisibility v) {
        assertThat(ScheduleVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_ONLY")
    void members_only_maps_to_MEMBERS_ONLY() {
        assertThat(ScheduleVisibilityMapper.toStandard(ScheduleVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("ORGANIZATION → StandardVisibility.ORGANIZATION_WIDE")
    void organization_maps_to_ORGANIZATION_WIDE() {
        assertThat(ScheduleVisibilityMapper.toStandard(ScheduleVisibility.ORGANIZATION))
            .isEqualTo(StandardVisibility.ORGANIZATION_WIDE);
    }

    @Test
    @DisplayName("CUSTOM_TEMPLATE → StandardVisibility.CUSTOM_TEMPLATE")
    void custom_template_maps_to_CUSTOM_TEMPLATE() {
        assertThat(ScheduleVisibilityMapper.toStandard(ScheduleVisibility.CUSTOM_TEMPLATE))
            .isEqualTo(StandardVisibility.CUSTOM_TEMPLATE);
    }
}
