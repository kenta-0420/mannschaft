package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ActiveIncidentResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportStatsResponse;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * エラーレポートの作成・重複集約・検索を担当するサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ErrorReportService {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportNotifier errorReportNotifier;
    private final StringRedisTemplate redisTemplate;

    /**
     * エラーレポートを受信し、重複集約または新規作成する。
     *
     * @param request   エラーレポートリクエスト
     * @param ipAddress 送信元IPアドレス
     * @return 作成または更新されたエラーレポートエンティティ
     */
    public ErrorReportEntity createOrAggregate(ErrorReportRequest request, String ipAddress) {
        String pagePath = extractPath(request.getPageUrl());
        String normalized = normalizeForHash(request.getErrorMessage());
        String errorHash = sha256(normalized + "|" + pagePath);

        // stack_trace を先頭2000文字に切り捨て
        String stackTrace = request.getStackTrace();
        if (stackTrace != null && stackTrace.length() > 2000) {
            stackTrace = stackTrace.substring(0, 2000);
        }

        Optional<ErrorReportEntity> existing = errorReportRepository.findByErrorHash(errorHash);

        if (existing.isPresent()) {
            ErrorReportEntity report = existing.get();

            if (report.getStatus() == ErrorReportStatus.RESOLVED) {
                // リグレッション: REOPENED に変更
                report.reopen(request.getOccurredAt());
                int affectedCount = trackAffectedUser(errorHash, request.getUserId());
                if (affectedCount > 0) {
                    report.setAffectedUserCount(affectedCount);
                }
                if (request.getUserComment() != null) {
                    report.setLatestUserComment(request.getUserComment());
                }
                errorReportNotifier.notifyRegression(report);
                log.info("エラーレポートリグレッション検知: id={}, hash={}", report.getId(), errorHash);
                return report;
            }

            if (report.getStatus() != ErrorReportStatus.IGNORED) {
                // 通常の重複集約
                ErrorReportSeverity oldSeverity = report.getSeverity();
                errorReportRepository.incrementOccurrence(errorHash, request.getOccurredAt(), request.getUserComment());

                // clearAutomatically = true により永続化コンテキストは自動クリア済み
                ErrorReportEntity updated = errorReportRepository.findByErrorHash(errorHash).orElseThrow();
                ErrorReportSeverity newSeverity = updated.getSeverity();

                // 影響ユーザー数追跡
                int affectedCount = trackAffectedUser(errorHash, request.getUserId());
                if (affectedCount > 0) {
                    updated.setAffectedUserCount(affectedCount);
                }

                // affected_user_count による severity 補正
                newSeverity = adjustSeverity(updated, affectedCount);
                if (newSeverity != updated.getSeverity()) {
                    updated.setSeverity(newSeverity);
                }

                // severity 昇格通知
                if (newSeverity.ordinal() > oldSeverity.ordinal()) {
                    errorReportNotifier.notifyEscalation(updated, oldSeverity, newSeverity);
                }

                log.info("エラーレポート重複集約: id={}, hash={}, count={}", updated.getId(), errorHash, updated.getOccurrenceCount());
                return updated;
            }
        }

        // IGNORED または該当なし → 新規作成
        ErrorReportSeverity severity = determineSeverity(request.getPageUrl(), request.getErrorMessage());
        Long organizationId = resolveOrganizationId(request.getUserId());

        ErrorReportEntity newReport = ErrorReportEntity.builder()
                .errorMessage(request.getErrorMessage())
                .stackTrace(stackTrace)
                .pageUrl(request.getPageUrl())
                .userAgent(request.getUserAgent())
                .userComment(request.getUserComment())
                .userId(request.getUserId())
                .organizationId(organizationId)
                .requestId(request.getRequestId())
                .ipAddress(ipAddress)
                .occurredAt(request.getOccurredAt())
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash(errorHash)
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(request.getOccurredAt())
                .lastOccurredAt(request.getOccurredAt())
                .latestUserComment(request.getUserComment())
                .build();

        ErrorReportEntity saved = errorReportRepository.save(newReport);

        // 影響ユーザー追跡
        trackAffectedUser(errorHash, request.getUserId());

        // 新規作成時の通知
        if (severity.ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
            errorReportNotifier.notifySlack(saved);
            errorReportNotifier.notifySystemAdmins(saved);
        }

        log.info("エラーレポート新規作成: id={}, hash={}, severity={}", saved.getId(), errorHash, severity);
        return saved;
    }

    /**
     * エラーレポートのステータスを更新する。
     *
     * @param id      エラーレポートID
     * @param request 更新リクエスト
     * @param adminId 管理者ユーザーID
     * @return 更新されたエラーレポートエンティティ
     */
    public ErrorReportEntity updateStatus(Long id, ErrorReportUpdateRequest request, Long adminId) {
        ErrorReportEntity report = errorReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));

        ErrorReportStatus newStatus = request.getStatus() != null
                ? ErrorReportStatus.valueOf(request.getStatus()) : null;
        ErrorReportSeverity newSeverity = request.getSeverity() != null
                ? ErrorReportSeverity.valueOf(request.getSeverity()) : null;

        if (newStatus != null) {
            report.setStatus(newStatus);
        }
        if (newSeverity != null) {
            report.setSeverity(newSeverity);
        }
        if (request.getAdminNote() != null) {
            report.setAdminNote(request.getAdminNote());
        }

        if (newStatus == ErrorReportStatus.RESOLVED) {
            report.resolve(adminId);
            // 報告者通知（user_id 非NULL時）
            if (report.getUserId() != null) {
                errorReportNotifier.notifyResolution(report);
            }
        }

        log.info("エラーレポートステータス更新: id={}, status={}, adminId={}", id, newStatus, adminId);
        return report;
    }

    /**
     * エラーレポートを一括更新する。RESOLVED/IGNORED のみ許可。
     *
     * @param request 一括更新リクエスト
     * @return 更新件数
     */
    public int bulkUpdate(ErrorReportBulkUpdateRequest request) {
        ErrorReportStatus status = ErrorReportStatus.valueOf(request.getStatus());
        if (status != ErrorReportStatus.RESOLVED
                && status != ErrorReportStatus.IGNORED) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_INVALID_STATUS_TRANSITION);
        }
        if (request.getIds().size() > 100) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_BULK_LIMIT_EXCEEDED);
        }

        List<ErrorReportEntity> reports = errorReportRepository.findAllById(request.getIds());
        for (ErrorReportEntity report : reports) {
            report.setStatus(status);
            if (status == ErrorReportStatus.RESOLVED) {
                report.resolve(null);
            }
        }

        log.info("エラーレポート一括更新: count={}, status={}", reports.size(), status);
        return reports.size();
    }

    /**
     * エラーレポート統計情報を取得する。
     *
     * @return 統計レスポンス
     */
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
     * エラーメッセージを正規化してハッシュの重複検知精度を高める。
     * 元の error_message はそのまま DB に保存し、正規化はハッシュ計算時のみ使用する。
     */
    private String normalizeForHash(String errorMessage) {
        return errorMessage
                .replaceAll("'[^']*'", "'?'")           // 'name' → '?'
                .replaceAll("\"[^\"]*\"", "\"?\"")       // "name" → "?"
                .replaceAll("\\b\\d+\\b", "N")           // 42 → N
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "UUID");
    }

    /**
     * user_id から organization_id をルックアップする。
     */
    private Long resolveOrganizationId(Long userId) {
        if (userId == null) return null;
        return errorReportRepository
                .findOrganizationIdByUserId(userId)
                .orElse(null);
    }

    /**
     * 影響ユーザーを Valkey SET で追跡し、ユニークユーザー数を返す。
     */
    private int trackAffectedUser(String errorHash, Long userId) {
        if (userId == null) return -1;
        String key = "error-report:affected:" + errorHash;
        redisTemplate.opsForSet().add(key, userId.toString());
        redisTemplate.expire(key, Duration.ofDays(90));
        Long size = redisTemplate.opsForSet().size(key);
        return size != null ? size.intValue() : -1;
    }

    /**
     * 新規作成時の severity 自動判定。
     */
    private ErrorReportSeverity determineSeverity(String pageUrl, String errorMessage) {
        if (pageUrl != null && (pageUrl.contains("/checkout") || pageUrl.contains("/payment"))) {
            return ErrorReportSeverity.HIGH;
        }
        if (errorMessage != null && errorMessage.contains("ChunkLoadError")) {
            return ErrorReportSeverity.LOW;
        }
        return ErrorReportSeverity.MEDIUM;
    }

    /**
     * affected_user_count による severity 補正。
     */
    private ErrorReportSeverity adjustSeverity(ErrorReportEntity report, int affectedCount) {
        ErrorReportSeverity severity = report.getSeverity();

        // occurrence_count >= 50 かつ affected_user_count <= 1 → CRITICAL → HIGH に据え置き
        if (report.getOccurrenceCount() >= 50 && affectedCount <= 1
                && severity == ErrorReportSeverity.CRITICAL) {
            return ErrorReportSeverity.HIGH;
        }

        // affected_user_count >= 20 かつ severity が MEDIUM → HIGH に昇格
        if (affectedCount >= 20 && severity == ErrorReportSeverity.MEDIUM) {
            return ErrorReportSeverity.HIGH;
        }

        return severity;
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

    /**
     * SHA-256 ハッシュを計算する。
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 文字列を指定長に切り詰める。
     */
    static String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }
}
