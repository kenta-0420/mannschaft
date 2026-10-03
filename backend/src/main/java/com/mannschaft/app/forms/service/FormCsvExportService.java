package com.mannschaft.app.forms.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormFieldType;
import com.mannschaft.app.forms.FormScopes;
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

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * フォーム提出 CSV エクスポートサービス（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>テンプレート単位で提出を CSV にピボット展開する。EAV パターンの
 * {@code form_submission_values} を横展開し、フィールドラベルを列見出しにする。</p>
 *
 * <p>セキュリティ:</p>
 * <ul>
 *   <li>RFC 4180 準拠（カンマ・ダブルクオート・改行を含む値はクオートで囲み、内部のダブルクオートは二重化）</li>
 *   <li>CSV インジェクション防止: セル値の先頭が {@code =}, {@code +}, {@code -}, {@code @} の場合に
 *       シングルクオートを prefix する（設計書 §6 準拠）</li>
 *   <li>SECTION / DESCRIPTION 型フィールドはスキップ（入力値を持たないため）</li>
 * </ul>
 *
 * @since 2026-05-17 (F05.7 Phase 11 第四陣 4-B)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormCsvExportService {

    /** CSV 改行（RFC 4180 は CRLF）。 */
    private static final String CRLF = "\r\n";

    /** CSV インジェクション対象文字。 */
    private static final String CSV_INJECTION_CHARS = "=+-@";

    private final FormTemplateRepository templateRepository;
    private final FormTemplateFieldRepository fieldRepository;
    private final FormSubmissionRepository submissionRepository;
    private final FormSubmissionValueRepository valueRepository;
    private final AuditLogService auditLogService;
    private final AccessControlService accessControlService;

    /**
     * テンプレート単位の提出を CSV 文字列に変換する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>テンプレート + フィールド定義を取得</li>
     *   <li>提出一覧を取得（DRAFT を含む全件）</li>
     *   <li>各提出の値を取得し、フィールド順に列展開</li>
     *   <li>RFC 4180 形式で CSV をシリアライズ</li>
     *   <li>監査ログ {@link AuditEventType#FORM_SUBMISSIONS_CSV_EXPORTED} を記録</li>
     * </ol>
     *
     * <p>ヘッダー: {@code 提出ID, 提出者ID, ステータス, 提出日時, {フィールドラベル1}, ...}</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param templateId    テンプレート ID
     * @param currentUserId 操作ユーザー ID（監査ログ用）
     * @return CSV 文字列（UTF-8 でシリアライズ済み）
     */
    public String exportSubmissionsCsv(
            String scopeType, Long scopeId, Long templateId, Long currentUserId) {
        FormTemplateEntity template = templateRepository
                .findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
        // 認可根治戦役 Wave3-B4: CSV エクスポートは ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, FormScopes.canonical(scopeType));

        List<FormTemplateFieldEntity> fields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
        // CSV 列対象から除外する表示専用フィールド
        List<FormTemplateFieldEntity> dataFields = fields.stream()
                .filter(f -> f.getFieldType() != FormFieldType.LABEL)
                .toList();

        List<FormSubmissionEntity> submissions =
                submissionRepository.findByTemplateIdOrderByCreatedAtDesc(templateId);

        StringBuilder csv = new StringBuilder();
        appendHeader(csv, dataFields);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (FormSubmissionEntity s : submissions) {
            List<FormSubmissionValueEntity> values = valueRepository.findBySubmissionId(s.getId());
            Map<String, FormSubmissionValueEntity> byKey = new HashMap<>();
            for (FormSubmissionValueEntity v : values) {
                byKey.put(v.getFieldKey(), v);
            }
            csv.append(escapeCell(String.valueOf(s.getId()))).append(',');
            csv.append(escapeCell(String.valueOf(s.getSubmittedBy()))).append(',');
            csv.append(escapeCell(s.getStatus().name())).append(',');
            csv.append(escapeCell(s.getCreatedAt() == null ? "" : s.getCreatedAt().format(dtf)));
            for (FormTemplateFieldEntity f : dataFields) {
                csv.append(',');
                FormSubmissionValueEntity v = byKey.get(f.getFieldKey());
                csv.append(escapeCell(renderValue(v)));
            }
            csv.append(CRLF);
        }

        Long teamId = "teams".equalsIgnoreCase(scopeType) ? scopeId : null;
        Long orgId = "organizations".equalsIgnoreCase(scopeType) ? scopeId : null;
        String metadata = String.format(
                "{\"templateId\":%d,\"templateName\":\"%s\",\"rowCount\":%d}",
                templateId, escapeJson(template.getName()), submissions.size());
        auditLogService.record(
                AuditEventType.FORM_SUBMISSIONS_CSV_EXPORTED.name(),
                currentUserId, null, teamId, orgId, null, null, null, metadata);

        log.info("フォーム提出 CSV エクスポート: templateId={}, rowCount={}", templateId, submissions.size());
        return csv.toString();
    }

    // ─────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────

    private void appendHeader(StringBuilder csv, List<FormTemplateFieldEntity> dataFields) {
        csv.append(escapeCell("提出ID")).append(',');
        csv.append(escapeCell("提出者ID")).append(',');
        csv.append(escapeCell("ステータス")).append(',');
        csv.append(escapeCell("提出日時"));
        for (FormTemplateFieldEntity f : dataFields) {
            csv.append(',');
            csv.append(escapeCell(f.getFieldLabel()));
        }
        csv.append(CRLF);
    }

    private String renderValue(FormSubmissionValueEntity v) {
        if (v == null) return "";
        if (v.getTextValue() != null) return v.getTextValue();
        if (v.getNumberValue() != null) return v.getNumberValue().toPlainString();
        if (v.getDateValue() != null) return v.getDateValue().toString();
        if (v.getFileKey() != null) return v.getFileKey();
        return "";
    }

    /**
     * RFC 4180 + CSV インジェクション防止のセルエスケープ。
     */
    String escapeCell(String value) {
        if (value == null) return "";
        String safe = value;
        // CSV インジェクション防止 — 先頭が = + - @ の場合は ' を前置
        if (!safe.isEmpty() && CSV_INJECTION_CHARS.indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuote = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        if (needsQuote) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /** メタデータ JSON 用の最小限のエスケープ。 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
