package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.CsvImportConfirmResponse;
import com.mannschaft.app.repairplan.dto.CsvImportPreviewResponse;
import com.mannschaft.app.repairplan.dto.CsvImportPreviewResponse.CsvImportError;
import com.mannschaft.app.repairplan.dto.CsvImportPreviewResponse.CsvRowPreview;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.RepairPlanTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 修繕計画項目 CSV インポートサービス（F08.8 Phase 1）。
 *
 * <p>F08.6 {@code BudgetCsvService} と同じく、Valkey に CSV 原文を 30 分保存して
 * preview → confirm の 2 段階で取り込みを確定する。</p>
 *
 * <p>CSV ヘッダは国交省「マンション修繕積立金ガイドライン」サンプル Excel 互換項目を
 * 自動判定する（{@code カテゴリ,項目名,...} で始まれば 1 行目をスキップ）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepairPlanItemCsvService {

    private final RepairPlanItemRepository itemRepository;
    private final RepairPlanTemplateRepository templateRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;

    /** Valkey 上の preview キー prefix */
    private static final String CSV_PREVIEW_PREFIX = "repairplan:csv-preview:";

    /** preview の保存期間 */
    static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    /** 1 ファイルあたりの最大サイズ（5MB） */
    static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;

    /** ステータス許容値 */
    private static final Set<String> ALLOWED_STATUS = Set.of("PLANNED", "RESERVED", "IN_PROGRESS", "DONE", "CANCELLED");

    /** 想定列数（status / cpi / tags は任意項目） */
    private static final int MIN_COLUMNS = 6; // category,title,description,plannedYear,plannedMonth,estimatedAmount

    // =========================================================================
    // public API
    // =========================================================================

    /**
     * CSV をパースしてバリデーションし、Valkey にプレビューを保存する。
     */
    public CsvImportPreviewResponse preview(MultipartFile file,
                                             Long userId,
                                             Long scopeId,
                                             String scopeType,
                                             Long organizationId) {
        // 認可: 対象スコープの ADMIN/DEPUTY_ADMIN のみ
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(RepairPlanErrorCode.REPAIR_PLAN_CSV_003);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(RepairPlanErrorCode.REPAIR_PLAN_CSV_002);
        }

        String csvContent;
        try {
            csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(RepairPlanErrorCode.REPAIR_PLAN_CSV_003, e);
        }

        return previewFromString(csvContent, userId, scopeId, scopeType, organizationId);
    }

    /**
     * 文字列ベース（テスト用 / ダウンロード再取込用）。実体ロジックを共有する。
     */
    public CsvImportPreviewResponse previewFromString(String csvContent,
                                                       Long userId,
                                                       Long scopeId,
                                                       String scopeType,
                                                       Long organizationId) {
        Set<String> knownCategories = collectKnownCategories(scopeId, scopeType, organizationId);

        String[] lines = csvContent.split("\\r?\\n");
        int startLine = detectHeaderLine(lines);

        List<CsvRowPreview> previewRows = new ArrayList<>();
        List<CsvImportError> errors = new ArrayList<>();
        int validRows = 0;
        int errorRows = 0;

        for (int i = startLine; i < lines.length; i++) {
            String line = stripBomIfFirst(lines[i], i == 0);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int rowNumber = i + 1;
            String[] cols = parseCsvLine(line);

            if (cols.length < MIN_COLUMNS) {
                previewRows.add(new CsvRowPreview(rowNumber, "", "", "", "", "", "", "", "", "",
                        false, "列数が不足しています（最低 " + MIN_COLUMNS + " 列必要）"));
                errors.add(new CsvImportError(rowNumber, "_row", "列数が不足しています"));
                errorRows++;
                continue;
            }

            String category = safeTrim(cols, 0);
            String title = safeTrim(cols, 1);
            String description = safeTrim(cols, 2);
            String plannedYearStr = safeTrim(cols, 3);
            String plannedMonthStr = safeTrim(cols, 4);
            String estimatedAmountStr = safeTrim(cols, 5);
            String cpiBasisYearStr = safeTrim(cols, 6);
            String statusStr = safeTrim(cols, 7);
            String tags = safeTrim(cols, 8);

            List<CsvImportError> rowErrors = validateRow(rowNumber, category, title,
                    plannedYearStr, plannedMonthStr, estimatedAmountStr,
                    cpiBasisYearStr, statusStr, knownCategories);

            boolean valid = rowErrors.isEmpty();
            String errorMessage = rowErrors.isEmpty() ? null
                    : rowErrors.stream().map(CsvImportError::message).collect(Collectors.joining("; "));

            previewRows.add(new CsvRowPreview(rowNumber, category, title, description,
                    plannedYearStr, plannedMonthStr, estimatedAmountStr,
                    cpiBasisYearStr, statusStr, tags, valid, errorMessage));

            if (valid) {
                validRows++;
            } else {
                errors.addAll(rowErrors);
                errorRows++;
            }
        }

        String importToken = UUID.randomUUID().toString();
        String previewKey = buildPreviewKey(importToken, userId, scopeType, scopeId);
        redisTemplate.opsForValue().set(previewKey, csvContent, PREVIEW_TTL);

        LocalDateTime expiresAt = LocalDateTime.now().plus(PREVIEW_TTL);

        return new CsvImportPreviewResponse(
                importToken,
                expiresAt,
                previewRows.size(),
                validRows,
                errorRows,
                previewRows,
                errors
        );
    }

    /**
     * preview で発行された importToken を使って正式インポートを実行する。
     */
    @Transactional
    public CsvImportConfirmResponse confirm(String importToken,
                                             Long userId,
                                             Long scopeId,
                                             String scopeType,
                                             Long organizationId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        if (importToken == null || importToken.isBlank()) {
            throw new BusinessException(RepairPlanErrorCode.REPAIR_PLAN_CSV_001);
        }

        String previewKey = buildPreviewKey(importToken, userId, scopeType, scopeId);
        String csvContent = redisTemplate.opsForValue().get(previewKey);
        if (csvContent == null) {
            throw new BusinessException(RepairPlanErrorCode.REPAIR_PLAN_CSV_001);
        }

        Set<String> knownCategories = collectKnownCategories(scopeId, scopeType, organizationId);

        String[] lines = csvContent.split("\\r?\\n");
        int startLine = detectHeaderLine(lines);

        List<RepairPlanItem> toInsert = new ArrayList<>();
        int totalRows = 0;
        int skippedRows = 0;
        LocalDateTime now = LocalDateTime.now();

        for (int i = startLine; i < lines.length; i++) {
            String line = stripBomIfFirst(lines[i], i == 0);
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            totalRows++;
            int rowNumber = i + 1;
            String[] cols = parseCsvLine(line);

            if (cols.length < MIN_COLUMNS) {
                skippedRows++;
                log.warn("CSV 行スキップ: 列数不足 row={}", rowNumber);
                continue;
            }

            String category = safeTrim(cols, 0);
            String title = safeTrim(cols, 1);
            String description = safeTrim(cols, 2);
            String plannedYearStr = safeTrim(cols, 3);
            String plannedMonthStr = safeTrim(cols, 4);
            String estimatedAmountStr = safeTrim(cols, 5);
            String cpiBasisYearStr = safeTrim(cols, 6);
            String statusStr = safeTrim(cols, 7);
            String tags = safeTrim(cols, 8);

            List<CsvImportError> rowErrors = validateRow(rowNumber, category, title,
                    plannedYearStr, plannedMonthStr, estimatedAmountStr,
                    cpiBasisYearStr, statusStr, knownCategories);

            if (!rowErrors.isEmpty()) {
                skippedRows++;
                continue;
            }

            int plannedYear = Integer.parseInt(plannedYearStr);
            Integer plannedMonth = plannedMonthStr.isEmpty() ? null : Integer.parseInt(plannedMonthStr);
            long estimatedAmount = Long.parseLong(estimatedAmountStr.replace(",", ""));
            int cpiBasisYear = cpiBasisYearStr.isEmpty() ? plannedYear : Integer.parseInt(cpiBasisYearStr);
            String status = statusStr.isEmpty() ? "PLANNED" : statusStr;
            String jsonTags = tagsToJsonArray(tags);

            RepairPlanItem entity = RepairPlanItem.builder()
                    .organizationId(organizationId)
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .category(category)
                    .title(title)
                    .description(description.isEmpty() ? null : description)
                    .plannedYear(plannedYear)
                    .plannedMonth(plannedMonth)
                    .estimatedAmount(estimatedAmount)
                    .cpiInflationBasisYear(cpiBasisYear)
                    .status(status)
                    .tags(jsonTags)
                    .createdBy(userId)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            toInsert.add(entity);
        }

        // バッチ INSERT
        if (!toInsert.isEmpty()) {
            itemRepository.saveAll(toInsert);
        }
        int insertedRows = toInsert.size();

        // Valkey から削除
        redisTemplate.delete(previewKey);

        // 監査ログ（非同期）
        String metadata = String.format(
                "{\"source\":\"REPAIR_PLAN_CSV_IMPORT\",\"scope_type\":\"%s\",\"scope_id\":%d,\"inserted\":%d,\"skipped\":%d,\"total\":%d}",
                scopeType, scopeId, insertedRows, skippedRows, totalRows);
        auditLogService.record(
                AuditEventType.PLAN_ITEM_CSV_IMPORTED.name(),
                userId,
                null,
                "TEAM".equals(scopeType) ? scopeId : null,
                organizationId,
                null,
                null,
                SecurityUtils.getCurrentSessionHash(),
                metadata
        );

        log.info("修繕計画項目 CSV インポート確定: scopeType={}, scopeId={}, inserted={}, skipped={}",
                scopeType, scopeId, insertedRows, skippedRows);

        return new CsvImportConfirmResponse(totalRows, insertedRows, skippedRows);
    }

    // =========================================================================
    // バリデーション
    // =========================================================================

    private List<CsvImportError> validateRow(int rowNumber,
                                              String category,
                                              String title,
                                              String plannedYearStr,
                                              String plannedMonthStr,
                                              String estimatedAmountStr,
                                              String cpiBasisYearStr,
                                              String statusStr,
                                              Set<String> knownCategories) {
        List<CsvImportError> errors = new ArrayList<>();

        if (category.isEmpty()) {
            errors.add(new CsvImportError(rowNumber, "category", "カテゴリは必須です"));
        } else if (!knownCategories.isEmpty() && !knownCategories.contains(category)) {
            errors.add(new CsvImportError(rowNumber, "category", "未登録のカテゴリです: " + category));
        }

        if (title.isEmpty()) {
            errors.add(new CsvImportError(rowNumber, "title", "項目名は必須です"));
        } else if (title.length() > 200) {
            errors.add(new CsvImportError(rowNumber, "title", "項目名は 200 文字以内で指定してください"));
        }

        // year
        if (plannedYearStr.isEmpty()) {
            errors.add(new CsvImportError(rowNumber, "planned_year", "計画年度は必須です"));
        } else {
            try {
                int year = Integer.parseInt(plannedYearStr);
                if (year < 1900 || year > 2200) {
                    errors.add(new CsvImportError(rowNumber, "planned_year", "計画年度の値が不正です: " + year));
                }
            } catch (NumberFormatException e) {
                errors.add(new CsvImportError(rowNumber, "planned_year", "計画年度は数値で指定してください"));
            }
        }

        // month（任意）
        if (!plannedMonthStr.isEmpty()) {
            try {
                int month = Integer.parseInt(plannedMonthStr);
                if (month < 1 || month > 12) {
                    errors.add(new CsvImportError(rowNumber, "planned_month", "計画月は 1〜12 で指定してください"));
                }
            } catch (NumberFormatException e) {
                errors.add(new CsvImportError(rowNumber, "planned_month", "計画月は数値で指定してください"));
            }
        }

        // amount
        if (estimatedAmountStr.isEmpty()) {
            errors.add(new CsvImportError(rowNumber, "estimated_amount", "見積金額は必須です"));
        } else {
            try {
                long amount = Long.parseLong(estimatedAmountStr.replace(",", ""));
                if (amount < 0) {
                    errors.add(new CsvImportError(rowNumber, "estimated_amount", "見積金額は 0 以上で指定してください"));
                }
            } catch (NumberFormatException e) {
                errors.add(new CsvImportError(rowNumber, "estimated_amount", "見積金額は数値で指定してください"));
            }
        }

        // cpi basis year（任意。空なら plannedYear と同値扱い）
        if (!cpiBasisYearStr.isEmpty()) {
            try {
                int basisYear = Integer.parseInt(cpiBasisYearStr);
                if (basisYear < 1900 || basisYear > 2200) {
                    errors.add(new CsvImportError(rowNumber, "cpi_inflation_basis_year",
                            "CPI 基準年度の値が不正です: " + basisYear));
                }
            } catch (NumberFormatException e) {
                errors.add(new CsvImportError(rowNumber, "cpi_inflation_basis_year",
                        "CPI 基準年度は数値で指定してください"));
            }
        }

        // status（任意。空なら PLANNED）
        if (!statusStr.isEmpty() && !ALLOWED_STATUS.contains(statusStr)) {
            errors.add(new CsvImportError(rowNumber, "status",
                    "ステータスが不正です: " + statusStr + "（許容: " + ALLOWED_STATUS + "）"));
        }

        return errors;
    }

    /**
     * テンプレートマスタからカテゴリ候補集合を取得する（SYSTEM + 同スコープのオーバーライド）。
     * テンプレートが 1 件も登録されていない場合は空集合を返し、カテゴリ未登録チェックをスキップする。
     */
    private Set<String> collectKnownCategories(Long scopeId, String scopeType, Long organizationId) {
        Set<String> categories = new HashSet<>();
        templateRepository.findBySystemScope().forEach(t -> categories.add(t.getCategory()));
        if (scopeId != null && scopeType != null) {
            templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId)
                    .forEach(t -> categories.add(t.getCategory()));
        }
        if (organizationId != null && !"ORGANIZATION".equals(scopeType)) {
            templateRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", organizationId)
                    .forEach(t -> categories.add(t.getCategory()));
        }
        return categories;
    }

    // =========================================================================
    // CSV パーサ
    // =========================================================================

    /**
     * ヘッダ行を検出して開始行番号を返す。日本語ヘッダ・英語ヘッダ双方に対応。
     */
    private int detectHeaderLine(String[] lines) {
        if (lines.length == 0) return 0;
        String first = stripBomIfFirst(lines[0], true).trim();
        if (first.isEmpty()) return 0;
        // 日本語ヘッダ
        if (first.startsWith("カテゴリ") || first.startsWith("項目")) return 1;
        // 英語ヘッダ
        String lower = first.toLowerCase();
        if (lower.startsWith("category") || lower.startsWith("item")) return 1;
        return 0;
    }

    private String stripBomIfFirst(String line, boolean isFirstLine) {
        if (line == null) return "";
        if (isFirstLine && !line.isEmpty() && line.charAt(0) == '﻿') {
            return line.substring(1);
        }
        return line;
    }

    private String safeTrim(String[] cols, int idx) {
        if (idx >= cols.length) return "";
        String v = cols[idx];
        return v == null ? "" : v.trim();
    }

    /**
     * RFC 4180 風 CSV 行パース。引用符・エスケープ二重引用符に対応。
     */
    String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * カンマ区切り tags を JSON 配列文字列に変換する。空文字なら null。
     */
    private String tagsToJsonArray(String tags) {
        if (tags == null || tags.isBlank()) return null;
        List<String> items = Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private String buildPreviewKey(String importToken, Long userId, String scopeType, Long scopeId) {
        return CSV_PREVIEW_PREFIX + scopeType + ":" + scopeId + ":" + userId + ":" + importToken;
    }
}
