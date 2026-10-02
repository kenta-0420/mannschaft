package com.mannschaft.app.schedule.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("カレンダー内容DTOの後方互換")
class CalendarContentDtoTest {

    @Test
    void 既存3引数と5引数は色未解決のまま構築できる() {
        var three = new CalendarEntryResponse.CalendarContentDto("予定", "PRACTICE", "SCHEDULED");
        var five = new CalendarEntryResponse.CalendarContentDto("想起", "REFLECTION_RECALL", null, "uuid", "REFLECTION_RECALL");
        assertThat(three.title()).isEqualTo("予定");
        assertThat(three.referenceUuid()).isNull();
        assertThat(three.color()).isNull();
        assertThat(three.scopeAutoColor()).isNull();
        assertThat(five.referenceUuid()).isEqualTo("uuid");
        assertThat(five.color()).isNull();
        assertThat(five.scopeAutoColor()).isNull();
    }

    @Test
    void 既存8引数は指定色を保持し新しい自動色だけ未解決となる() {
        var content = new CalendarEntryResponse.CalendarContentDto("予定", "PRACTICE", "SCHEDULED",
                null, null, "#123456", CalendarColorSource.CATEGORY, "#123456");
        assertThat(content.color()).isEqualTo("#123456");
        assertThat(content.colorSource()).isEqualTo(CalendarColorSource.CATEGORY);
        assertThat(content.categoryColor()).isEqualTo("#123456");
        assertThat(content.scopeAutoColor()).isNull();
    }

    @Test
    void 既存withColorは独立自動色と識別子を引き継ぐ() {
        var content = new CalendarEntryResponse.CalendarContentDto("想起", "REFLECTION_RECALL", null,
                "uuid", "REFLECTION_RECALL", "#F59E0B", CalendarColorSource.SCHEDULE, null, "#2563EB");
        var changed = content.withColor("#6366F1", CalendarColorSource.SCHEDULE, "#123456");
        assertThat(changed.scopeAutoColor()).isEqualTo("#2563EB");
        assertThat(changed.referenceUuid()).isEqualTo("uuid");
        assertThat(changed.referenceKind()).isEqualTo("REFLECTION_RECALL");
        assertThat(changed.color()).isEqualTo("#6366F1");
        assertThat(changed.categoryColor()).isEqualTo("#123456");
        assertThat(content.color()).isEqualTo("#F59E0B");
    }
}
