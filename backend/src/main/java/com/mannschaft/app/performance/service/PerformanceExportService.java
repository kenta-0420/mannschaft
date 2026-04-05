package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * パフォーマンスCSVエクスポートサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceExportService {

    private final PerformanceRecordRepository recordRepository;
    private final PerformanceMetricService metricService;

    /**
     * エクスポート対象件数を取得する。
     */
    public long countExportRecords(Long teamId, Long metricId, Long userId, LocalDate dateFrom, LocalDate dateTo) {
        return recordRepository.countForExport(teamId, metricId, userId, dateFrom, dateTo);
    }

    /**
     * 同期的にCSVを書き出す（1,000件以下）。
     */
    public void exportCsv(PrintWriter writer, Long teamId, Long metricId, Long userId,
                           LocalDate dateFrom, LocalDate dateTo) {
        List<PerformanceRecordEntity> records = recordRepository.findForExport(teamId, metricId, userId, dateFrom, dateTo);
        List<PerformanceMetricEntity> metrics = metricService.getActiveMetrics(teamId);
        Map<Long, PerformanceMetricEntity> metricMap = metrics.stream()
                .collect(Collectors.toMap(PerformanceMetricEntity::getId, m -> m));

        // BOM + header
        writer.write('\ufeff');
        writer.println("recorded_date,user_display_name,metric_name,value,unit,note");

        for (PerformanceRecordEntity record : records) {
            PerformanceMetricEntity metric = metricMap.get(record.getMetricId());
            String metricName = metric != null ? metric.getName() : "";
            String unit = metric != null && metric.getUnit() != null ? metric.getUnit() : "";
            String note = sanitizeCsvValue(record.getNote());

            writer.printf("%s,%s,%s,%s,%s,%s%n",
                    record.getRecordedDate(),
                    "User#" + record.getUserId(),
                    sanitizeCsvValue(metricName),
                    record.getValue().toPlainString(),
                    sanitizeCsvValue(unit),
                    note);
        }
    }

    /**
     * CSVインジェクション対策 + CSV引用符処理。
     * 先頭が危険文字の場合はシングルクォートを付与し、
     * カンマ・引用符・改行を含む場合はダブルクォートで囲む。
     */
    private String sanitizeCsvValue(String value) {
        if (value == null) return "";
        String sanitized = value.trim();

        // 1. CSVインジェクション対策: 先頭が危険文字の場合はシングルクォートを付与
        if (sanitized.startsWith("=") || sanitized.startsWith("+")
                || sanitized.startsWith("-") || sanitized.startsWith("@")) {
            sanitized = "'" + sanitized;
        }

        // 2. CSV引用符処理: カンマ・引用符・改行を含む場合はダブルクォートで囲む
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n")) {
            sanitized = "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }

        return sanitized;
    }
}
