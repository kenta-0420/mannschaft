package com.mannschaft.app.forms.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormScopes;
import com.mannschaft.app.forms.dto.FormPdfDownloadUrlResponse;
import com.mannschaft.app.forms.dto.FormPdfGenerateResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormSubmissionValueEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormSubmissionValueRepository;
import com.mannschaft.app.forms.repository.FormTemplateFieldRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * フォーム提出 PDF サービス（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>提出済みフォームの内容を Thymeleaf テンプレート（{@code pdf/form-submission}）に流し込み、
 * Flying Saucer で PDF を生成して R2/S3 にアップロードする。生成結果の S3 キーは
 * {@link FormSubmissionEntity#setPdfFileKey(String)} で永続化する。</p>
 *
 * <p>F09.15 {@code LegalFilingService} と異なり POI XWPF（Word）ではなく
 * 既存基盤 {@link PdfGeneratorService}（Thymeleaf + Flying Saucer）を流用する。
 * 既に登録済みの日本語フォント / Cache Policy / I/O エラー処理を再利用できるためコスト低。</p>
 *
 * <p>認可: 提出者本人 / テンプレート作成者 / スコープ ADMIN・DEPUTY_ADMIN 以上 のいずれか
 * （認可根治戦役 Wave3-B4 で {@code AccessControlService.isAdminOrAbove} を本 Service に実装済み）。</p>
 *
 * @since 2026-05-17 (F05.7 Phase 11 第四陣 4-B)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormPdfService {

    /** PDF Pre-signed ダウンロード URL の有効期間（5 分）。設計書 §6 セキュリティ準拠。 */
    private static final Duration PDF_DOWNLOAD_TTL = Duration.ofMinutes(5);

    /** R2/S3 オブジェクトキーのプレフィックス。 */
    private static final String PDF_KEY_PREFIX = "forms";

    /** Thymeleaf テンプレート名。 */
    private static final String PDF_TEMPLATE = "pdf/form-submission";

    private final FormSubmissionRepository submissionRepository;
    private final FormSubmissionValueRepository valueRepository;
    private final FormTemplateRepository templateRepository;
    private final FormTemplateFieldRepository fieldRepository;
    private final PdfGeneratorService pdfGeneratorService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final AccessControlService accessControlService;

    /**
     * 提出済みフォームの PDF を生成し R2/S3 にアップロードする。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>提出を取得・SUBMITTED 以降であることを確認</li>
     *   <li>認可: 提出者本人 / テンプレート作成者 のいずれか</li>
     *   <li>テンプレート + フィールド + 値を取得し Thymeleaf 変数を組み立て</li>
     *   <li>{@link PdfGeneratorService#generateFromTemplate} で PDF バイト列を取得</li>
     *   <li>R2/S3 にアップロード（key = {@code forms/{scopeType}/{scopeId}/submissions/{id}/form_{id}_{epoch}.pdf}）</li>
     *   <li>{@link FormSubmissionEntity#setPdfFileKey(String)} で S3 キーを保存</li>
     *   <li>監査ログ {@link AuditEventType#FORM_PDF_GENERATED} を非同期記録</li>
     * </ol>
     *
     * @param scopeType     スコープ種別（teams / organizations）
     * @param scopeId       スコープ ID
     * @param submissionId  提出 ID
     * @param currentUserId 操作ユーザー ID
     * @return 生成済み PDF メタ情報（S3 キー + 生成日時）
     * @throws BusinessException SUBMISSION_NOT_FOUND / PDF_GENERATION_NOT_ALLOWED / PDF_ACCESS_DENIED
     */
    @Transactional
    public FormPdfGenerateResponse generatePdf(
            String scopeType, Long scopeId, Long submissionId, Long currentUserId) {
        FormSubmissionEntity submission = findSubmissionInScopeOrThrow(scopeType, scopeId, submissionId);
        ensureSubmittedOrLater(submission);
        FormTemplateEntity template = findTemplateOrThrow(submission.getTemplateId());
        ensureViewerCanAccess(submission, template, currentUserId, scopeType, scopeId);

        List<FormTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(template.getId());
        List<FormSubmissionValueEntity> values =
                valueRepository.findBySubmissionId(submissionId);

        Map<String, Object> vars = buildTemplateVars(template, submission, fields, values);
        byte[] pdfBytes = pdfGeneratorService.generateFromTemplate(PDF_TEMPLATE, vars);

        String pdfKey = buildPdfKey(scopeType, scopeId, submissionId);
        storageService.upload(pdfKey, pdfBytes, "application/pdf");
        submission.setPdfFileKey(pdfKey);
        submissionRepository.save(submission);

        // 監査ログ（非同期 fire-and-forget）
        Long teamId = "teams".equalsIgnoreCase(scopeType) ? scopeId : null;
        Long orgId = "organizations".equalsIgnoreCase(scopeType) ? scopeId : null;
        String metadata = String.format(
                "{\"submissionId\":%d,\"templateId\":%d,\"pdfFileKey\":\"%s\"}",
                submissionId, template.getId(), pdfKey);
        auditLogService.record(
                AuditEventType.FORM_PDF_GENERATED.name(),
                currentUserId, submission.getSubmittedBy(),
                teamId, orgId, null, null, null, metadata);

        log.info("フォーム PDF 生成: submissionId={}, templateId={}, pdfFileKey={}",
                submissionId, template.getId(), pdfKey);
        return new FormPdfGenerateResponse(submissionId, pdfKey, LocalDateTime.now());
    }

    /**
     * 生成済み PDF の Pre-signed ダウンロード URL を発行する（有効期限 5 分）。
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param submissionId  提出 ID
     * @param currentUserId 操作ユーザー ID
     * @return Pre-signed URL + 有効期限秒
     * @throws BusinessException SUBMISSION_NOT_FOUND / PDF_NOT_GENERATED / PDF_ACCESS_DENIED
     */
    public FormPdfDownloadUrlResponse generateDownloadUrl(
            String scopeType, Long scopeId, Long submissionId, Long currentUserId) {
        FormSubmissionEntity submission = findSubmissionInScopeOrThrow(scopeType, scopeId, submissionId);
        FormTemplateEntity template = findTemplateOrThrow(submission.getTemplateId());
        ensureViewerCanAccess(submission, template, currentUserId, scopeType, scopeId);

        String pdfKey = submission.getPdfFileKey();
        if (pdfKey == null || pdfKey.isBlank()) {
            throw new BusinessException(FormErrorCode.PDF_NOT_GENERATED);
        }

        String url = storageService.generateDownloadUrl(pdfKey, PDF_DOWNLOAD_TTL);
        log.debug("フォーム PDF download URL 発行: submissionId={}, pdfFileKey={}", submissionId, pdfKey);
        return new FormPdfDownloadUrlResponse(url, PDF_DOWNLOAD_TTL.toSeconds());
    }

    // ─────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────

    private FormSubmissionEntity findSubmissionOrThrow(Long submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * 提出を取得し、URL の {@code scopeType}/{@code scopeId} と一致するかを検証する（BOLA ガード）。
     * 認可根治戦役 Wave3-B4: 従来は submissionId のみで取得しており、URL の scope を一切
     * 検証していなかった。不一致は {@link FormErrorCode#SUBMISSION_NOT_FOUND}（404）で存在秘匿する。
     */
    private FormSubmissionEntity findSubmissionInScopeOrThrow(String scopeType, Long scopeId, Long submissionId) {
        FormSubmissionEntity entity = findSubmissionOrThrow(submissionId);
        if (!entity.getScopeType().equalsIgnoreCase(scopeType) || !entity.getScopeId().equals(scopeId)) {
            throw new BusinessException(FormErrorCode.SUBMISSION_NOT_FOUND);
        }
        return entity;
    }

    private FormTemplateEntity findTemplateOrThrow(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
    }

    private void ensureSubmittedOrLater(FormSubmissionEntity submission) {
        if (!submission.isSubmitted()) {
            throw new BusinessException(FormErrorCode.PDF_GENERATION_NOT_ALLOWED);
        }
    }

    /**
     * 提出者本人 / テンプレート作成者 / スコープ ADMIN 以上 のいずれかであることを確認する。
     *
     * <p>認可根治戦役 Wave3-B4: 従来コメントは「ADMIN 経路は Controller 層で別途担保する想定」
     * としていたが、実際には Controller・Service いずれにも ADMIN 判定が実装されておらず、
     * 提出者本人でも作成者でもない ADMIN は PDF を閲覧できない過小権限バグだった。
     * {@link AccessControlService#isAdminOrAbove} を追加して根治する。</p>
     */
    private void ensureViewerCanAccess(
            FormSubmissionEntity submission, FormTemplateEntity template, Long userId,
            String scopeType, Long scopeId) {
        if (userId == null) {
            throw new BusinessException(FormErrorCode.PDF_ACCESS_DENIED);
        }
        boolean isSubmitter = userId.equals(submission.getSubmittedBy());
        boolean isCreator = userId.equals(template.getCreatedBy());
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, scopeId, FormScopes.canonical(scopeType));
        if (!isSubmitter && !isCreator && !isAdmin) {
            throw new BusinessException(FormErrorCode.PDF_ACCESS_DENIED);
        }
    }

    private Map<String, Object> buildTemplateVars(
            FormTemplateEntity template,
            FormSubmissionEntity submission,
            List<FormTemplateFieldEntity> fields,
            List<FormSubmissionValueEntity> values) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("templateName", template.getName());
        vars.put("templateDescription", template.getDescription());
        vars.put("submissionId", submission.getId());
        vars.put("submittedAt", submission.getCreatedAt());
        vars.put("submittedBy", submission.getSubmittedBy());
        vars.put("status", submission.getStatus().name());

        // field_key -> value のマップを作って、フィールド定義の順序で行を組む
        Map<String, FormSubmissionValueEntity> valuesByKey = new HashMap<>();
        for (FormSubmissionValueEntity v : values) {
            valuesByKey.put(v.getFieldKey(), v);
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (FormTemplateFieldEntity f : fields) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fieldLabel", f.getFieldLabel());
            row.put("fieldType", f.getFieldType().name());
            FormSubmissionValueEntity v = valuesByKey.get(f.getFieldKey());
            row.put("value", v == null ? "" : displayValue(v));
            rows.add(row);
        }
        vars.put("rows", rows);
        return vars;
    }

    private String displayValue(FormSubmissionValueEntity v) {
        if (v.getTextValue() != null) return v.getTextValue();
        if (v.getNumberValue() != null) return v.getNumberValue().toPlainString();
        if (v.getDateValue() != null) return v.getDateValue().toString();
        if (v.getFileKey() != null) return "[添付: " + v.getFileKey() + "]";
        return "";
    }

    private String buildPdfKey(String scopeType, Long scopeId, Long submissionId) {
        long epoch = System.currentTimeMillis();
        return String.format("%s/%s/%d/submissions/%d/form_%d_%d.pdf",
                PDF_KEY_PREFIX, scopeType, scopeId, submissionId, submissionId, epoch);
    }
}
