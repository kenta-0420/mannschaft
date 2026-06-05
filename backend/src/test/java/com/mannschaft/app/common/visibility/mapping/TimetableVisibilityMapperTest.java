package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.timetable.TimetableVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link TimetableVisibilityMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。
 */
@DisplayName("TimetableVisibilityMapper")
class TimetableVisibilityMapperTest {

    @ParameterizedTest
    @EnumSource(TimetableVisibility.class)
    @DisplayName("全 enum 値が non-null な StandardVisibility にマップされる")
    void every_value_maps_to_non_null(TimetableVisibility v) {
        assertThat(TimetableVisibilityMapper.toStandard(v)).isNotNull();
    }

    @Test
    @DisplayName("MEMBERS_ONLY → StandardVisibility.MEMBERS_AND_ABOVE（W5 内輪・応援者除外）")
    void members_only_maps_to_MEMBERS_AND_ABOVE() {
        // 判定根拠: 設計書 F03.9 §DB 設計に「MEMBERS_ONLY: MEMBER 以上のみ」「PUBLIC: SUPPORTER も閲覧可」
        // と明記。応援者除外の MEMBERS_AND_ABOVE。挙動変更: SUPPORTER は MEMBERS_ONLY 時間割を閲覧不可に。
        assertThat(TimetableVisibilityMapper.toStandard(TimetableVisibility.MEMBERS_ONLY))
            .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("PUBLIC → StandardVisibility.PUBLIC")
    void public_maps_to_PUBLIC() {
        assertThat(TimetableVisibilityMapper.toStandard(TimetableVisibility.PUBLIC))
            .isEqualTo(StandardVisibility.PUBLIC);
    }
}
