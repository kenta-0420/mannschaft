package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityFieldStatsResponse;
import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.common.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 活動記録統計サービス。統計・集計・CSVエクスポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityStatsService {

    private final ActivityResultRepository resultRepository;
    private final ActivityTemplateFieldRepository fieldRepository;

    /**
     * 活動統計を取得する。
     */
    public ActivityStatsResponse getStats(ActivityScopeType scopeType, Long scopeId,
                                           Long templateId, String period,
                                           LocalDate dateFrom, LocalDate dateTo) {
        long totalActivities = resultRepository.countByScopeTypeAndScopeId(scopeType, scopeId);

        // TODO: テンプレート別集計、月別集計、トップ参加者の集計をJPQL/ネイティブクエリで実装
        return ActivityStatsResponse.builder()
                .totalActivities(totalActivities)
                .byTemplate(Collections.emptyList())
                .byMonth(Collections.emptyList())
                .topParticipants(Collections.emptyList())
                .build();
    }

    /**
     * カスタムフィールドの集計データを取得する。
     */
    public ActivityFieldStatsResponse getFieldStats(ActivityScopeType scopeType, Long scopeId,
                                                     Long templateId, String fieldKey, String period) {
        // field_key のバリデーション（SQL Injection 防止）
        if (!fieldKey.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("不正なfield_keyです");
        }

        // フィールド定義の取得
        ActivityTemplateFieldEntity field = fieldRepository.findByTemplateIdAndFieldKey(templateId, fieldKey)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND));

        // TODO: JSON_EXTRACTを使ったネイティブクエリで集計を実装
        return ActivityFieldStatsResponse.builder()
                .fieldKey(field.getFieldKey())
                .fieldLabel(field.getFieldLabel())
                .unit(field.getUnit())
                .aggregation(new ActivityFieldStatsResponse.Aggregation(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .trend(Collections.emptyList())
                .build();
    }

    /**
     * 活動記録をCSVエクスポートする。
     */
    public void exportCsv(ActivityScopeType scopeType, Long scopeId,
                           Long templateId, LocalDate dateFrom, LocalDate dateTo,
                           HttpServletResponse response) {
        List<ActivityResultEntity> results = resultRepository.findForExport(
                scopeType, scopeId, templateId, dateFrom, dateTo, PageRequest.of(0, 5000));

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=activities_" +
                scopeType.name() + "_" + scopeId + ".csv");

        try {
            PrintWriter writer = response.getWriter();
            // BOM for Excel
            writer.write('\uFEFF');

            // ヘッダー
            writer.println("日付,タイトル,参加者数,作成者");

            for (ActivityResultEntity result : results) {
                writer.printf("%s,%s,%d,%s%n",
                        result.getActivityDate(),
                        escapeCsv(result.getTitle()),
                        0, // TODO: 参加者数の取得
                        result.getCreatedBy() != null ? result.getCreatedBy().toString() : "退会済みユーザー");
            }

            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("CSVエクスポートに失敗しました", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
