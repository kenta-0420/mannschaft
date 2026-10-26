package com.mannschaft.app.quickmemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalTime;

/**
 * ポイっとメモ設定更新リクエスト（すべてのフィールドはオプショナル）。
 */
public record UpdateSettingsRequest(

        Boolean reminderEnabled,

        @Min(1) @Max(90)
        Integer defaultOffset1Days,
        LocalTime defaultTime1,

        @Min(1) @Max(90)
        Integer defaultOffset2Days,
        LocalTime defaultTime2,

        @Min(1) @Max(90)
        Integer defaultOffset3Days,
        LocalTime defaultTime3
) {}
