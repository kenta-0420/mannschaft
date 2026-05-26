package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知設定レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PreferenceResponse {

    Long    id;
    Long    userId;

    PreferenceScopeDto scope;
    Boolean            isEnabled;
    PreferenceAuditDto audit;

    public record PreferenceScopeDto(String scopeType, Long scopeId) {}

    public record PreferenceAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
