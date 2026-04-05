package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 監査ログレスポンスDTO。
 */
@Getter
@Builder
public class AuditLogResponse {

    private final Long id;
    private final Long userId;
    private final Long targetUserId;
    private final Long teamId;
    private final Long organizationId;
    private final String eventType;
    private final String ipAddress;
    private final String userAgent;
    private final String sessionHash;
    private final String metadata;
    private final LocalDateTime createdAt;

    public static AuditLogResponse from(AuditLogEntity entity) {
        return AuditLogResponse.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .targetUserId(entity.getTargetUserId())
                .teamId(entity.getTeamId())
                .organizationId(entity.getOrganizationId())
                .eventType(entity.getEventType())
                .ipAddress(entity.getIpAddress())
                .userAgent(entity.getUserAgent())
                .sessionHash(entity.getSessionHash())
                .metadata(entity.getMetadata())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
