package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 代理入力同意書レスポンスDTO（F14.1）。
 * scannedDocumentS3Key は秘匿情報のため返さず、hasScannedDocument フラグのみ公開する。
 * スキャン文書の閲覧は GET /scan-download-url で presigned URL を発行する。
 */
@Getter
@Builder
public class ProxyInputConsentResponse {

    private Long id;
    private Long subjectUserId;
    private Long proxyUserId;
    private Long organizationId;
    private String consentMethod;

    /** スキャン文書が登録済みかどうか。 */
    private boolean hasScannedDocument;
    /** 後見登記事項証明書が登録済みかどうか。 */
    private boolean hasGuardianCertificate;

    private Long witnessUserId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveUntil;

    /** 同意書の状態: PENDING_APPROVAL / APPROVED / REVOKED */
    private String status;

    private Long approvedByUserId;
    private LocalDateTime approvedAt;
    private LocalDateTime revokedAt;
    private String revokeMethod;
    private String revokeReason;

    private List<String> scopes;
    private LocalDateTime createdAt;

    public static ProxyInputConsentResponse from(ProxyInputConsentEntity entity) {
        String status;
        if (entity.getRevokedAt() != null) {
            status = "REVOKED";
        } else if (entity.getApprovedAt() != null) {
            status = "APPROVED";
        } else {
            status = "PENDING_APPROVAL";
        }

        List<String> scopeNames = entity.getScopes().stream()
                .map(scope -> scope.getFeatureScope().name())
                .toList();

        return ProxyInputConsentResponse.builder()
                .id(entity.getId())
                .subjectUserId(entity.getSubjectUserId())
                .proxyUserId(entity.getProxyUserId())
                .organizationId(entity.getOrganizationId())
                .consentMethod(entity.getConsentMethod().name())
                .hasScannedDocument(entity.getScannedDocumentS3Key() != null)
                .hasGuardianCertificate(entity.getGuardianCertificateS3Key() != null)
                .witnessUserId(entity.getWitnessUserId())
                .effectiveFrom(entity.getEffectiveFrom())
                .effectiveUntil(entity.getEffectiveUntil())
                .status(status)
                .approvedByUserId(entity.getApprovedByUserId())
                .approvedAt(entity.getApprovedAt())
                .revokedAt(entity.getRevokedAt())
                .revokeMethod(entity.getRevokeMethod() != null ? entity.getRevokeMethod().name() : null)
                .revokeReason(entity.getRevokeReason())
                .scopes(scopeNames)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
