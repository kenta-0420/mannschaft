package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 代理入力履歴レスポンスDTO。
 */
@Getter
@Builder
public class ProxyInputRecordResponse {

    private final Long id;

    @Schema(description = "代理入力同意ID（同意書紐付けのない履歴はnull）", nullable = true)
    private final Long proxyInputConsentId;
    private final Long subjectUserId;
    private final Long proxyUserId;
    private final String featureScope;
    private final String targetEntityType;
    private final Long targetEntityId;
    private final String inputSource;
    private final String originalStorageLocation;

    @Schema(description = "監査ログID（未紐付けはnull）", nullable = true)
    private final Long auditLogId;
    private final LocalDateTime createdAt;

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
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
