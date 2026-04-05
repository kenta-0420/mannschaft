package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 個人スケジュール作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePersonalScheduleRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @Size(max = 300)
    private final String location;

    @NotNull
    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    @NotNull
    private final Boolean allDay;

    private final String eventType;

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private final String color;

    @Size(max = 3)
    private final List<Integer> reminders;

    private final RecurrenceRuleDto recurrenceRule;

    /**
     * eventType のデフォルト値を返す。null の場合は OTHER を返す。
     */
    public String getEventTypeOrDefault() {
        return eventType != null ? eventType : "OTHER";
    }
}
