package com.mannschaft.app.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 通知種別設定一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class TypePreferenceBulkUpdateRequest {

    @NotEmpty
    @Valid
    private final List<TypePreferenceEntry> preferences;

    /**
     * 通知種別設定の個別エントリ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TypePreferenceEntry {

        private final String notificationType;

        private final Boolean isEnabled;
    }
}
