package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 代理入力履歴レスポンスDTO。
 */
@Getter
@Builder
public class ProxyInputRecordResponse {

    private final Long id;

    @Schema(types = {"integer", "null"}, nullable = true,
            description = "代理入力同意ID（同意書紐付けのない履歴はnull）")
    private final Long proxyInputConsentId;
    private final Long subjectUserId;
    private final Long proxyUserId;
    private final String featureScope;
    private final String targetEntityType;
    private final Long targetEntityId;
    private final String inputSource;
    private final String originalStorageLocation;

    @Schema(types = {"integer", "null"}, nullable = true,
            description = "監査ログID（未紐付けはnull）")
    private final Long auditLogId;
    private final Instant createdAt;

    public static ProxyInputRecordResponse from(ProxyInputRecordEntity entity) {
        return ProxyInputRecordResponse.builder()
                .id(entity.getId())
                .proxyInputConsentId(entity.getProxyInputConsentId())
                .subjectUserId(entity.getSubjectUserId())
                .proxyUserId(entity.getProxyUserId())
                .featureScope(entity.getFeatureScope())
                .targetEntityType(entity.getTargetEntityType())
                .targetEntityId(entity.getTargetEntityId())
                .inputSource(entity.getInputSource().name())
                .originalStorageLocation(entity.getOriginalStorageLocation())
                .auditLogId(entity.getAuditLogId())
                .createdAt(entity.getCreatedAt()
                        .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
                        .toInstant())
                .build();
    }
}
