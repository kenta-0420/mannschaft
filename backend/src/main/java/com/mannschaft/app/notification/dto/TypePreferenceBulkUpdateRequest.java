package com.mannschaft.app.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 通知種別設定一括更新リクエストDTO（F04.3 ハイブリッド方式）。
 *
 * <p>各エントリは {@code channelOverride} を持ち、単一モード（false）と Dual モード（true）を
 * 切り替える。条件付き必須（単一なら {@code isEnabled}、Dual なら {@code inAppEnabled} /
 * {@code pushEnabled}）は Service 層で検証する。</p>
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

        @NotNull
        private final String notificationType;

        @NotNull
        private final Boolean channelOverride;

        /** 単一モード（channelOverride=false）のとき必須。 */
        private final Boolean isEnabled;

        /** Dual モード（channelOverride=true）のとき必須。 */
        private final Boolean inAppEnabled;

        /** Dual モード（channelOverride=true）のとき必須。 */
        private final Boolean pushEnabled;
    }
}
