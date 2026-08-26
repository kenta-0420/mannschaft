package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.disclosure.service.DisclosureExportStorageService.PresignedUrl;
import com.mannschaft.app.disclosure.service.DisclosureExportStorageService.StoredFile;
import com.mannschaft.app.disclosure.service.DisclosureExportValidationService.ReferenceCheckResult;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import com.mannschaft.app.seal.service.SealStampService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 重要事項説明書 出力サービス ファサード（F09.14 Phase 2-β-4 / Phase 4-A リファクタリング第 4 弾）。
 *
 * <p>設計書 §4 出力 API および §5.1 / §5.4 / §6.3 に対応するエントリポイント。
 * 大きく以下 3 つに責務を分割し、本クラスは横断調整役として委譲する:</p>
 *
 * <ul>
 *   <li>{@link DisclosureExportValidationService} — バージョン整合・form_schema 検証・引用パッケージ検証</li>
 *   <li>{@link DisclosureExportFileService} — PDF/Excel/Word 生成 + SHA-256 算出</li>
 *   <li>{@link DisclosureExportStorageService} — R2 アップロード + SharedFile DB 登録 + presigned URL 発行</li>
 * </ul>
 *
 * <p>本ファサードに残す責務:</p>
 * <ol>
 *   <li>{@code disclosure_exports} レコード作成 + ドラフト status 遷移</li>
 *   <li>履歴一覧 / 詳細取得 / 自動削除予定日延長</li>
 *   <li>F05.3 {@link SealStampService} 連携による改ざん検出多層化（§6.3）</li>
 * </ol>
 *
 * <p><strong>本フェーズではロジック変更なし。</strong>振る舞いは元の単一クラス実装と完全互換。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DisclosureExportService {

    /** 設計書 §3 で許容されるスコープ種別。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** 設計書 §6.6 / §5.7: 出力ファイル自動削除期限のデフォルト（90 日）。 */
    static final Duration EXPIRES_AFTER = Duration.ofDays(90);

    private final DisclosureExportRepository exportRepository;
    private final DisclosureFormDraftService draftService;
    private final DisclosureFormTemplateService templateService;
    private final DisclosureExportValidationService validationService;
    private final DisclosureExportFileService fileService;
    private final DisclosureExportStorageService storageService;
    private final OrganizationRepository organizationRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;

    /**
     * F05.3 押印検証サービス（Phase 4-A 改ざん検出多層化）。
     * <p>seal ドメインへのクロスドメイン参照。{@code circulation_document_id} を介した
     * 電子印鑑承認回覧（F05.2）で押印された出力ファイルについて、
     * {@code seal_stamp_logs} の証跡ログと照合し改ざんを検出する。</p>
     *
     * <p>TODO: 将来イベント駆動化候補。本来はイベント連携 (例: {@code DocumentDownloadRequestedEvent})
     * 経由で seal ドメインに検証を委譲し、結果を集約する形が望ましいが、
     * Phase 4-A ではモノリス前提で直接呼出を採用する。</p>
     */
    private final SealStampService sealStampService;

    /**
     * F05.3 押印ログリポジトリ（Phase 4-A 改ざん検出多層化）。
     * <p>TODO: 将来イベント駆動化候補。読込専用利用（{@code targetType=CIRCULATION} 検索のみ）。</p>
     */
    private final SealStampLogRepository sealStampLogRepository;

    // =========================================================================
    // 出力実行
    // =========================================================================

    /**
     * ドラフトを出力する。
     *
     * @param scopeId       組織 ID
     * @param draftId       対象ドラフト ID
     * @param format        出力形式（PDF/EXCEL）。Phase 1 では PDF のみが推奨
     * @param userId        操作者ユーザー ID
     * @param recipientNote 提出先メモ（任意）
     * @return 出力レスポンス（presigned ダウンロード URL 含む）
     */
    @Transactional
    public DisclosureExportResponse exportDraft(Long scopeId, Long draftId,
                                                DisclosureOutputFormat format, Long userId,
                                                String recipientNote, boolean allowPersonalInfo) {
        if (format == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // 1. ドラフトとテンプレート取得
        DisclosureFormDraftEntity draft = draftService.findDraftOrThrow(draftId);
        validationService.ensureScope(draft.getScopeType(), draft.getScopeId(), scopeId);
        // 認可根治戦役 Wave3-B4: Export は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);

        DisclosureFormTemplateEntity template = templateService.getEntityOrThrow(draft.getTemplateId());

        // 2. テンプレートバージョン整合性
        validationService.validateTemplateVersion(draft, template);

        // 3. form_schema 構造バリデーション
        JsonNode formSchema = validationService.parseAndValidateFormSchema(template);

        // 4. formData 取得 + 必須項目チェック
        JsonNode formData = validationService.parseFormData(draft);
        validationService.verifyRequiredFields(formSchema, formData);

        // 5. 引用パッケージ整合性検証 (PropertyWorkPackage)
        ReferenceCheckResult refCheck = validationService.verifyPackageReferences(
                formSchema, formData, draft.getScopeId());

        // 6. 出力データ生成
        OrganizationEntity organization = organizationRepository.findById(draft.getScopeId())
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
        DwellingUnitEntity dwellingUnit = (draft.getTargetDwellingUnitId() != null)
                ? dwellingUnitRepository.findById(draft.getTargetDwellingUnitId()).orElse(null)
                : null;
        String outputUserName = fileService.resolveUserName(userId);

        byte[] payload;
        String contentType;
        String extension;
        switch (format) {
            case PDF -> {
                payload = fileService.generatePdf(template, formSchema, formData, organization, outputUserName);
                contentType = "application/pdf";
                extension = "pdf";
            }
            case EXCEL -> {
                payload = fileService.generateExcel(template, formSchema, formData, organization, outputUserName);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            case WORD -> {
                payload = fileService.generateWord(draft, template);
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                extension = "docx";
            }
            default -> throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // 7. SHA-256
        String sha256 = fileService.sha256Hex(payload);

        // 8. R2 直接アップロード (private 保存) + SharedFile 登録
        StoredFile stored = storageService.storeExportedFile(
                scopeId, draft.getId(), payload, contentType, extension,
                organization, dwellingUnit, userId);
        SharedFileEntity savedFile = stored.sharedFile();

        // 9. disclosure_exports レコード作成
        LocalDateTime now = LocalDateTime.now();
        DisclosureExportEntity exportEntity = DisclosureExportEntity.builder()
                .scopeType(SCOPE_ORGANIZATION)
                .scopeId(scopeId)
                .draftId(draft.getId())
                .templateId(template.getId())
                .templateCodeSnapshot(template.getCode())
                .templateVersionSnapshot(template.getVersion())
                .outputFormat(format)
                .sharedFileId(savedFile.getId())
                .targetDwellingUnitId(draft.getTargetDwellingUnitId())
                .requesterUserId(userId)
                .recipientNote(recipientNote)
                .referencedPackageIds(serializeListOrNull(refCheck.includedIds()))
                .referencedDwellingUnitIds(
                        draft.getTargetDwellingUnitId() != null
                                ? serializeListOrNull(List.of(draft.getTargetDwellingUnitId()))
                                : null)
                .dataSnapshot(draft.getFormData())
                .outputSha256(sha256)
                .expiresAt(now.plus(EXPIRES_AFTER))
                .build();
        DisclosureExportEntity savedExport = exportRepository.save(exportEntity);

        // 10. ドラフト status を EXPORTED に遷移 + 引用パッケージ ID 記録
        draftService.recordReferencedPackages(draft, refCheck.includedIds());
        draftService.markExported(draft, userId);

        // 11. presigned ダウンロード URL 発行
        PresignedUrl presigned = storageService.generatePresignedUrl(stored.fileKey());

        log.info("重説書出力完了: scopeId={}, draftId={}, exportId={}, format={}, sha256={}",
                scopeId, draftId, savedExport.getId(), format, sha256);

        return new DisclosureExportResponse(
                savedExport.getId(),
                savedExport.getScopeType(),
                savedExport.getScopeId(),
                savedExport.getDraftId(),
                savedExport.getTemplateId(),
                savedExport.getTemplateCodeSnapshot(),
                savedExport.getTemplateVersionSnapshot(),
                savedExport.getOutputFormat(),
                savedExport.getSharedFileId(),
                savedExport.getTargetDwellingUnitId(),
                savedExport.getRequesterUserId(),
                savedExport.getRecipientNote(),
                refCheck.includedIds(),
                sha256,
                presigned.url(),
                presigned.expiresAt(),
                savedExport.getExpiresAt(),
                savedExport.getCreatedAt(),
                refCheck.warnings());
    }

    // =========================================================================
    // 履歴閲覧 / ダウンロード
    // =========================================================================

    /**
     * スコープ別の出力履歴一覧を取得する。
     */
    public Page<DisclosureExportResponse> listExports(Long scopeId, Long userId, Pageable pageable) {
        accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        Pageable safePageable = pageable != null ? pageable : PageRequest.of(0, 20);
        return exportRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        SCOPE_ORGANIZATION, scopeId, safePageable)
                .map(e -> DisclosureExportResponse.fromHistory(e, deserializeIds(e.getReferencedPackageIds())));
    }

    /**
     * 出力履歴詳細を取得する。
     */
    public DisclosureExportResponse getExport(Long scopeId, Long userId, Long exportId) {
        DisclosureExportEntity entity = findExportOrThrow(exportId);
        validationService.ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);
        accessControlService.checkMembership(userId, scopeId, SCOPE_ORGANIZATION);
        return DisclosureExportResponse.fromHistory(entity, deserializeIds(entity.getReferencedPackageIds()));
    }

    /**
     * presigned ダウンロード URL を発行する。設計書 §6.3 改ざん検出（Phase 4-A 多層化）に従い、
     * 以下の 2 層検証を実施する。両層が PASS した場合のみ presigned URL を返す。
     *
     * <ol>
     *   <li><strong>SHA-256 検証</strong>: R2 から再ダウンロードして {@code output_sha256} と比較。</li>
     *   <li><strong>F05.3 seal_stamp_logs 検証</strong>（Phase 4-A 追加）:
     *       {@code circulation_document_id} が NULL でない場合、当該回覧の押印ログ
     *       ({@code targetType=CIRCULATION}) を取得し、{@link SealStampService#verifyStamp}
     *       で印鑑ハッシュを照合する。1 件でもハッシュ不一致があれば NG。
     *       取消済（{@code is_revoked=true}）はスキップする（押印取消は改ざんではない）。</li>
     * </ol>
     *
     * <p><strong>AND 検証</strong>: 上記 2 層のいずれかが NG なら
     * {@link DisclosureErrorCode#DISCLOSURE_010}（HTTP 503 相当）を投げる。
     * {@code circulation_document_id} が NULL の場合（電子印鑑なし出力）は SHA-256 のみ検証。</p>
     */
    public DisclosureExportResponse generateDownloadUrl(Long scopeId, Long userId, Long exportId) {
        DisclosureExportEntity entity = findExportOrThrow(exportId);
        validationService.ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);
        // 認可根治戦役 Wave3-B4: 実ファイルダウンロードは Export と同格の ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);

        SharedFileEntity sharedFile = storageService.findSharedFileOrThrow(entity.getSharedFileId());

        // 改ざん検出（2 層）: SHA-256 + F05.3 seal_stamp_logs
        verifyOutputIntegrity(entity, sharedFile);

        PresignedUrl presigned = storageService.generatePresignedUrl(sharedFile.getFileKey());
        return new DisclosureExportResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getDraftId(),
                entity.getTemplateId(),
                entity.getTemplateCodeSnapshot(),
                entity.getTemplateVersionSnapshot(),
                entity.getOutputFormat(),
                entity.getSharedFileId(),
                entity.getTargetDwellingUnitId(),
                entity.getRequesterUserId(),
                entity.getRecipientNote(),
                deserializeIds(entity.getReferencedPackageIds()),
                entity.getOutputSha256(),
                presigned.url(),
                presigned.expiresAt(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                List.of());
    }

    /**
     * 出力ファイルの改ざん検出（2 層検証、設計書 §6.3 / Phase 4-A）。
     *
     * <p>第 1 層: R2 からダウンロードしたファイルの SHA-256 を再計算し、
     * {@code disclosure_exports.output_sha256} と比較する。</p>
     *
     * <p>第 2 層: {@code circulation_document_id} が NULL でない場合、
     * 当該回覧（{@code targetType=CIRCULATION}）に紐づく {@code seal_stamp_logs}
     * 全件について、{@link SealStampService#verifyStamp} で印鑑ハッシュ
     * （{@code seal_hash_at_stamp} と現在の {@code electronic_seals.seal_hash}）の
     * 一致を確認する。取消済（{@code is_revoked=true}）はスキップする。</p>
     *
     * <p>AND 検証: いずれかの層で NG が出れば {@link DisclosureErrorCode#DISCLOSURE_010}
     * （HTTP 503 相当）を投げる。</p>
     *
     * @param entity     対象の出力履歴エンティティ
     * @param sharedFile R2 上の実体ファイルメタ
     * @throws BusinessException {@link DisclosureErrorCode#DISCLOSURE_010} 改ざん検出
     */
    private void verifyOutputIntegrity(DisclosureExportEntity entity, SharedFileEntity sharedFile) {
        // 第 1 層: SHA-256 検証
        if (entity.getOutputSha256() != null) {
            try {
                byte[] data = storageService.downloadFromR2(sharedFile.getFileKey());
                String actualSha = fileService.sha256Hex(data);
                if (!actualSha.equals(entity.getOutputSha256())) {
                    log.error("重説書 SHA-256 不一致（改ざんの可能性）: exportId={}, expected={}, actual={}",
                            entity.getId(), entity.getOutputSha256(), actualSha);
                    throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("重説書ダウンロード時の SHA-256 検証失敗: exportId={}", entity.getId(), e);
                throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
            }
        }

        // 第 2 層: F05.3 seal_stamp_logs 検証（電子印鑑承認回覧あり時のみ）
        Long circulationId = entity.getCirculationDocumentId();
        if (circulationId == null) {
            return;
        }
        try {
            List<SealStampLogEntity> stampLogs = sealStampLogRepository
                    .findByTargetTypeAndTargetIdOrderByStampedAtDesc(
                            StampTargetType.CIRCULATION, circulationId);
            for (SealStampLogEntity stampLog : stampLogs) {
                // 取消済の押印は照合対象外（取消は改ざんではなく業務操作）
                if (stampLog.isAlreadyRevoked()) {
                    continue;
                }
                StampVerifyResponse verify = sealStampService.verifyStamp(stampLog.getId());
                if (!Boolean.TRUE.equals(verify.getIsValid())) {
                    log.error("重説書 seal_stamp_logs 検証 NG（改ざんの可能性）: exportId={}, stampLogId={}, message={}",
                            entity.getId(), stampLog.getId(), verify.getMessage());
                    throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("重説書ダウンロード時の seal_stamp_logs 検証失敗: exportId={}, circulationId={}",
                    entity.getId(), circulationId, e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    DisclosureExportEntity findExportOrThrow(Long exportId) {
        return exportRepository.findByIdAndDeletedAtIsNull(exportId)
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
    }

    /**
     * 出力履歴の自動削除予定日（{@code expires_at}）を延長する（F09.14 Phase 3-E、設計書 §5.7）。
     *
     * <p>制約:</p>
     * <ul>
     *   <li>{@code newExpiresAt} は <strong>現在時刻より未来</strong> であること</li>
     *   <li>{@code newExpiresAt} は <strong>本日から 7 年</strong> を超えないこと
     *       （F12.3 GDPR 整合: 最大保管期間）</li>
     * </ul>
     *
     * @param scopeId       組織 ID（テナント分離）
     * @param exportId      対象出力履歴 ID
     * @param newExpiresAt  新しい自動削除予定日時
     * @return 更新後の {@link DisclosureExportResponse}（download URL は含めない）
     * @throws BusinessException {@link DisclosureErrorCode#DISCLOSURE_001} (404) 出力履歴未発見、
     *                           {@link DisclosureErrorCode#DISCLOSURE_002} (403) スコープ不一致、
     *                           {@link DisclosureErrorCode#DISCLOSURE_011} (422) 延長範囲違反
     * @implNote 上限は <strong>本日 00:00 (Asia/Tokyo) 基準</strong>で
     *           {@code LocalDate.now().plusYears(7).atStartOfDay()}（境界値は含む）。
     */
    @Transactional
    public DisclosureExportResponse extendExpiry(Long scopeId, Long userId, Long exportId,
                                                 LocalDateTime newExpiresAt) {
        if (newExpiresAt == null) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_011);
        }
        LocalDateTime now = LocalDateTime.now();
        // 過去日時は禁止
        if (!newExpiresAt.isAfter(now)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_011);
        }
        // 本日から 7 年超は禁止（最大保管期間、F12.3 GDPR 整合）
        LocalDateTime maxAllowed = now.toLocalDate().plusYears(7).atStartOfDay();
        if (newExpiresAt.isAfter(maxAllowed)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_011);
        }

        DisclosureExportEntity entity = findExportOrThrow(exportId);
        validationService.ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);
        // 認可根治戦役 Wave3-B4: 期限延長は ADMIN のみ許可する（設計書 §5.7）
        accessControlService.checkAdminOrAbove(userId, scopeId, SCOPE_ORGANIZATION);

        entity.extendExpiresAt(newExpiresAt);
        DisclosureExportEntity saved = exportRepository.save(entity);

        log.info("重説書 expires_at 延長: exportId={}, newExpiresAt={}", exportId, newExpiresAt);

        return new DisclosureExportResponse(
                saved.getId(),
                saved.getScopeType(),
                saved.getScopeId(),
                saved.getDraftId(),
                saved.getTemplateId(),
                saved.getTemplateCodeSnapshot(),
                saved.getTemplateVersionSnapshot(),
                saved.getOutputFormat(),
                saved.getSharedFileId(),
                saved.getTargetDwellingUnitId(),
                saved.getRequesterUserId(),
                saved.getRecipientNote(),
                deserializeIds(saved.getReferencedPackageIds()),
                saved.getOutputSha256(),
                null,
                null,
                saved.getExpiresAt(),
                saved.getCreatedAt(),
                List.of());
    }

    // =========================================================================
    // 内部ヘルパー（参照シリアライズ）
    // =========================================================================

    private String serializeListOrNull(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            log.warn("referenced_package_ids シリアライズ失敗", e);
            return null;
        }
    }

    private List<Long> deserializeIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<Long> result = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item.canConvertToLong()) {
                    result.add(item.asLong());
                }
            }
            return result;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
