package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュール更新リクエストDTO。全フィールドnullable（部分更新）。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePersonalScheduleRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    private final Boolean allDay;

    private final String eventType;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private final String color;

    @Size(max = 3)
    private final List<Integer> reminders;

    private final RecurrenceRuleDto recurrenceRule;

    private final String updateScope;

    /**
     * updateScope のデフォルト値を返す。null の場合は THIS_ONLY を返す。
     */
    public String getUpdateScopeOrDefault() {
        return updateScope != null ? updateScope : "THIS_ONLY";
    }
}
