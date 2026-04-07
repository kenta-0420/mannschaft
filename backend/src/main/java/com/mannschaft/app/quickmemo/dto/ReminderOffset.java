package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * リマインドオフセット（日数＋時刻）。CreateQuickMemoRequest / UpdateQuickMemoRequest に埋め込む。
 */
public record ReminderOffset(

        @NotNull @Min(1) @Max(90)
        Integer dayOffset,

        @NotNull
        @Pattern(regexp = "^([0-1][0-9]|2[0-3]):(00|30)$", message = "時刻は HH:00 または HH:30 の形式で指定してください")
        String time
) {}
