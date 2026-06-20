package com.mannschaft.app.reflection.dto;

import lombok.Builder;

/**
 * 想起通知設定レスポンス（F06.5・§7 #14 / §2.7）。
 *
 * @param remindHour 想起通知時刻（0-23・ユーザー TZ・未設定は既定 8）
 */
@Builder
public record ReflectionSettingsResponse(
        Integer remindHour
) {
}
