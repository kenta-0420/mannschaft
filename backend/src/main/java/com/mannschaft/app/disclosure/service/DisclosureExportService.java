package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.excel.ExcelGeneratorService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.dto.DisclosureExportResponse;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import com.mannschaft.app.disclosure.util.DisclosureFileNameBuilder;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import com.mannschaft.app.seal.service.SealStampService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 重要事項説明書 出力サービス（F09.14 Phase 2-β-4）。
 *
 * <p>設計書 §4 出力 API および §5.1 / §5.4 / §6.3 に対応。
 * 主な責務:</p>
 * <ol>
 *   <li>ドラフトと様式テンプレートのバージョン整合性確認 (DISCLOSURE_006)</li>
 *   <li>form_schema バリデーション + 必須項目チェック (DISCLOSURE_004 / DISCLOSURE_007)</li>
 *   <li>引用パッケージの整合性検証 (DISCLOSURE_008、is_disclosable=false 等は警告)</li>
 *   <li>PDF/Excel 生成 (PdfGeneratorService / ExcelGeneratorService)</li>
 *   <li>SHA-256 算出 + R2 直接保存 + SharedFile DB 登録</li>
 *   <li>disclosure_exports レコード作成 + ドラフト status を EXPORTED へ遷移</li>
 *   <li>presigned ダウンロード URL 発行 (15 分有効)</li>
 * </ol>
 *
 * <p>本フェーズでは Excel テンプレートの xlsx ファイル本体は未準備のため、
 * ファイル不在時は ExcelGeneratorService.generateMultiSheetExcel() でフォールバック
 * 出力する（FIXME: Phase 2-β-5 後の標準書式 xlsx 整備で正式化）。</p>
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

    /** F13 Phase 5-a 命名規則: presigned URL の有効期限（共通 15 分）。 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /** 重説書出力ファイルの専用フォルダ名。 */
    private static final String EXPORT_FOLDER_NAME = "disclosure-exports";

    /** PDF 共通テンプレートのパス。 */
    private static final String PDF_TEMPLATE_COMMON = "pdf/disclosure/common";

    /** Excel テンプレートのリソース配置プレフィックス。 */
    private static final String EXCEL_TEMPLATE_PREFIX = "excel/disclosure/";

    private final DisclosureExportRepository exportRepository;
    private final DisclosureFormDraftService draftService;
    private final DisclosureFormTemplateService templateService;
    private final DisclosureFormTemplateValidator templateValidator;
    private final PdfGeneratorService pdfGeneratorService;
    private final ExcelGeneratorService excelGeneratorService;
    private final WordGeneratorService wordGeneratorService;
    private final R2StorageService r2StorageService;
    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository sharedFileRepository;
    private final SharedFileVersionRepository sharedFileVersionRepository;
    private final OrganizationRepository organizationRepository;
    private final DwellingUnitRepository dwellingUnitRepository;
    private final PropertyWorkPackageRepository propertyWorkPackageRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

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
        ensureScope(draft.getScopeType(), draft.getScopeId(), scopeId);

        DisclosureFormTemplateEntity template = templateService.getEntityOrThrow(draft.getTemplateId());

        // 2. テンプレートバージョン整合性
        if (!template.getVersion().equals(draft.getTemplateVersionSnapshot())) {
            log.warn("テンプレートバージョン差異: draftId={}, snapshot={}, latest={}",
                    draftId, draft.getTemplateVersionSnapshot(), template.getVersion());
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_006);
        }
        // effective_until 経過チェック
        if (template.getEffectiveUntil() != null
                && template.getEffectiveUntil().isBefore(LocalDate.now())) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_006);
        }

        // 3. form_schema 構造バリデーション
        JsonNode formSchema = parseJsonOrThrow(template.getFormSchema());
        templateValidator.validate(formSchema);

        // 4. formData 取得 + 必須項目チェック
        JsonNode formData = parseJsonOrEmpty(draft.getFormData());
        verifyRequiredFields(formSchema, formData);

        // 5. 引用パッケージ整合性検証 (PropertyWorkPackage)
        ReferenceCheckResult refCheck = verifyPackageReferences(formSchema, formData, draft.getScopeId());

        // 6. 出力データ生成
        OrganizationEntity organization = organizationRepository.findById(draft.getScopeId())
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));
        DwellingUnitEntity dwellingUnit = (draft.getTargetDwellingUnitId() != null)
                ? dwellingUnitRepository.findById(draft.getTargetDwellingUnitId()).orElse(null)
                : null;
        String outputUserName = resolveUserName(userId);

        byte[] payload;
        String contentType;
        String extension;
        switch (format) {
            case PDF -> {
                payload = generatePdf(template, formSchema, formData, organization, outputUserName);
                contentType = "application/pdf";
                extension = "pdf";
            }
            case EXCEL -> {
                payload = generateExcel(template, formSchema, formData, organization, outputUserName);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            case WORD -> {
                payload = generateWord(draft, template);
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                extension = "docx";
            }
            default -> throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }

        // 7. SHA-256
        String sha256 = sha256Hex(payload);

        // 8. R2 直接アップロード (private 保存) + SharedFile 登録
        String fileName = buildFileName(extension, organization, dwellingUnit);
        String fileKey = buildFileKey(scopeId, extension);
        try {
            r2StorageService.upload(fileKey, payload, contentType);
        } catch (BusinessException e) {
            log.error("重説書 R2 アップロード失敗: scopeId={}, draftId={}", scopeId, draftId, e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }

        SharedFolderEntity folder = ensureExportFolder(scopeId);
        SharedFileEntity sharedFile = SharedFileEntity.builder()
                .folderId(folder.getId())
                .name(fileName)
                .fileKey(fileKey)
                .fileSize((long) payload.length)
                .contentType(contentType)
                .description("F09.14 重説書出力 (draftId=" + draft.getId() + ")")
                .createdBy(userId)
                .build();
        SharedFileEntity savedFile = sharedFileRepository.save(sharedFile);

        SharedFileVersionEntity version = SharedFileVersionEntity.builder()
                .fileId(savedFile.getId())
                .versionNumber(1)
                .fileKey(fileKey)
                .fileSize((long) payload.length)
                .contentType(contentType)
                .uploadedBy(userId)
                .comment("初回アップロード（F09.14 重説書出力）")
                .build();
        sharedFileVersionRepository.save(version);

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
        String downloadUrl = r2StorageService.generateDownloadUrl(fileKey, PRESIGN_TTL);
        LocalDateTime urlExpiresAt = now.plus(PRESIGN_TTL);

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
                downloadUrl,
                urlExpiresAt,
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
    public Page<DisclosureExportResponse> listExports(Long scopeId, Pageable pageable) {
        Pageable safePageable = pageable != null ? pageable : PageRequest.of(0, 20);
        return exportRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        SCOPE_ORGANIZATION, scopeId, safePageable)
                .map(e -> DisclosureExportResponse.fromHistory(e, deserializeIds(e.getReferencedPackageIds())));
    }

    /**
     * 出力履歴詳細を取得する。
     */
    public DisclosureExportResponse getExport(Long scopeId, Long exportId) {
        DisclosureExportEntity entity = findExportOrThrow(exportId);
        ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);
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
    public DisclosureExportResponse generateDownloadUrl(Long scopeId, Long exportId) {
        DisclosureExportEntity entity = findExportOrThrow(exportId);
        ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);

        SharedFileEntity sharedFile = sharedFileRepository.findById(entity.getSharedFileId())
                .orElseThrow(() -> new BusinessException(DisclosureErrorCode.DISCLOSURE_001));

        // 改ざん検出（2 層）: SHA-256 + F05.3 seal_stamp_logs
        verifyOutputIntegrity(entity, sharedFile);

        String url = r2StorageService.generateDownloadUrl(sharedFile.getFileKey(), PRESIGN_TTL);
        LocalDateTime expiresAt = LocalDateTime.now().plus(PRESIGN_TTL);
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
                url,
                expiresAt,
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
                byte[] data = r2StorageService.download(sharedFile.getFileKey());
                String actualSha = sha256Hex(data);
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
    public DisclosureExportResponse extendExpiry(Long scopeId, Long exportId,
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
        ensureScope(entity.getScopeType(), entity.getScopeId(), scopeId);

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
    // 内部ヘルパー
    // =========================================================================

    private void ensureScope(String entityScopeType, Long entityScopeId, Long expectedScopeId) {
        if (!SCOPE_ORGANIZATION.equals(entityScopeType) || !entityScopeId.equals(expectedScopeId)) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_002);
        }
    }

    /** form_schema を走査し、required: true のフィールド未入力があれば DISCLOSURE_007。 */
    private void verifyRequiredFields(JsonNode formSchema, JsonNode formData) {
        List<ErrorResponse.FieldError> missing = new ArrayList<>();
        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            return;
        }
        for (JsonNode section : sections) {
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode requiredNode = field.get("required");
                if (requiredNode == null || !requiredNode.asBoolean(false)) {
                    continue;
                }
                JsonNode idNode = field.get("id");
                if (idNode == null || !idNode.isTextual()) {
                    continue;
                }
                String fieldId = idNode.asText();
                JsonNode value = formData.get(fieldId);
                if (isEmptyValue(value)) {
                    String label = field.get("label") != null
                            ? field.get("label").asText() : fieldId;
                    missing.add(new ErrorResponse.FieldError(fieldId,
                            "必須項目「" + label + "」が未入力です"));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_007, missing);
        }
    }

    private boolean isEmptyValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isArray()) {
            return node.size() == 0;
        }
        if (node.isObject()) {
            return node.size() == 0;
        }
        return false;
    }

    /**
     * formData の AUTO_TABLE フィールドのうち {@code property_history.packages} を引用するものを走査し、
     * 引用された package id 群について、論理削除済 / is_disclosable=false を除外して警告する。
     *
     * <p>入力 formData は autoFill 済みの状態と仮定する。除外件数が大きい場合は呼び出し側で
     * UI 警告を表示する想定。本フェーズではログ + warnings 配列で返却する。</p>
     */
    private ReferenceCheckResult verifyPackageReferences(JsonNode formSchema, JsonNode formData, Long scopeId) {
        List<Long> includedIds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            return new ReferenceCheckResult(List.of(), List.of());
        }
        for (JsonNode section : sections) {
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode autoFillFrom = field.get("autoFillFrom");
                if (autoFillFrom == null
                        || !"property_history.packages".equals(autoFillFrom.asText(""))) {
                    continue;
                }
                String fieldId = field.get("id") != null ? field.get("id").asText() : null;
                if (fieldId == null) {
                    continue;
                }
                JsonNode rows = formData.get(fieldId);
                if (rows == null || !rows.isArray()) {
                    continue;
                }
                for (JsonNode row : rows) {
                    JsonNode idNode = row.get("id");
                    if (idNode == null || !idNode.canConvertToLong()) {
                        continue;
                    }
                    long pkgId = idNode.asLong();
                    var entityOpt = propertyWorkPackageRepository.findByIdAndDeletedAtIsNull(pkgId);
                    if (entityOpt.isEmpty()) {
                        warnings.add("パッケージ id=" + pkgId + " は削除済のため除外しました");
                        continue;
                    }
                    PropertyWorkPackageEntity entity = entityOpt.get();
                    if (Boolean.FALSE.equals(entity.getIsDisclosable())) {
                        warnings.add("「" + entity.getTitle() + "」は非開示設定のため除外しました");
                        continue;
                    }
                    if (!scopeId.equals(entity.getScopeId())) {
                        // クロステナント遮断（autoFill では発生しないはずだが防衛的）
                        warnings.add("パッケージ id=" + pkgId + " はスコープ外のため除外しました");
                        continue;
                    }
                    includedIds.add(pkgId);
                }
            }
        }
        return new ReferenceCheckResult(includedIds, warnings);
    }

    private byte[] generatePdf(DisclosureFormTemplateEntity template,
                               JsonNode formSchema, JsonNode formData,
                               OrganizationEntity organization,
                               String outputUserName) {
        try {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("templateName", template.getName());
            variables.put("templateCode", template.getCode());
            variables.put("prefectureCode", template.getPrefectureCode());
            variables.put("effectiveDate", template.getEffectiveFrom());
            variables.put("outputDate", LocalDateTime.now());
            variables.put("outputUserName", outputUserName);
            variables.put("organizationName", organization != null ? organization.getName() : null);
            // Thymeleaf テンプレ (templates/pdf/disclosure/common.html) は Map で sections/fields を
            // th:each するため、JsonNode のままだと Iterable とみなされず PDF_001 を投げる。
            variables.put("formSchema", jsonNodeToMap(formSchema));
            variables.put("formData", jsonNodeToMap(formData));
            String templatePath = template.getPdfTemplatePath() != null
                    ? template.getPdfTemplatePath() : PDF_TEMPLATE_COMMON;
            return pdfGeneratorService.generateFromTemplate(templatePath, variables);
        } catch (BusinessException e) {
            log.error("重説書 PDF 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * Excel 出力。
     *
     * <p>本フェーズでは標準書式 xlsx ファイルが未準備のため、テンプレ存在時は fillTemplate、
     * 未存在時は generateMultiSheetExcel によるフォールバック出力で対応する。</p>
     */
    private byte[] generateExcel(DisclosureFormTemplateEntity template,
                                 JsonNode formSchema, JsonNode formData,
                                 OrganizationEntity organization,
                                 String outputUserName) {
        String templateKey = template.getExcelTemplateKey();
        if (templateKey == null || templateKey.isBlank()) {
            templateKey = EXCEL_TEMPLATE_PREFIX + template.getCode() + ".xlsx";
        }
        try {
            ClassPathResource resource = new ClassPathResource(templateKey);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    Map<String, Object> data = buildExcelTemplateData(formSchema, formData,
                            organization, outputUserName, template);
                    return excelGeneratorService.fillTemplate(is, data);
                }
            }
            // フォールバック: 共通の表形式 Excel を生成（FIXME: Phase 2-β-5 で xlsx テンプレ整備後に削除）
            log.warn("Excel テンプレ未配置のためフォールバック出力: key={}, templateId={}",
                    templateKey, template.getId());
            return excelGeneratorService.generateMultiSheetExcel(buildFallbackExcelSheets(
                    template, formSchema, formData, organization, outputUserName));
        } catch (IOException | RuntimeException e) {
            log.error("重説書 Excel 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * Word 出力（F09.14 Phase 3-B）。
     *
     * <p>WordGeneratorService に委譲し、テンプレート (docx/disclosure/{templateCode}.docx)
     * 配下の docx を読み込んで {@code ${key}} プレースホルダーを置換する。テンプレート
     * 未配置の場合は WordGeneratorService 側のフォールバックで最低限の docx を生成する。</p>
     */
    private byte[] generateWord(DisclosureFormDraftEntity draft,
                                DisclosureFormTemplateEntity template) {
        try {
            return wordGeneratorService.generate(draft, template);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("重説書 Word 生成失敗: templateId={}", template.getId(), e);
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    private Map<String, Object> buildExcelTemplateData(JsonNode formSchema, JsonNode formData,
                                                       OrganizationEntity organization,
                                                       String outputUserName,
                                                       DisclosureFormTemplateEntity template) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateName", template.getName());
        data.put("templateCode", template.getCode());
        data.put("outputDate", LocalDateTime.now());
        data.put("outputUserName", outputUserName);
        data.put("organizationName", organization != null ? organization.getName() : "");
        if (formData != null && formData.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = formData.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    data.put(e.getKey(), "");
                } else if (v.isValueNode()) {
                    data.put(e.getKey(), v.asText());
                } else {
                    data.put(e.getKey(), v.toString());
                }
            }
        }
        return data;
    }

    private List<ExcelGeneratorService.ExcelSheet> buildFallbackExcelSheets(
            DisclosureFormTemplateEntity template, JsonNode formSchema, JsonNode formData,
            OrganizationEntity organization, String outputUserName) {
        List<ExcelGeneratorService.ExcelSheet> sheets = new ArrayList<>();

        // シート1: 注意書き
        List<List<Object>> noticeRows = new ArrayList<>();
        noticeRows.add(List.of("様式", template.getName() != null ? template.getName() : ""));
        noticeRows.add(List.of("様式コード", template.getCode() != null ? template.getCode() : ""));
        noticeRows.add(List.of("出力日時", LocalDateTime.now()));
        noticeRows.add(List.of("出力者", outputUserName != null ? outputUserName : ""));
        noticeRows.add(List.of("物件名", organization != null && organization.getName() != null
                ? organization.getName() : ""));
        noticeRows.add(List.of("注意",
                "本書類は管理組合が物件調査に応じて作成した参考情報です。実際の取引では宅地建物取引士による説明・記名押印が必須です。"));
        sheets.add(new ExcelGeneratorService.ExcelSheet(
                "注意事項", List.of("項目", "値"), noticeRows));

        // シート2: フォームデータ
        List<List<Object>> dataRows = new ArrayList<>();
        if (formSchema != null && formSchema.isObject()) {
            JsonNode sections = formSchema.get("sections");
            if (sections != null && sections.isArray()) {
                for (JsonNode section : sections) {
                    JsonNode fields = section.get("fields");
                    if (fields == null || !fields.isArray()) {
                        continue;
                    }
                    for (JsonNode field : fields) {
                        if (field.get("type") != null
                                && "AUTO_TABLE".equals(field.get("type").asText())) {
                            continue; // AUTO_TABLE は別シート化が望ましいが、本フォールバックでは省略
                        }
                        String fieldId = field.get("id") != null ? field.get("id").asText() : "";
                        String label = field.get("label") != null ? field.get("label").asText() : fieldId;
                        JsonNode value = formData != null ? formData.get(fieldId) : null;
                        String text = (value == null || value.isNull()) ? ""
                                : (value.isValueNode() ? value.asText() : value.toString());
                        dataRows.add(List.of(
                                section.get("title") != null ? section.get("title").asText() : "",
                                label, text));
                    }
                }
            }
        }
        sheets.add(new ExcelGeneratorService.ExcelSheet(
                "重要事項説明書", List.of("セクション", "項目", "値"), dataRows));

        return sheets;
    }

    /** Thymeleaf 用に JsonNode を Map<String, Object> に変換（ヌル安全 + ネストオブジェクトはそのまま）。 */
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            map.put(e.getKey(), jsonNodeToObject(v));
        }
        return map;
    }

    private Object jsonNodeToObject(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isInt() || v.isLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : v) {
                list.add(jsonNodeToObject(item));
            }
            return list;
        }
        if (v.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            v.fields().forEachRemaining(e -> map.put(e.getKey(), jsonNodeToObject(e.getValue())));
            return map;
        }
        return v.toString();
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は JDK 標準のため通常発生しない
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_010, e);
        }
    }

    /**
     * R2 オブジェクトキーを生成する（F13 Phase 5-a 命名規則: files/{scopeType}/{scopeId}/{uuid}.{ext}）。
     */
    private String buildFileKey(Long scopeId, String extension) {
        return "files/" + FileScopeType.ORGANIZATION.name() + "/" + scopeId
                + "/" + UUID.randomUUID() + "." + extension;
    }

    private String buildFileName(String extension, OrganizationEntity organization,
                                 DwellingUnitEntity dwellingUnit) {
        DisclosureFileNameBuilder builder = DisclosureFileNameBuilder.of(extension)
                .date(LocalDate.now());
        if (organization != null) {
            builder.propertyName(organization.getName());
        }
        if (dwellingUnit != null) {
            builder.unitNumber(dwellingUnit.getUnitNumber());
        }
        return builder.build();
    }

    /** disclosure-exports 用フォルダを ensure する（存在しなければ作成）。 */
    private SharedFolderEntity ensureExportFolder(Long scopeId) {
        // 組織直下のルートフォルダから検索
        List<SharedFolderEntity> roots = folderRepository
                .findByOrganizationIdAndParentIdIsNullOrderByNameAsc(scopeId);
        for (SharedFolderEntity f : roots) {
            if (EXPORT_FOLDER_NAME.equals(f.getName())) {
                return f;
            }
        }
        SharedFolderEntity folder = SharedFolderEntity.builder()
                .scopeType(FileScopeType.ORGANIZATION)
                .organizationId(scopeId)
                .name(EXPORT_FOLDER_NAME)
                .description("F09.14 重説書出力ファイル（自動生成）")
                .createdBy(null)
                .build();
        return folderRepository.save(folder);
    }

    private String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(this::formatUserName)
                .orElse(null);
    }

    private String formatUserName(UserEntity user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        StringBuilder sb = new StringBuilder();
        if (user.getLastName() != null) {
            sb.append(user.getLastName());
        }
        if (user.getFirstName() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(user.getFirstName());
        }
        return sb.length() > 0 ? sb.toString() : user.getEmail();
    }

    private JsonNode parseJsonOrThrow(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004);
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BusinessException(DisclosureErrorCode.DISCLOSURE_004, e);
        }
    }

    private JsonNode parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

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

    /** PropertyWorkPackage 引用整合性検証の結果。 */
    private record ReferenceCheckResult(List<Long> includedIds, List<String> warnings) {
    }
}
