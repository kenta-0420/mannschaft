package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;

/**
 * カレンダー同期ON/OFFリクエストDTO。
 *
 * <p>Java Record を使用することで Jackson が引数なしのデシリアライズを正常に行える。
 * {@code @RequiredArgsConstructor} + {@code final} フィールド構成では
 * property-based Creator が認識されずデシリアライズエラーになるため Record に変更。</p>
 */
public record CalendarSyncToggleRequest(
        @NotNull Boolean isEnabled
) {}
