package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 通知種別設定レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class TypePreferenceResponse {

    Long    id;
    Long    userId;
    String  notificationType;
    Boolean isEnabled;

    TypePrefAuditDto audit;

    public record TypePrefAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
