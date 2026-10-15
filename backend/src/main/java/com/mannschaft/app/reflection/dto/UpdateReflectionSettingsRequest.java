package com.mannschaft.app.reflection.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 想起通知設定更新リクエスト（F06.5・§7 #15 / §2.7）。
 *
 * @param remindHour 想起通知時刻（必須・0-23）
 */
public record UpdateReflectionSettingsRequest(

        @NotNull(message = "通知時刻を指定してください")
        @Min(value = 0, message = "通知時刻は0〜23で指定してください")
        @Max(value = 23, message = "通知時刻は0〜23で指定してください")
        Integer remindHour
) {
}
