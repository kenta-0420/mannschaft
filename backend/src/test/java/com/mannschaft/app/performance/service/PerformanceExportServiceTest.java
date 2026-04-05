package com.mannschaft.app.performance.service;

import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link PerformanceExportService} の単体テスト。
 * パフォーマンスCSVエクスポートのロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceExportService 単体テスト")
class PerformanceExportServiceTest {

    @Mock
    private PerformanceRecordRepository recordRepository;

    @Mock
    private PerformanceMetricService metricService;

    @InjectMocks
    private PerformanceExportService performanceExportService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long METRIC_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final LocalDate DATE_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate DATE_TO = LocalDate.of(2026, 3, 31);

    private PerformanceMetricEntity createMetric(Long id, String name, String unit) {
        PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                .teamId(TEAM_ID)
                .name(name)
                .unit(unit)
                .aggregationType(AggregationType.SUM)
                .build();
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {}
        return entity;
    }

    private PerformanceRecordEntity createRecord(Long metricId, Long userId, BigDecimal value,
                                                  LocalDate date, String note) {
        return PerformanceRecordEntity.builder()
                .metricId(metricId)
                .userId(userId)
                .recordedDate(date)
                .value(value)
                .note(note)
                .build();
    }

    // ========================================
    // countExportRecords
    // ========================================

    @Nested
    @DisplayName("countExportRecords")
    class CountExportRecords {

        @Test
        @DisplayName("正常系: 件数が返る")
        void countExportRecords_正常_件数が返る() {
            // Given
            given(recordRepository.countForExport(TEAM_ID, METRIC_ID, USER_ID, DATE_FROM, DATE_TO))
                    .willReturn(42L);

            // When
            long count = performanceExportService.countExportRecords(TEAM_ID, METRIC_ID, USER_ID, DATE_FROM, DATE_TO);

            // Then
            assertThat(count).isEqualTo(42L);
        }

        @Test
        @DisplayName("正常系: 0件の場合")
        void countExportRecords_0件_ゼロが返る() {
            // Given
            given(recordRepository.countForExport(TEAM_ID, null, null, null, null))
                    .willReturn(0L);

            // When
            long count = performanceExportService.countExportRecords(TEAM_ID, null, null, null, null);

            // Then
            assertThat(count).isEqualTo(0L);
        }
    }

    // ========================================
    // exportCsv
    // ========================================

    @Nested
    @DisplayName("exportCsv")
    class ExportCsv {

        @Test
        @DisplayName("正常系: CSVにBOMとヘッダーとデータ行が出力される")
        void exportCsv_正常_CSV出力される() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "距離", "km");
            PerformanceRecordEntity record = createRecord(METRIC_ID, USER_ID,
                    new BigDecimal("10.5000"), LocalDate.of(2026, 2, 15), "練習ラン");

            given(recordRepository.findForExport(TEAM_ID, METRIC_ID, USER_ID, DATE_FROM, DATE_TO))
                    .willReturn(List.of(record));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            // When
            performanceExportService.exportCsv(writer, TEAM_ID, METRIC_ID, USER_ID, DATE_FROM, DATE_TO);
            writer.flush();

            // Then
            String csv = stringWriter.toString();
            assertThat(csv).contains("\ufeff"); // BOM
            assertThat(csv).contains("recorded_date,user_display_name,metric_name,value,unit,note");
            assertThat(csv).contains("2026-02-15");
            assertThat(csv).contains("User#" + USER_ID);
            assertThat(csv).contains("距離");
            assertThat(csv).contains("10.5000");
            assertThat(csv).contains("km");
            assertThat(csv).contains("練習ラン");
        }

        @Test
        @DisplayName("正常系: レコード0件の場合はヘッダーのみ出力")
        void exportCsv_0件_ヘッダーのみ() {
            // Given
            given(recordRepository.findForExport(TEAM_ID, null, null, null, null))
                    .willReturn(List.of());
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of());

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            // When
            performanceExportService.exportCsv(writer, TEAM_ID, null, null, null, null);
            writer.flush();

            // Then
            String csv = stringWriter.toString();
            assertThat(csv).contains("recorded_date,user_display_name,metric_name,value,unit,note");
            String[] lines = csv.split("\n");
            assertThat(lines).hasSize(1); // ヘッダーのみ
        }

        @Test
        @DisplayName("正常系: メトリクスが見つからないレコードは空文字で出力")
        void exportCsv_メトリクス不在_空文字() {
            // Given
            PerformanceRecordEntity record = createRecord(999L, USER_ID,
                    new BigDecimal("5.0000"), LocalDate.of(2026, 1, 10), null);

            given(recordRepository.findForExport(TEAM_ID, null, null, DATE_FROM, DATE_TO))
                    .willReturn(List.of(record));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of()); // メトリクスなし

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            // When
            performanceExportService.exportCsv(writer, TEAM_ID, null, null, DATE_FROM, DATE_TO);
            writer.flush();

            // Then
            String csv = stringWriter.toString();
            // メトリクス名・ユニットが空文字になる
            assertThat(csv).contains("5.0000");
        }

        @Test
        @DisplayName("境界値: CSVインジェクション対策でノートの先頭に危険文字があればシングルクォート付与")
        void exportCsv_CSVインジェクション_サニタイズされる() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "スコア", "点");
            PerformanceRecordEntity record = createRecord(METRIC_ID, USER_ID,
                    new BigDecimal("100.0000"), LocalDate.of(2026, 3, 1), "=HYPERLINK(\"evil\")");

            given(recordRepository.findForExport(TEAM_ID, METRIC_ID, null, DATE_FROM, DATE_TO))
                    .willReturn(List.of(record));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            // When
            performanceExportService.exportCsv(writer, TEAM_ID, METRIC_ID, null, DATE_FROM, DATE_TO);
            writer.flush();

            // Then
            String csv = stringWriter.toString();
            // 先頭の = がサニタイズされ、シングルクォートが付与される
            assertThat(csv).contains("'=HYPERLINK");
        }

        @Test
        @DisplayName("境界値: noteがnullの場合は空文字で出力")
        void exportCsv_noteがnull_空文字() {
            // Given
            PerformanceMetricEntity metric = createMetric(METRIC_ID, "スコア", null);
            PerformanceRecordEntity record = createRecord(METRIC_ID, USER_ID,
                    new BigDecimal("50.0000"), LocalDate.of(2026, 2, 1), null);

            given(recordRepository.findForExport(TEAM_ID, METRIC_ID, null, null, null))
                    .willReturn(List.of(record));
            given(metricService.getActiveMetrics(TEAM_ID)).willReturn(List.of(metric));

            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);

            // When
            performanceExportService.exportCsv(writer, TEAM_ID, METRIC_ID, null, null, null);
            writer.flush();

            // Then
            String csv = stringWriter.toString();
            assertThat(csv).contains("50.0000");
        }
    }
}
