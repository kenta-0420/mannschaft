package com.mannschaft.app.receipt.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.ImageConverter;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptLogoUrlProvider;
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
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 領収書発行者設定サービス。発行者設定のCRUDを担当する。
 *
 * <p>更新は PATCH セマンティクス（F08.4 §9.2）である。</p>
 * <ul>
 *   <li>リクエストに現れなかった／{@code null} のフィールドは<b>無変更</b></li>
 *   <li>空文字は<b>明示的なクリア</b>で、DB には空文字（暗号化列なら空文字の暗号文）ではなく
 *       {@code NULL} を書き込む</li>
 *   <li>必須性・インボイスの整合といった不変条件は、リクエスト単体ではなく
 *       <b>既存エンティティとリクエストをマージした後の状態</b>に対して検証する</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptIssuerSettingsService {

    /** ロゴの長辺上限（px）。§3 のカラム定義・§5 の PDF ロゴ仕様と同一。 */
    private static final int LOGO_MAX_EDGE_PX = 200;

    /** ロゴの業務上限（1MB）。コンテナ側の枠はこれより広い 2MB を application.yml で明示している。 */
    private static final long LOGO_MAX_FILE_SIZE_BYTES = 1024L * 1024L;

    private static final String CONTENT_TYPE_PNG = "image/png";
    private static final String CONTENT_TYPE_JPEG = "image/jpeg";
    private static final Set<String> ALLOWED_LOGO_CONTENT_TYPES =
            Set.of(CONTENT_TYPE_PNG, CONTENT_TYPE_JPEG);

    private static final String INVOICE_REGISTRATION_NUMBER_PATTERN = "^T\\d{13}$";

    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final ReceiptMapper receiptMapper;
    private final AccessControlService accessControlService;
    private final ReceiptLogoUrlProvider logoUrlProvider;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    /**
     * 発行者設定を取得する。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ閲覧可能（F08.4 D-6）。
     * 住所・電話・登録番号・次番号を含むため、一般メンバーには開示しない。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @return 発行者設定レスポンス
     */
    public IssuerSettingsResponse getSettings(ReceiptScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        ReceiptIssuerSettingsEntity entity = findSettingsOrThrow(scopeType, scopeId);
        return toResponse(entity);
    }

    /**
     * 発行者設定を差分更新する（未作成なら新規作成する UPSERT）。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ変更可能。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @param request     更新リクエスト（差分）
     * @return 更新後の発行者設定レスポンス
     */
    @Transactional
    public IssuerSettingsResponse upsertSettings(ReceiptScopeType scopeType, Long scopeId,
                                                  Long actorUserId, UpdateIssuerSettingsRequest request) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());

        ReceiptIssuerSettingsEntity entity = issuerSettingsRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);

        // ── マージ（未送信/null は現在値を維持、空文字は NULL へクリア） ──────────────
        String issuerName = merge(request.getIssuerName(), entity == null ? null : entity.getIssuerName());
        String postalCode = merge(request.getPostalCode(), entity == null ? null : entity.getPostalCode());
        String address = merge(request.getAddress(), entity == null ? null : entity.getAddress());
        String phone = merge(request.getPhone(), entity == null ? null : entity.getPhone());
        Boolean isQualifiedInvoicer = request.getIsQualifiedInvoicer() != null
                ? request.getIsQualifiedInvoicer()
                : (entity == null ? null : entity.getIsQualifiedInvoicer());
        String invoiceRegistrationNumber = merge(request.getInvoiceRegistrationNumber(),
                entity == null ? null : entity.getInvoiceRegistrationNumber());
        Long defaultSealUserId = request.getDefaultSealUserId() != null
                ? request.getDefaultSealUserId()
                : (entity == null ? null : entity.getDefaultSealUserId());
        SealVariant defaultSealVariant = mergeSealVariant(request.getDefaultSealVariant(),
                entity == null ? null : entity.getDefaultSealVariant());
        String receiptNoteTemplate = merge(request.getReceiptNoteTemplate(),
                entity == null ? null : entity.getReceiptNoteTemplate());
        String receiptNumberPrefix = merge(request.getReceiptNumberPrefix(),
                entity == null ? null : entity.getReceiptNumberPrefix());
        Integer fiscalYearStartMonth = request.getFiscalYearStartMonth() != null
                ? request.getFiscalYearStartMonth()
                : (entity == null ? 4 : entity.getFiscalYearStartMonth());
        Boolean autoResetNumber = request.getAutoResetNumber() != null
                ? request.getAutoResetNumber()
                : (entity == null ? Boolean.TRUE : entity.getAutoResetNumber());
        String customFooter = merge(request.getCustomFooter(), entity == null ? null : entity.getCustomFooter());

        // ── マージ後の状態に対する不変条件検証 ────────────────────────────────
        validateMergedState(issuerName, isQualifiedInvoicer, invoiceRegistrationNumber);

        // 旧値は entity.update(...) の前に控える（差分更新では上書き後に読むと新値しか取れない）。
        Boolean previousIsQualifiedInvoicer = entity == null ? null : entity.getIsQualifiedInvoicer();
        String previousInvoiceRegistrationNumber = entity == null ? null : entity.getInvoiceRegistrationNumber();

        if (entity == null) {
            entity = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .issuerName(issuerName)
                    .postalCode(postalCode)
                    .address(address)
                    .phone(phone)
                    .isQualifiedInvoicer(isQualifiedInvoicer)
                    .invoiceRegistrationNumber(invoiceRegistrationNumber)
                    .defaultSealUserId(defaultSealUserId)
                    .defaultSealVariant(defaultSealVariant)
                    .receiptNoteTemplate(receiptNoteTemplate)
                    .receiptNumberPrefix(receiptNumberPrefix)
                    .fiscalYearStartMonth(fiscalYearStartMonth)
                    .autoResetNumber(autoResetNumber)
                    .customFooter(customFooter)
                    .build();
        } else {
            entity.update(issuerName, postalCode, address, phone,
                    isQualifiedInvoicer, invoiceRegistrationNumber,
                    defaultSealUserId, defaultSealVariant,
                    receiptNoteTemplate, receiptNumberPrefix,
                    fiscalYearStartMonth, autoResetNumber, customFooter);
        }

        ReceiptIssuerSettingsEntity saved = issuerSettingsRepository.save(entity);
        log.info("発行者設定更新: scopeType={}, scopeId={}", scopeType, scopeId);

        recordInvoiceAuditLog(scopeType, scopeId, actorUserId,
                previousIsQualifiedInvoicer, isQualifiedInvoicer,
                previousInvoiceRegistrationNumber, invoiceRegistrationNumber);

        return toResponse(saved);
    }

    /**
     * ロゴ画像をアップロードする（F08.4 D-3）。
     * 認可: 指定スコープの ADMIN/DEPUTY_ADMIN のみ変更可能。
     *
     * <p>受け取った画像は長辺 200px 以内へ縮小し、透過は白背景へ合成して
     * 再エンコードしたうえで UUID キーで保存する。差し替え時は旧オブジェクトを削除する。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープID
     * @param actorUserId 操作者ユーザーID
     * @param file        アップロードされたロゴ画像（PNG / JPEG・1MB 以下）
     * @return 更新後の発行者設定レスポンス
     */
    @Transactional
    public IssuerSettingsResponse uploadLogo(ReceiptScopeType scopeType, Long scopeId,
                                              Long actorUserId, MultipartFile file) {
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType.name());
        ReceiptIssuerSettingsEntity entity = findSettingsOrThrow(scopeType, scopeId);

        String contentType = validateLogoFile(file);
        boolean png = CONTENT_TYPE_PNG.equals(contentType);
        byte[] normalized = normalizeLogoImage(file, png);

        // スコープ種別は正規化後の enum 値から組む（生の文字列を連結しない・D-5(3)）。
        String extension = png ? "png" : "jpg";
        String storageKey = String.format("receipt-logos/%s/%d/%s.%s",
                scopeType.name(), scopeId, UUID.randomUUID(), extension);

        storageService.upload(storageKey, normalized, contentType);

        String previousKey = entity.getLogoStorageKey();
        entity.updateLogoStorageKey(storageKey);
        ReceiptIssuerSettingsEntity saved = issuerSettingsRepository.save(entity);

        if (previousKey != null && !previousKey.isBlank() && !previousKey.equals(storageKey)) {
            // UUID キーのため旧ファイルは自動では上書きされず残留する。明示的に削除する。
            storageService.delete(previousKey);
        }

        log.info("ロゴ画像更新: scopeType={}, scopeId={}", scopeType, scopeId);
        return toResponse(saved);
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
        String previousKey = entity.getLogoStorageKey();
        entity.updateLogoStorageKey(null);
        issuerSettingsRepository.save(entity);
        if (previousKey != null && !previousKey.isBlank()) {
            storageService.delete(previousKey);
        }
        log.info("ロゴ画像削除: scopeType={}, scopeId={}", scopeType, scopeId);
    }

    /**
     * 発行者設定エンティティを取得する。存在しない場合は例外をスローする。
     */
    ReceiptIssuerSettingsEntity findSettingsOrThrow(ReceiptScopeType scopeType, Long scopeId) {
        return issuerSettingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.ISSUER_SETTINGS_NOT_FOUND));
    }

    /** 署名付きロゴ URL を添えてレスポンスへ変換する。 */
    private IssuerSettingsResponse toResponse(ReceiptIssuerSettingsEntity entity) {
        return receiptMapper.toIssuerSettingsResponse(
                entity, logoUrlProvider.generateLogoUrl(entity.getLogoStorageKey()));
    }

    /**
     * 差分更新のマージ規則。
     *
     * @param requestValue リクエスト値（{@code null} なら無変更、空文字なら明示クリア）
     * @param currentValue 現在値
     * @return マージ後の値
     */
    private static String merge(String requestValue, String currentValue) {
        if (requestValue == null) {
            return currentValue;
        }
        // 暗号化列に空文字を書くと「空文字の暗号文」になり IS NULL 判定が壊れるため NULL に正規化する。
        return requestValue.isBlank() ? null : requestValue;
    }

    /** 印鑑バリアントのマージ（空文字＝明示クリア）。 */
    private static SealVariant mergeSealVariant(String requestValue, SealVariant currentValue) {
        if (requestValue == null) {
            return currentValue;
        }
        if (requestValue.isBlank()) {
            return null;
        }
        return SealVariant.valueOf(requestValue);
    }

    /**
     * マージ後の状態に対して不変条件を検証する（F08.4 §9.2）。
     *
     * <p>リクエスト単体を見る旧実装では、DB が適格 {@code TRUE} のまま登録番号だけを
     * クリアする差分更新が素通りし、「適格請求書の表記なのに登録番号が空」という
     * 法的に不正な領収書が発行できてしまう。</p>
     */
    private void validateMergedState(String issuerName, Boolean isQualifiedInvoicer,
                                     String invoiceRegistrationNumber) {
        // issuer_name は NOT NULL 列であり、マージ後に空になることは許容しない。
        if (issuerName == null || issuerName.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        if (isQualifiedInvoicer == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        if (Boolean.TRUE.equals(isQualifiedInvoicer)) {
            if (invoiceRegistrationNumber == null || invoiceRegistrationNumber.isBlank()) {
                throw new BusinessException(ReceiptErrorCode.INVOICE_REGISTRATION_NUMBER_REQUIRED);
            }
            if (!invoiceRegistrationNumber.matches(INVOICE_REGISTRATION_NUMBER_PATTERN)) {
                throw new BusinessException(ReceiptErrorCode.INVALID_INVOICE_REGISTRATION_NUMBER);
            }
        }
    }

    /**
     * 適格フラグ・登録番号が変化した場合に監査ログを記録する（AC-33）。
     * 登録番号は国税庁が公表する公開情報のため、平文で記録してよい。
     */
    private void recordInvoiceAuditLog(ReceiptScopeType scopeType, Long scopeId, Long actorUserId,
                                       Boolean oldQualified, Boolean newQualified,
                                       String oldRegistrationNumber, String newRegistrationNumber) {
        boolean qualifiedChanged = !Objects.equals(oldQualified, newQualified);
        boolean registrationChanged = !Objects.equals(oldRegistrationNumber, newRegistrationNumber);
        if (!qualifiedChanged && !registrationChanged) {
            return;
        }

        String metadata = String.format(
                "{\"scopeType\":\"%s\",\"scopeId\":%d,"
                        + "\"isQualifiedInvoicer\":{\"old\":%s,\"new\":%s},"
                        + "\"invoiceRegistrationNumber\":{\"old\":%s,\"new\":%s}}",
                scopeType.name(), scopeId,
                oldQualified, newQualified,
                jsonStringOrNull(oldRegistrationNumber), jsonStringOrNull(newRegistrationNumber));

        auditLogService.record(
                AuditEventType.RECEIPT_SETTINGS_UPDATED.name(),
                actorUserId, null,
                scopeType == ReceiptScopeType.TEAM ? scopeId : null,
                scopeType == ReceiptScopeType.ORGANIZATION ? scopeId : null,
                null, null, null,
                metadata);
    }

    private static String jsonStringOrNull(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * ロゴファイルの形式・サイズを検証し、正規化した Content-Type を返す。
     * 逸脱はすべて {@link ReceiptErrorCode#LOGO_UPLOAD_FAILED}（RECEIPT_020 / 400）とする。
     */
    private String validateLogoFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED);
        }
        if (file.getSize() > LOGO_MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_LOGO_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED);
        }
        return contentType;
    }

    /**
     * ロゴ画像をデコードし、白背景に合成しつつ長辺 200px 以内へ縮小して
     * 再エンコードする。デコードできないファイル（MIME 偽装）は RECEIPT_020 とする。
     */
    private byte[] normalizeLogoImage(MultipartFile file, boolean png) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            if (original == null) {
                // 拡張子・Content-Type が画像でも実体が画像でない（MIME 偽装）。
                throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED);
            }
            BufferedImage resized = ImageConverter.resizeOnWhiteBackground(original, LOGO_MAX_EDGE_PX);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(resized, png ? "png" : "jpeg", out)) {
                throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ReceiptErrorCode.LOGO_UPLOAD_FAILED, e);
        }
    }
}
