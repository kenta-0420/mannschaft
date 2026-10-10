package com.mannschaft.app.proxy.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.ProxyAuditEventTypes;
import com.mannschaft.app.proxy.dto.ProxyInputConsentResponse;
import com.mannschaft.app.proxy.dto.ProxyInputRecordResponse;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 代理入力同意書のビジネスロジックサービス（F14.1 Phase 12-α）。
 * 同意書の登録・承認・撤回・S3スキャン文書URL発行を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyInputConsentService {

    private static final Duration SCAN_URL_TTL = Duration.ofMinutes(5);
    private static final long MAX_CONSENT_DAYS = 365L;

    private final ProxyInputConsentRepository consentRepository;
    private final AuditLogService auditLogService;
    private final StorageService storageService;
    private final AccessControlService accessControlService;
    private final ProxyInputQueryService proxyInputQueryService;

    /**
     * 同意書を登録する。
     * <ul>
     *   <li>有効期間の上限（365日）チェック</li>
     *   <li>consent_method別の必須項目チェック</li>
     *   <li>重複チェック（同一組み合わせの有効同意書が既存 → 409）</li>
     *   <li>スコープ登録</li>
     *   <li>監査ログ記録（PROXY_CONSENT_CREATED）</li>
     * </ul>
     */
    // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentCreatedEventで分離予定
    @Transactional
    public ProxyInputConsentEntity createConsent(Long requestUserId, Long organizationId,
                                                  CreateProxyConsentCommand command) {
        // 権限チェック（DEPUTY_ADMIN以上）。組合IDはURLパス由来のためここで検証する。
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");

        // 有効期間の上限チェック（最長1年）
        long days = command.effectiveFrom().until(command.effectiveUntil(), java.time.temporal.ChronoUnit.DAYS);
        if (days > MAX_CONSENT_DAYS || days <= 0) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // consent_method別の必須項目チェック
        validateConsentMethodRequirements(command);

        // スコープが空でないことを確認
        if (command.scopes() == null || command.scopes().isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        // 重複チェック（Service層でのSELECT FOR UPDATE代替）
        boolean duplicateExists = consentRepository.existsActiveConsent(
                command.subjectUserId(), command.proxyUserId(),
                organizationId, command.effectiveFrom());
        if (duplicateExists) {
            // 409相当: COMMON_003（競合）を流用するか、独自エラーコードで対応
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }

        // 同意書エンティティ生成
        ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                command.subjectUserId(),
                command.proxyUserId(),
                organizationId,
                command.consentMethod(),
                command.scannedDocumentS3Key(),
                command.guardianCertificateS3Key(),
                command.witnessUserId(),
                command.effectiveFrom(),
                command.effectiveUntil()
        );

        // スコープを追加（OneToManyのcascade ALL で一括保存）
        List<ProxyInputConsentScopeEntity> scopeEntities = command.scopes().stream()
                .map(ProxyInputConsentScopeEntity::create)
                .toList();
        consent.getScopes().addAll(scopeEntities);

        ProxyInputConsentEntity saved = consentRepository.save(consent);

        // 監査ログ（非同期・fire-and-forget）
        auditLogService.record(
                ProxyAuditEventTypes.PROXY_CONSENT_CREATED,
                requestUserId,
                command.subjectUserId(),
                null,
                organizationId,
                null, null, null,
                buildConsentMetadata(saved)
        );

        log.info("代理入力同意書登録: id={}, subject={}, proxy={}, org={}",
                saved.getId(), command.subjectUserId(), command.proxyUserId(), organizationId);
        return saved;
    }

    /**
     * 同意書を承認する。
     * <ul>
     *   <li>自己承認禁止（requestUserId == consent.proxyUserId → 403）</li>
     *   <li>PROXY_CONSENT_APPROVE 権限チェック</li>
     *   <li>監査ログ記録（PROXY_CONSENT_APPROVED）</li>
     * </ul>
     */
    // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentApprovedEventで分離予定
    @Transactional
    public ProxyInputConsentEntity approveConsent(Long requestUserId, Long consentId) {
        ProxyInputConsentEntity consent = consentRepository.findByIdForUpdate(consentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));

        // 自己承認禁止
        if (consent.getProxyUserId().equals(requestUserId)) {
            log.warn("代理入力同意書の自己承認を試みた: consentId={}, userId={}", consentId, requestUserId);
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // PROXY_CONSENT_APPROVE 権限チェック
        if (!accessControlService.isSystemAdmin(requestUserId)) {
            accessControlService.checkPermission(
                    requestUserId, consent.getOrganizationId(), "ORGANIZATION", "PROXY_CONSENT_APPROVE");
        }

        if (consent.getApprovedAt() != null || consent.getRevokedAt() != null) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }

        consent.approve(requestUserId);
        ProxyInputConsentEntity saved = consentRepository.save(consent);
        saved.getScopes().size();

        auditLogService.record(
                ProxyAuditEventTypes.PROXY_CONSENT_APPROVED,
                requestUserId,
                consent.getSubjectUserId(),
                null,
                consent.getOrganizationId(),
                null, null, null,
                "{\"consentId\":" + consentId + "}"
        );

        log.info("代理入力同意書承認: id={}, approvedBy={}", consentId, requestUserId);
        return saved;
    }

    /**
     * 同意書を撤回する。
     * <ul>
     *   <li>権限チェック: 本人（subjectUserId）またはADMIN</li>
     *   <li>監査ログ記録（PROXY_CONSENT_REVOKED）</li>
     * </ul>
     */
    // TODO: proxyドメインとauthドメイン(AuditLogService)をまたいでいる。将来はProxyConsentRevokedEventで分離予定
    @Transactional
    public void revokeConsent(Long requestUserId, Long consentId, RevokeConsentCommand command) {
        ProxyInputConsentEntity consent = consentRepository.findByIdForUpdate(consentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));

        // 本人または組合ADMIN以上のみ撤回可
        boolean isSelf = consent.getSubjectUserId().equals(requestUserId);
        boolean isAdmin = accessControlService.isSystemAdmin(requestUserId)
                || accessControlService.isAdminOrAbove(
                requestUserId, consent.getOrganizationId(), "ORGANIZATION");

        if (!isSelf && !isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        boolean isPaperRevocation = command.revokeMethod()
                == ProxyInputConsentEntity.RevokeMethod.PAPER_BY_SUBJECT;
        if (isPaperRevocation && !isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        if (consent.getRevokedAt() != null) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }

        Long witnessedByUserId = isPaperRevocation
                ? requestUserId
                : command.revokeWitnessedByUserId();
        consent.revoke(command.revokeMethod(), witnessedByUserId, command.revokeReason());
        consentRepository.save(consent);

        auditLogService.record(
                ProxyAuditEventTypes.PROXY_CONSENT_REVOKED,
                requestUserId,
                consent.getSubjectUserId(),
                null,
                consent.getOrganizationId(),
                null, null, null,
                "{\"consentId\":" + consentId + ",\"revokeMethod\":\"" + command.revokeMethod() + "\"}"
        );

        log.info("代理入力同意書撤回: id={}, revokedBy={}, method={}", consentId, requestUserId, command.revokeMethod());
    }

    /**
     * 代理者が保有する有効同意書一覧を取得する（ProxyInputDeskView起動時）。
     */
    @Transactional(readOnly = true)
    public List<ProxyInputConsentEntity> getActiveConsentsForProxy(Long proxyUserId) {
        List<ProxyInputConsentEntity> consents = consentRepository.findActiveByProxyUserId(proxyUserId);
        consents.forEach(consent -> consent.getScopes().size());
        return consents;
    }

    /**
     * 組合単位の同意書一覧を取得する（ADMIN向け管理画面）。
     */
    public Page<ProxyInputConsentResponse> getConsentsByOrganization(
            Long requestUserId, Long organizationId, Pageable pageable) {
        checkOrganizationAdminOrSystem(requestUserId, organizationId);
        return proxyInputQueryService.getConsentsByOrganization(organizationId, pageable);
    }

    /**
     * 組合単位の代理入力履歴を取得する（ADMIN向け管理画面）。
     */
    public Page<ProxyInputRecordResponse> getRecordsByOrganization(
            Long requestUserId, Long organizationId, Long subjectUserId, Pageable pageable) {
        checkOrganizationAdminOrSystem(requestUserId, organizationId);
        return proxyInputQueryService.getRecordsByOrganization(
                organizationId, subjectUserId, pageable);
    }

    /**
     * 本人またはSYSTEM_ADMIN向けに、本人単位の代理入力履歴を取得する。
     */
    public Page<ProxyInputRecordResponse> getRecordsBySubject(
            Long requestUserId, Long subjectUserId, Pageable pageable) {
        if (!requestUserId.equals(subjectUserId)
                && !accessControlService.isSystemAdmin(requestUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return proxyInputQueryService.getRecordsBySubject(subjectUserId, pageable);
    }

    /**
     * 代理入力監査ログ一覧を取得する（ADMIN or 本人のみ）。
     * Phase 12-α では同意書一覧を返す（監査ログの詳細はPhase 13で追加）。
     *
     * <p>TODO Phase 13-α: 監査ログ詳細化時に findActiveBySubjectUserId → findBySubjectUserId（全件）に変更予定。
     * 現在は有効（未撤回）同意書のみ返す。撤回済みレコードは監査ログ（audit_logs）を参照すること。</p>
     */
    @Transactional(readOnly = true)
    public List<ProxyInputConsentEntity> getConsentsBySubject(Long requestUserId, Long subjectUserId) {
        if (!requestUserId.equals(subjectUserId)) {
            // 本人以外はADMIN権限チェック（組合は不明なため一旦SystemAdminチェック）
            if (!accessControlService.isSystemAdmin(requestUserId)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }
        return consentRepository.findActiveBySubjectUserId(subjectUserId);
    }

    /**
     * スキャン文書アップロード用のpresigned PUT URLを発行する（TTL 5分）。
     * S3キー形式: proxy-consents/{organizationId}/{year}/{month}/{UUID}.pdf
     */
    public PresignedUploadResult generateScanUploadUrl(Long requestUserId, Long organizationId) {
        // 組合への所属確認（DEPUTY_ADMIN以上が対象）
        accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");

        LocalDate now = LocalDate.now();
        String s3Key = String.format("proxy-consents/%d/%s/%s/%s.pdf",
                organizationId,
                now.format(DateTimeFormatter.ofPattern("yyyy")),
                now.format(DateTimeFormatter.ofPattern("MM")),
                UUID.randomUUID());

        return storageService.generateUploadUrl(s3Key, "application/pdf", SCAN_URL_TTL);
    }

    /**
     * スキャン文書ダウンロード用のpresigned GET URLを発行する（TTL 5分）。
     */
    @Transactional(readOnly = true)
    public String generateScanDownloadUrl(Long requestUserId, Long consentId) {
        ProxyInputConsentEntity consent = consentRepository.findById(consentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));

        // 本人または組合ADMIN以上のみ閲覧可
        boolean isSelf = consent.getSubjectUserId().equals(requestUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(
                requestUserId, consent.getOrganizationId(), "ORGANIZATION");

        if (!isSelf && !isAdmin) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        if (consent.getScannedDocumentS3Key() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        return storageService.generateDownloadUrl(consent.getScannedDocumentS3Key(), SCAN_URL_TTL);
    }

    // ─────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────

    private void validateConsentMethodRequirements(CreateProxyConsentCommand command) {
        switch (command.consentMethod()) {
            case GUARDIAN_BY_COURT -> {
                if (command.guardianCertificateS3Key() == null || command.guardianCertificateS3Key().isBlank()) {
                    throw new BusinessException(CommonErrorCode.COMMON_001);
                }
            }
            case WITNESSED_ORAL -> {
                if (command.witnessUserId() == null) {
                    throw new BusinessException(CommonErrorCode.COMMON_001);
                }
            }
            default -> { /* PAPER_SIGNED / DIGITAL_SIGNATURE は追加制約なし */ }
        }
    }

    private void checkOrganizationAdminOrSystem(Long requestUserId, Long organizationId) {
        if (!accessControlService.isSystemAdmin(requestUserId)) {
            accessControlService.checkAdminOrAbove(requestUserId, organizationId, "ORGANIZATION");
        }
    }

    private String buildConsentMetadata(ProxyInputConsentEntity consent) {
        return String.format(
                "{\"consentId\":%d,\"subjectUserId\":%d,\"proxyUserId\":%d,\"consentMethod\":\"%s\",\"effectiveFrom\":\"%s\",\"effectiveUntil\":\"%s\"}",
                consent.getId(), consent.getSubjectUserId(), consent.getProxyUserId(),
                consent.getConsentMethod(), consent.getEffectiveFrom(), consent.getEffectiveUntil());
    }
}
