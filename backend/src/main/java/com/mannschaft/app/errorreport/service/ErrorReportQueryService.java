package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     * 障害告知バナーの検知候補を取得する（F12.5 シスアド用）。
     *
     * <p>エラーテレメトリ（CRITICAL/HIGH × NEW/INVESTIGATING/REOPENED）から
     * バナー化候補を機械的に抽出する。これは「気づき」であり、実際にバナーを公開するかは
     * 管理者が判断する（ハイブリッド方式）。本メソッドはキャッシュしない
     * （管理者向けかつ即時性が重要なため）。</p>
     *
     * @return 検知候補（pagePattern / severity / occurrenceCount / affectedUserCount / since）
     */
    public List<IncidentSuggestion> getIncidentSuggestions() {
        List<ErrorReportEntity> reports = errorReportRepository
                .findBySeverityInAndStatusIn(
                        List.of(ErrorReportSeverity.CRITICAL, ErrorReportSeverity.HIGH),
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.INVESTIGATING, ErrorReportStatus.REOPENED));

        return reports.stream()
                .map(report -> new IncidentSuggestion(
                        toWildcardPattern(extractPath(report.getPageUrl())),
                        report.getSeverity().name(),
                        report.getOccurrenceCount() != null ? report.getOccurrenceCount() : 0L,
                        report.getAffectedUserCount() != null ? report.getAffectedUserCount() : 0L,
                        report.getFirstOccurredAt()))
                .toList();
    }

    /**
     * 障害告知バナーの検知候補（内部 DTO）。
     *
     * <p>Controller/外部 DTO 変換は incidentbanner ドメイン側が担う。</p>
     *
     * @param pagePattern       検知元ページパターン（ワイルドカード化済み）
     * @param severity          重要度（CRITICAL / HIGH）
     * @param occurrenceCount   発生回数
     * @param affectedUserCount 影響ユーザー数
     * @param since             初回発生日時
     */
    public record IncidentSuggestion(
            String pagePattern,
            String severity,
            long occurrenceCount,
            long affectedUserCount,
            LocalDateTime since
    ) {}

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
     * エラーレポートを検索する（ステータス・重要度・日付範囲・SLA超過でフィルタ）。
     *
     * @param status      ステータス文字列（nullable）
     * @param severity    重要度文字列（nullable）
     * @param from        開始日（nullable）
     * @param to          終了日（nullable）
     * @param overdueOnly true の場合、SLA超過かつ未対応のレポートのみ返す
     * @param pageable    ページング情報
     * @return ページングされたエラーレポート
     */
    public Page<ErrorReportEntity> search(String status, String severity,
                                           LocalDate from, LocalDate to,
                                           boolean overdueOnly, Pageable pageable) {
        // F10.6 Phase 10-δ — overdueOnly=true のとき他のフィルタより優先
        if (overdueOnly) {
            List<ErrorReportStatus> activeStatuses = List.of(
                ErrorReportStatus.NEW,
                ErrorReportStatus.INVESTIGATING,
                ErrorReportStatus.REOPENED);
            return errorReportRepository.findOverdueByStatusIn(
                LocalDateTime.now(), activeStatuses, pageable);
        }

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
     * 後方互換性維持のためのオーバーロード（overdueOnly=false として委譲）。
     */
    public Page<ErrorReportEntity> search(String status, String severity,
                                           LocalDate from, LocalDate to, Pageable pageable) {
        return search(status, severity, from, to, false, pageable);
    }

    /**
     * URL からパス部分を抽出する。
     * 不透明URI（"health:backend" 等）は getPath() が null を返すため、その場合は元の url をそのまま返す。
     */
    private String extractPath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path != null ? path : url;
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
