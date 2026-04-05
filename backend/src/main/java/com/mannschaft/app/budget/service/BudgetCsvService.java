package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetApprovalStatus;
import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetTransactionType;
import com.mannschaft.app.budget.dto.CsvImportConfirmRequest;
import com.mannschaft.app.budget.dto.CsvImportPreviewResponse;
import com.mannschaft.app.budget.dto.CsvImportPreviewResponse.CsvRowPreview;
import com.mannschaft.app.budget.dto.CsvImportResultResponse;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 予算CSV入出力サービス。CSVエクスポート・インポートプレビュー・確定を担��する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetCsvService {

    private final BudgetTransactionRepository transactionRepository;
    private final BudgetFiscalYearService fiscalYearService;
    private final BudgetCategoryService categoryService;
    private final AccessControlService accessControlService;
    private final StringRedisTemplate redisTemplate;

    private static final String CSV_PREVIEW_PREFIX = "budget:csv-preview:";
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 会計年度の取引データをCSVエクスポートする。
     */
    public byte[] export(Long fiscalYearId) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        List<BudgetTransactionEntity> transactions = transactionRepository.findByFiscalYearId(fiscalYearId);

        // カテゴリ名マップ
        Map<Long, String> categoryNames = categoryService.listFlatByFiscalYear(fiscalYearId)
                .stream()
                .collect(Collectors.toMap(c -> c.id(), c -> c.name()));

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // BOM
        csv.append("日付,カテゴリ,種別,金額,説明,支払方法,参照番号,承認状態\n");

        for (BudgetTransactionEntity tx : transactions) {
            csv.append(tx.getTransactionDate()).append(',');
            csv.append(escapeCsv(categoryNames.getOrDefault(tx.getCategoryId(), ""))).append(',');
            csv.append(tx.getTransactionType().name()).append(',');
            csv.append(tx.getAmount()).append(',');
            csv.append(escapeCsv(tx.getTitle())).append(',');
            csv.append(tx.getPaymentMethod() != null ? tx.getPaymentMethod() : "").append(',');
            csv.append(tx.getReferenceNumber() != null ? tx.getReferenceNumber() : "").append(',');
            csv.append(tx.getApprovalStatus().name()).append('\n');
        }

        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * CSVデータを解析し、プレビューをValkey（Redis）に一時保存する。
     */
    public CsvImportPreviewResponse importPreview(Long fiscalYearId, String csvContent) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fy.getScopeId(), fy.getScopeType());

        // カテゴリ名→IDマップ
        Map<String, Long> categoryNameToId = categoryService.listFlatByFiscalYear(fiscalYearId)
                .stream()
                .collect(Collectors.toMap(c -> c.name(), c -> c.id(), (a, b) -> a));

        String[] lines = csvContent.split("\n");
        List<CsvRowPreview> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int validRows = 0;
        int errorRows = 0;

        // ヘッダー行をスキップ（BOMも除去）
        int startLine = 0;
        if (lines.length > 0) {
            String firstLine = lines[0].replace("\uFEFF", "").trim();
            if (firstLine.startsWith("日付") || firstLine.toLowerCase().startsWith("date")) {
                startLine = 1;
            }
        }

        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] cols = parseCsvLine(line);
            int rowNum = i + 1;

            if (cols.length < 5) {
                rows.add(new CsvRowPreview(rowNum, "", "", "", "", "", false, "列数が不足しています"));
                errorRows++;
                errors.add("行" + rowNum + ": 列数が不足しています");
                continue;
            }

            String dateStr = cols[0].trim();
            String categoryName = cols[1].trim();
            String txTypeStr = cols[2].trim();
            String amountStr = cols[3].trim();
            String description = cols[4].trim();

            StringBuilder rowError = new StringBuilder();
            boolean valid = true;

            // 日付検証
            try {
                LocalDate.parse(dateStr, DATE_FORMAT);
            } catch (DateTimeParseException e) {
                rowError.append("日付形式が不正です; ");
                valid = false;
            }

            // カテゴリ検証
            if (!categoryNameToId.containsKey(categoryName)) {
                rowError.append("カテゴリが見つかりません; ");
                valid = false;
            }

            // 種別検証
            try {
                BudgetTransactionType.valueOf(txTypeStr);
            } catch (IllegalArgumentException e) {
                rowError.append("種別が不正です(INCOME/EXPENSE); ");
                valid = false;
            }

            // 金額検証
            try {
                BigDecimal amount = new BigDecimal(amountStr);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    rowError.append("金額は正の値が必要です; ");
                    valid = false;
                }
            } catch (NumberFormatException e) {
                rowError.append("金額形式が不正です; ");
                valid = false;
            }

            if (valid) {
                validRows++;
            } else {
                errorRows++;
                errors.add("行" + rowNum + ": " + rowError);
            }

            rows.add(new CsvRowPreview(rowNum, categoryName, txTypeStr, amountStr,
                    dateStr, description, valid, valid ? null : rowError.toString()));
        }

        // Valkeyに一時保存
        String previewKey = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                CSV_PREVIEW_PREFIX + previewKey,
                csvContent,
                PREVIEW_TTL);

        return new CsvImportPreviewResponse(previewKey, rows.size(), validRows, errorRows, rows, errors);
    }

    /**
     * プレビュー済みCSVデータを確定INSERTする。
     */
    @Transactional
    public CsvImportResultResponse importConfirm(CsvImportConfirmRequest request) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(request.fiscalYearId());
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fy.getScopeId(), fy.getScopeType());

        // Valkeyからデータ取得
        String csvContent = redisTemplate.opsForValue().get(CSV_PREVIEW_PREFIX + request.previewKey());
        if (csvContent == null) {
            throw new BusinessException(BudgetErrorCode.BUDGET_012);
        }

        // カテゴリ名→IDマップ
        Map<String, Long> categoryNameToId = categoryService.listFlatByFiscalYear(request.fiscalYearId())
                .stream()
                .collect(Collectors.toMap(c -> c.name(), c -> c.id(), (a, b) -> a));

        String[] lines = csvContent.split("\n");
        int insertedRows = 0;
        int skippedRows = 0;

        int startLine = 0;
        if (lines.length > 0) {
            String firstLine = lines[0].replace("\uFEFF", "").trim();
            if (firstLine.startsWith("日付") || firstLine.toLowerCase().startsWith("date")) {
                startLine = 1;
            }
        }

        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] cols = parseCsvLine(line);
            if (cols.length < 5) {
                skippedRows++;
                continue;
            }

            try {
                String dateStr = cols[0].trim();
                String categoryName = cols[1].trim();
                String txTypeStr = cols[2].trim();
                String amountStr = cols[3].trim();
                String description = cols[4].trim();

                Long categoryId = categoryNameToId.get(categoryName);
                if (categoryId == null) {
                    skippedRows++;
                    continue;
                }

                BudgetTransactionEntity entity = BudgetTransactionEntity.builder()
                        .fiscalYearId(request.fiscalYearId())
                        .categoryId(categoryId)
                        .scopeType(fy.getScopeType())
                        .scopeId(fy.getScopeId())
                        .transactionType(BudgetTransactionType.valueOf(txTypeStr))
                        .amount(new BigDecimal(amountStr))
                        .transactionDate(LocalDate.parse(dateStr, DATE_FORMAT))
                        .title(description)
                        .approvalStatus(BudgetApprovalStatus.APPROVED)
                        .recordedBy(currentUserId)
                        .build();

                transactionRepository.save(entity);
                insertedRows++;
            } catch (Exception e) {
                skippedRows++;
                log.warn("CSV行のINSERTに失敗しました: line={}", i + 1, e);
            }
        }

        // Valkeyから削除
        redisTemplate.delete(CSV_PREVIEW_PREFIX + request.previewKey());

        log.info("CSVインポート確定: fiscalYearId={}, inserted={}, skipped={}",
                request.fiscalYearId(), insertedRows, skippedRows);

        return new CsvImportResultResponse(insertedRows + skippedRows, insertedRows, skippedRows);
    }

    // ========================================
    // ヘルパー
    // ========================================

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String[] parseCsvLine(String line) {
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
}
