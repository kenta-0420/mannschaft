package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ActiveIncidentResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * エラーレポートの検索・統計・一覧取得を担当するサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ErrorReportQueryService {

    private final ErrorReportRepository errorReportRepository;

    /**
     * エラーレポート統計情報を取得する。
     *
     * @return 統計レスポンス
     */
    public ErrorReportStatsResponse getStats() {
        long totalNew = errorReportRepository.countByStatus(ErrorReportStatus.NEW);
        long totalInvestigating = errorReportRepository.countByStatus(ErrorReportStatus.INVESTIGATING);
        long totalReopened = errorReportRepository.countByStatus(ErrorReportStatus.REOPENED);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long totalToday = errorReportRepository.countByCreatedAtAfter(todayStart);

        List<ErrorReportEntity> topErrors = errorReportRepository
                .findTop5ByStatusInOrderByOccurrenceCountDesc(
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

        return ErrorReportStatsResponse.builder()
                .totalNew(totalNew)
                .totalInvestigating(totalInvestigating)
                .totalReopened(totalReopened)
                .totalToday(totalToday)
                .topErrors(topErrors.stream()
                        .map(e -> ErrorReportStatsResponse.TopError.builder()
                                .errorHash(e.getErrorHash())
                                .errorMessage(e.getErrorMessage())
                                .pageUrl(e.getPageUrl())
                                .occurrenceCount(e.getOccurrenceCount())
                                .affectedUserCount(e.getAffectedUserCount())
                                .lastOccurredAt(e.getLastOccurredAt())
                                .build())
                        .toList())
                .build();
    }

    /**
     * アクティブなインシデント（CRITICAL/HIGH かつ NEW/INVESTIGATING/REOPENED）を取得する。
     *
     * @return アクティブインシデントレスポンスのリスト
     */
    @Cacheable("active-incidents")
    public ActiveIncidentResponse getActiveIncidents() {
        List<ErrorReportEntity> reports = errorReportRepository
                .findBySeverityInAndStatusIn(
                        List.of(ErrorReportSeverity.CRITICAL, ErrorReportSeverity.HIGH),
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

        List<ActiveIncidentResponse.Incident> incidents = reports.stream()
                .map(report -> ActiveIncidentResponse.Incident.builder()
                        .pagePattern(toWildcardPattern(extractPath(report.getPageUrl())))
                        .message("一部の画面で不具合が発生しています。現在対応中です。")
                        .severity(report.getSeverity().name())
                        .since(report.getFirstOccurredAt())
                        .build())
                .toList();

        return ActiveIncidentResponse.builder()
                .incidents(incidents)
                .build();
    }

    /**
     * エラーレポートをIDで取得する。
     *
     * @param id エラーレポートID
     * @return エラーレポートエンティティ
     */
    public ErrorReportEntity findById(Long id) {
        return errorReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));
    }

    /**
     * エラーレポートを検索する（ステータス・重要度・日付範囲でフィルタ）。
     *
     * @param status   ステータス文字列（nullable）
     * @param severity 重要度文字列（nullable）
     * @param from     開始日（nullable）
     * @param to       終了日（nullable）
     * @param pageable ページング情報
     * @return ページングされたエラーレポート
     */
    public Page<ErrorReportEntity> search(String status, String severity,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        ErrorReportStatus statusEnum = null;
        ErrorReportSeverity severityEnum = null;
        try {
            statusEnum = status != null ? ErrorReportStatus.valueOf(status) : null;
            severityEnum = severity != null ? ErrorReportSeverity.valueOf(severity) : null;
        } catch (IllegalArgumentException e) {
            // 不正な enum 値はフィルタ無しとして扱う
            log.warn("不正なフィルタ値: status={}, severity={}", status, severity);
        }

        if (statusEnum != null && severityEnum != null) {
            return errorReportRepository.findByStatusAndSeverity(statusEnum, severityEnum, pageable);
        } else if (statusEnum != null) {
            return errorReportRepository.findByStatus(statusEnum, pageable);
        } else if (severityEnum != null) {
            return errorReportRepository.findBySeverity(severityEnum, pageable);
        } else if (from != null && to != null) {
            return errorReportRepository.findByCreatedAtBetween(
                    from.atStartOfDay(), to.plusDays(1).atStartOfDay(), pageable);
        }
        return errorReportRepository.findAll(pageable);
    }

    /**
     * URL からパス部分を抽出する。
     */
    private String extractPath(String url) {
        try {
            return URI.create(url).getPath();
        } catch (Exception e) {
            // URL パースに失敗した場合はそのまま返す
            return url;
        }
    }

    /**
     * パスからワイルドカードパターンを生成する。
     * 末尾の動的セグメント（数値・UUID）を * に置換。
     */
    private String toWildcardPattern(String path) {
        if (path == null) return "*";
        return path.replaceAll("/\\d+", "/*")
                .replaceAll("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "/*");
    }
}
