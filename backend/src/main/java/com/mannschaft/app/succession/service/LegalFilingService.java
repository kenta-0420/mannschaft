package com.mannschaft.app.succession.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
import com.mannschaft.app.succession.repository.DelinquencyEscalationRepository;
import com.mannschaft.app.succession.repository.LegalFilingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 法的手続き準備サービス（F09.15 S6-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.8〜§5.13
 *
 * <p>主な責務:
 * <ul>
 *   <li>UC-C1: 法的手続きレコードを起票し、申立書テンプレート PDF を生成・S3 アップロードする</li>
 *   <li>UC-C2: 区分所有法 8 条 証拠パッケージ（ZIP）を生成して S3 にアップロードする</li>
 *   <li>証拠 ZIP の Pre-signed ダウンロード URL を発行する（有効期間 1h）</li>
 * </ul>
 *
 * <p>テナント分離: {@link LegalFilingRepository} は {@code AbstractTenantAwareRepository}
 * を継承しており、{@code organization_id} 必須でフィルタする。
 *
 * <p>証拠 ZIP 構成:
 * <ol>
 *   <li>01_template.pdf — 申立書テンプレート（{@link #createLegalFiling} で生成済み）</li>
 *   <li>02_evidence-timeline.pdf — 5 段階エスカレーションタイムライン PDF</li>
 *   <li>03_art8-cover.pdf — 区分所有法 8 条 証拠パッケージ表紙 PDF</li>
 * </ol>
 *
 * <p>TODO: v2 以降に PaymentService.exportDelinquencyHistoryPdf() を呼び出して
 * F08.2 滞納履歴 PDF を ZIP に同梱する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LegalFilingService {

    private final LegalFilingRepository legalFilingRepository;
    private final DelinquencyEscalationRepository escalationRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    /** 証拠 ZIP ダウンロード URL の有効期間。 */
    private static final Duration EVIDENCE_URL_TTL = Duration.ofHours(1);

    // ─────────────────────────────────────────────
    // UC-C1: 法的手続きレコード起票
    // ─────────────────────────────────────────────

    /**
     * 法的手続きレコードを起票し、申立書テンプレート PDF を生成・S3 アップロードする（UC-C1）。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>filingType バリデーション</li>
     *   <li>エスカレーション情報取得（PDF に埋め込む。存在しない場合は null 許容）</li>
     *   <li>テンプレート PDF 生成</li>
     *   <li>エンティティを先行 save して UUID を確定</li>
     *   <li>S3 キー確定・アップロード</li>
     *   <li>S3 キーをエンティティに反映して再 save</li>
     *   <li>監査ログ記録</li>
     * </ol>
     *
     * @param organizationId     テナント ID
     * @param residentRegistryId 居住者台帳 ID
     * @param dwellingUnitId     居室 ID
     * @param filingType         "ABSENTEE_PROPERTY_MANAGER" または "INHERITANCE_LIQUIDATOR"
     * @param note               備考（任意・null 可）
     * @param requestingUserId   起票ユーザー ID（監査ログ用）
     * @return 保存済み法的手続きエンティティ
     * @throws BusinessException INVALID_COVENANT_TYPE: 不正な filingType
     */
    @Transactional
    public LegalFilingEntity createLegalFiling(
            Long organizationId, Long residentRegistryId, Long dwellingUnitId,
            String filingType, String note, Long requestingUserId) {

        // 1. filingType バリデーション
        if (!"ABSENTEE_PROPERTY_MANAGER".equals(filingType)
                && !"INHERITANCE_LIQUIDATOR".equals(filingType)) {
            throw new BusinessException(SuccessionErrorCode.INVALID_COVENANT_TYPE);
        }

        // 2. エスカレーション情報を取得（PDF に埋め込む。存在しない場合は null 許容）
        DelinquencyEscalationEntity escalation = escalationRepository
                .findByResidentRegistryIdAndDeletedAtIsNull(residentRegistryId)
                .orElse(null);

        // 3. テンプレート PDF 生成
        String templateName = "ABSENTEE_PROPERTY_MANAGER".equals(filingType)
                ? "pdf/legal-filing-absentee-property-manager"
                : "pdf/legal-filing-inheritance-liquidator";
        Map<String, Object> vars = buildFilingTemplateVars(residentRegistryId, dwellingUnitId, escalation);
        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate(templateName, vars);

        // 4. エンティティを先行 save して UUID を確定
        // UUID は save 後に確定するため、entity を先に save してから S3 キーをセットする 2-step 方式を採用
        LegalFilingEntity entity = LegalFilingEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(dwellingUnitId)
                .residentRegistryId(residentRegistryId)
                .filingType(filingType)
                .note(note)
                .build();
        entity = legalFilingRepository.save(entity);

        // 5. S3 キー確定・アップロード
        String s3Key = buildTemplatePdfS3Key(organizationId, entity.getId());
        storageService.upload(s3Key, pdfBytes, "application/pdf");

        // 6. S3 キーをエンティティに反映して再 save
        entity.setTemplatePdfS3Key(s3Key);
        entity = legalFilingRepository.save(entity);

        // 7. 監査ログ
        String metadata = String.format(
                "{\"legalFilingId\":\"%s\",\"filingType\":\"%s\",\"residentRegistryId\":%d}",
                entity.getId(), filingType, residentRegistryId);
        auditLogService.record(AuditEventType.LEGAL_FILING_CREATED.name(),
                requestingUserId, null, null, organizationId, null, null, null, metadata);

        log.info("法的手続き起票完了: legalFilingId={}, filingType={}, organizationId={}",
                entity.getId(), filingType, organizationId);

        return entity;
    }

    // ─────────────────────────────────────────────
    // UC-C2: 証拠パッケージ（ZIP）生成
    // ─────────────────────────────────────────────

    /**
     * 区分所有法 8 条 証拠パッケージ（ZIP）を生成して S3 にアップロードする（UC-C2）。
     *
     * <p>ZIP 構成:
     * <ol>
     *   <li>01_template.pdf — 申立書テンプレート（createLegalFiling で生成済み）</li>
     *   <li>02_evidence-timeline.pdf — 5 段階エスカレーションタイムライン PDF</li>
     *   <li>03_art8-cover.pdf — 区分所有法 8 条 証拠パッケージ表紙 PDF</li>
     * </ol>
     *
     * <p>F08.2 滞納履歴 PDF の自動取得は v1 スコープ外。
     * TODO: v2 以降に PaymentService.exportDelinquencyHistoryPdf() を呼び出して同梱する。
     *
     * @param legalFilingId    法的手続きレコード ID
     * @param organizationId   テナント ID
     * @param requestingUserId 実行ユーザー ID（監査ログ用）
     * @return 証拠パッケージ情報を更新したエンティティ
     * @throws BusinessException LEGAL_FILING_NOT_FOUND: レコードが存在しない
     */
    @Transactional
    public LegalFilingEntity buildEvidencePackage(UUID legalFilingId, Long organizationId, Long requestingUserId) {

        LegalFilingEntity entity = getById(legalFilingId, organizationId);

        // テンプレート PDF を S3 から取得
        byte[] templatePdfBytes = storageService.download(entity.getTemplatePdfS3Key());

        // エスカレーションタイムライン PDF 生成
        DelinquencyEscalationEntity escalation = escalationRepository
                .findByResidentRegistryIdAndDeletedAtIsNull(entity.getResidentRegistryId())
                .orElse(null);
        Map<String, Object> timelineVars = buildTimelineVars(entity, escalation);
        byte[] timelinePdfBytes = pdfGeneratorService.generateFromTemplate(
                "pdf/legal-filing-evidence-timeline", timelineVars);

        // 表紙 PDF 生成
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> coverVars = buildCoverVars(entity, now, timelinePdfBytes.length);
        byte[] coverPdfBytes = pdfGeneratorService.generateFromTemplate(
                "pdf/legal-filing-art8-evidence-cover", coverVars);

        // ZIP 組み立て
        byte[] zipBytes = buildZip(templatePdfBytes, timelinePdfBytes, coverPdfBytes);

        // SHA-256 計算
        String sha256 = pdfGeneratorService.sha256Hex(zipBytes);

        // S3 アップロード
        String zipS3Key = buildEvidenceZipS3Key(organizationId, legalFilingId);
        storageService.upload(zipS3Key, zipBytes, "application/zip");

        // エンティティ更新
        entity.setEvidencePackageS3Key(zipS3Key);
        entity.setEvidenceSha256(sha256);
        entity.setEvidenceBuiltAt(now);
        entity = legalFilingRepository.save(entity);

        String metadata = String.format(
                "{\"legalFilingId\":\"%s\",\"evidenceSha256\":\"%s\"}",
                legalFilingId, sha256);
        auditLogService.record(AuditEventType.EVIDENCE_PACKAGE_BUILT.name(),
                requestingUserId, null, null, organizationId, null, null, null, metadata);

        log.info("証拠パッケージ生成完了: legalFilingId={}, sha256={}", legalFilingId, sha256);

        return entity;
    }

    // ─────────────────────────────────────────────
    // Pre-signed URL 発行
    // ─────────────────────────────────────────────

    /**
     * 証拠 ZIP の Pre-signed ダウンロード URL を発行する（有効期間 1h）。
     *
     * <p>ADMIN のみ呼び出し可能（Controller 層で権限確認済みであること）。
     *
     * @param legalFilingId    法的手続きレコード ID
     * @param organizationId   テナント ID
     * @param requestingUserId 実行ユーザー ID（監査ログ用）
     * @return Pre-signed ダウンロード URL（有効期間 1h）
     * @throws BusinessException LEGAL_FILING_NOT_FOUND: レコードが存在しない
     * @throws BusinessException EVIDENCE_NOT_READY: 証拠パッケージが未生成
     */
    public String generateEvidenceDownloadUrl(UUID legalFilingId, Long organizationId, Long requestingUserId) {
        LegalFilingEntity entity = getById(legalFilingId, organizationId);
        if (entity.getEvidencePackageS3Key() == null) {
            throw new BusinessException(SuccessionErrorCode.EVIDENCE_NOT_READY);
        }
        String url = storageService.generateDownloadUrl(entity.getEvidencePackageS3Key(), EVIDENCE_URL_TTL);

        String metadata = String.format("{\"legalFilingId\":\"%s\"}", legalFilingId);
        auditLogService.record(AuditEventType.EVIDENCE_PACKAGE_DOWNLOADED.name(),
                requestingUserId, null, null, organizationId, null, null, null, metadata);

        return url;
    }

    // ─────────────────────────────────────────────
    // 参照系
    // ─────────────────────────────────────────────

    /**
     * 組織配下の申立一覧（理事長ダッシュボード用）。
     *
     * @param organizationId テナント ID
     * @return 申立一覧（作成日降順）
     */
    public List<LegalFilingEntity> listByOrganization(Long organizationId) {
        return legalFilingRepository.findByOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(organizationId);
    }

    /**
     * 居住者別の申立履歴一覧。
     *
     * @param residentRegistryId 居住者台帳 ID
     * @param organizationId     テナント ID（現在は未使用だが、将来のシャーディング対応のために保持）
     * @return 申立履歴（作成日降順）
     */
    public List<LegalFilingEntity> listByResident(Long residentRegistryId, Long organizationId) {
        return legalFilingRepository.findByResidentRegistryIdAndDeletedAtIsNullOrderByCreatedAtDesc(residentRegistryId);
    }

    /**
     * テナント分離済みの単件取得。
     *
     * @param legalFilingId  法的手続きレコード ID
     * @param organizationId テナント ID
     * @return 法的手続きエンティティ
     * @throws BusinessException LEGAL_FILING_NOT_FOUND: レコードが存在しないか別テナント
     */
    public LegalFilingEntity getById(UUID legalFilingId, Long organizationId) {
        return legalFilingRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(legalFilingId, organizationId)
                .orElseThrow(() -> new BusinessException(SuccessionErrorCode.LEGAL_FILING_NOT_FOUND));
    }

    // ─────────────────────────────────────────────
    // プライベートヘルパー — S3 キー構築
    // ─────────────────────────────────────────────

    private String buildTemplatePdfS3Key(Long organizationId, UUID legalFilingId) {
        return String.format("organizations/%d/succession/legal-filings/%s/template.pdf",
                organizationId, legalFilingId);
    }

    private String buildEvidenceZipS3Key(Long organizationId, UUID legalFilingId) {
        return String.format("organizations/%d/succession/legal-filings/%s/evidence-package.zip",
                organizationId, legalFilingId);
    }

    // ─────────────────────────────────────────────
    // プライベートヘルパー — テンプレート変数構築
    // ─────────────────────────────────────────────

    private Map<String, Object> buildFilingTemplateVars(
            Long residentRegistryId, Long dwellingUnitId, DelinquencyEscalationEntity escalation) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("residentRegistryId", residentRegistryId);
        vars.put("dwellingUnitId", dwellingUnitId);
        vars.put("draftedAt", LocalDateTime.now());
        // v1 は ID 表示（v2: 部屋番号マスタ連携予定）
        vars.put("dwellingUnitLabel", "部屋 " + dwellingUnitId);
        if (escalation != null) {
            vars.put("delinquencyStartedAt", escalation.getDelinquencyStartedAt());
            vars.put("currentStage", escalation.getCurrentStage());
        }
        return vars;
    }

    private Map<String, Object> buildTimelineVars(
            LegalFilingEntity entity, DelinquencyEscalationEntity escalation) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("residentRegistryId", entity.getResidentRegistryId());
        vars.put("dwellingUnitLabel", "部屋 " + entity.getDwellingUnitId());
        vars.put("generatedAt", LocalDateTime.now());
        if (escalation != null) {
            vars.put("delinquencyStartedAt", escalation.getDelinquencyStartedAt());
            vars.put("currentStage", escalation.getCurrentStage());
            vars.put("stage1CompletedAt", escalation.getStage1CompletedAt());
            vars.put("stage2CompletedAt", escalation.getStage2CompletedAt());
            vars.put("stage3CompletedAt", escalation.getStage3CompletedAt());
            vars.put("stage4CompletedAt", escalation.getStage4CompletedAt());
            vars.put("stage5CompletedAt", escalation.getStage5CompletedAt());
            vars.put("frozenAt", escalation.getFrozenAt());
            vars.put("frozenReason", escalation.getFrozenReason());
            vars.put("resolvedAt", escalation.getResolvedAt());
            vars.put("resolvedReason", escalation.getResolvedReason());
        }
        return vars;
    }

    private Map<String, Object> buildCoverVars(
            LegalFilingEntity entity, LocalDateTime packageGeneratedAt, int timelineSize) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("dwellingUnitLabel", "部屋 " + entity.getDwellingUnitId());
        vars.put("packageGeneratedAt", packageGeneratedAt);
        vars.put("filingType", entity.getFilingType());
        vars.put("residentRegistryId", entity.getResidentRegistryId());
        // v1 では zip SHA-256 は buildEvidencePackage で計算するためここでは渡さない
        return vars;
    }

    // ─────────────────────────────────────────────
    // プライベートヘルパー — ZIP 生成
    // ─────────────────────────────────────────────

    /**
     * 3 つの PDF を ZIP に格納する。
     *
     * @param templatePdf  申立書テンプレート PDF
     * @param timelinePdf  エスカレーションタイムライン PDF
     * @param coverPdf     区分所有法 8 条 表紙 PDF
     * @return ZIP バイト配列
     */
    private byte[] buildZip(byte[] templatePdf, byte[] timelinePdf, byte[] coverPdf) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "01_template.pdf", templatePdf);
            addZipEntry(zos, "02_evidence-timeline.pdf", timelinePdf);
            addZipEntry(zos, "03_art8-cover.pdf", coverPdf);
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("証拠 ZIP 生成中にエラーが発生しました", e);
        }
    }

    private void addZipEntry(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}
