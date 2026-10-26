package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.UserVoiceInputConsentEntity;

import java.time.LocalDateTime;

/**
 * 音声入力同意レスポンス。
 */
public record VoiceInputConsentResponse(
        boolean hasConsent,
        Integer version,
        LocalDateTime consentedAt
) {
    public static VoiceInputConsentResponse active(UserVoiceInputConsentEntity entity) {
        return new VoiceInputConsentResponse(true, entity.getVersion(), entity.getConsentedAt());
    }

    public static VoiceInputConsentResponse inactive() {
        return new VoiceInputConsentResponse(false, null, null);
    }
}
