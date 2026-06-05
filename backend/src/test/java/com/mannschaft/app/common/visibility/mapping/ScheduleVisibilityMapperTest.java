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
    @DisplayName("MEMBERS_ONLY → StandardVisibility.SCOPE_AFFILIATED（W5 所属者全員・挙動保存）")
    void members_only_maps_to_SCOPE_AFFILIATED() {
        // 判定根拠: schedule の応援者包含/除外は別軸の min_view_role（F03.1 §DB 設計）が司り、
        // ScheduleVisibility.MEMBERS_ONLY は「直接所属で評価する」所属軸を意味するに過ぎない。
        // 機械的な応援者除外は min_view_role='SUPPORTER+' を過剰制限するため、直接所属者全員 =
        // SCOPE_AFFILIATED へ正準化し挙動を保存する（= isMemberOf = 旧 MEMBERS_ONLY と同一判定）。
        assertThat(ScheduleVisibilityMapper.toStandard(ScheduleVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.SCOPE_AFFILIATED);
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
