package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptMapper;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.SealVariant;
import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.UpdateIssuerSettingsRequest;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 領収書発行者設定サービス。発行者設定のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptIssuerSettingsService {

    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final ReceiptMapper receiptMapper;
    private final AccessControlService accessControlService;

    /**
     * 発行者設定を取得する。
     * 認可: 指定スコープのメンバーのみ閲覧可能。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @return 発行者設定レスポンス
     */
    public IssuerSettingsResponse getSettings(ReceiptScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType.name());
        ReceiptIssuerSettingsEntity entity = findSettingsOrThrow(scopeType, scopeId);
        return receiptMapper.toIssuerSettingsResponse(entity);
    }

    /**
     * 発行者設定を作成または更新する（UPSERT）。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ変更可能。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @param request     更新リクエスト
     * @return 更新後の発行者設定レスポンス
     */
    @Transactional
    public IssuerSettingsResponse upsertSettings(ReceiptScopeType scopeType, Long scopeId,
                                                  Long actorUserId, UpdateIssuerSettingsRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        validateInvoiceRegistration(request);

        ReceiptIssuerSettingsEntity entity = issuerSettingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);

        SealVariant sealVariant = request.getDefaultSealVariant() != null
                ? SealVariant.valueOf(request.getDefaultSealVariant())
                : null;

        if (entity == null) {
            entity = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .issuerName(request.getIssuerName())
                    .postalCode(request.getPostalCode())
                    .address(request.getAddress())
                    .phone(request.getPhone())
                    .isQualifiedInvoicer(request.getIsQualifiedInvoicer())
                    .invoiceRegistrationNumber(request.getInvoiceRegistrationNumber())
                    .defaultSealUserId(request.getDefaultSealUserId())
                    .defaultSealVariant(sealVariant)
                    .receiptNoteTemplate(request.getReceiptNoteTemplate())
                    .receiptNumberPrefix(request.getReceiptNumberPrefix())
                    .fiscalYearStartMonth(request.getFiscalYearStartMonth() != null
                            ? request.getFiscalYearStartMonth() : 4)
                    .autoResetNumber(request.getAutoResetNumber() != null
                            ? request.getAutoResetNumber() : true)
                    .customFooter(request.getCustomFooter())
                    .build();
        } else {
            entity.update(
                    request.getIssuerName(),
                    request.getPostalCode(),
                    request.getAddress(),
                    request.getPhone(),
                    request.getIsQualifiedInvoicer(),
                    request.getInvoiceRegistrationNumber(),
                    request.getDefaultSealUserId(),
                    sealVariant,
                    request.getReceiptNoteTemplate(),
                    request.getReceiptNumberPrefix(),
                    request.getFiscalYearStartMonth() != null
                            ? request.getFiscalYearStartMonth() : entity.getFiscalYearStartMonth(),
                    request.getAutoResetNumber() != null
                            ? request.getAutoResetNumber() : entity.getAutoResetNumber(),
                    request.getCustomFooter()
            );
        }

        ReceiptIssuerSettingsEntity saved = issuerSettingsRepository.save(entity);
        log.info("発行者設定更新: scopeType={}, scopeId={}", scopeType, scopeId);
        return receiptMapper.toIssuerSettingsResponse(saved);
    }

    /**
     * ロゴ画像のストレージキーを更新する。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ変更可能。
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param actorUserId     操作者ユーザーID
     * @param logoStorageKey  S3 ストレージキー
     * @return 更新後の発行者設定レスポンス
     */
    @Transactional
    public IssuerSettingsResponse updateLogo(ReceiptScopeType scopeType, Long scopeId,
                                              Long actorUserId, String logoStorageKey) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        ReceiptIssuerSettingsEntity entity = findSettingsOrThrow(scopeType, scopeId);
        entity.updateLogoStorageKey(logoStorageKey);
        ReceiptIssuerSettingsEntity saved = issuerSettingsRepository.save(entity);
        log.info("ロゴ画像更新: scopeType={}, scopeId={}", scopeType, scopeId);
        return receiptMapper.toIssuerSettingsResponse(saved);
    }

    /**
     * ロゴ画像を削除する。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ削除可能。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public void deleteLogo(ReceiptScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        ReceiptIssuerSettingsEntity entity = findSettingsOrThrow(scopeType, scopeId);
        entity.updateLogoStorageKey(null);
        issuerSettingsRepository.save(entity);
        log.info("ロゴ画像削除: scopeType={}, scopeId={}", scopeType, scopeId);
    }

    /**
     * 発行者設定エンティティを取得する。存在しない場合は例外をスローする。
     */
    ReceiptIssuerSettingsEntity findSettingsOrThrow(ReceiptScopeType scopeType, Long scopeId) {
        return issuerSettingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_FOUND));
    }

    /**
     * インボイス登録番号のバリデーションを行う。
     */
    private void validateInvoiceRegistration(UpdateIssuerSettingsRequest request) {
        if (Boolean.TRUE.equals(request.getIsQualifiedInvoicer())) {
            if (request.getInvoiceRegistrationNumber() == null
                    || request.getInvoiceRegistrationNumber().isBlank()) {
                throw new BusinessException(ReceiptErrorCode.INVOICE_REGISTRATION_NUMBER_REQUIRED);
            }
            if (!request.getInvoiceRegistrationNumber().matches("^T\\d{13}$")) {
                throw new BusinessException(ReceiptErrorCode.INVALID_INVOICE_REGISTRATION_NUMBER);
            }
        }
    }
}
