package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * ポイっとメモ更新リクエスト（すべてのフィールドはオプショナル）。
 */
public record UpdateQuickMemoRequest(

        @Size(max = 200)
        String title,

        @Size(max = 10000)
        String body,

        @Size(max = 10)
        List<Long> tagIds,

        Boolean reminderUsesDefault,

        @Valid
        @Size(max = 3)
        List<ReminderOffset> reminders
) {}
