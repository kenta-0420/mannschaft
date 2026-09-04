package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.SlaPolicy;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * エラーレポートの作成・重複集約・ステータス更新を担当するコアサービス。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ErrorReportService {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportNotifier errorReportNotifier;
    private final StringRedisTemplate redisTemplate;
    /** F12.5 Phase 2-C — CRITICAL/HIGH 新規 / REOPEN 時に AI 即時分析をキックする。 */
    /** Issue #2990 L4: AI 即時分析の起動は Dispatcher 経由（プロキシ境界を跨がせるため）。 */
    private final ErrorReportAiAnalysisDispatcher aiAnalysisDispatcher;
    /**
     * F10.5/F10.6 Phase 10-β 後続-⑥ — @Async プロキシバイパス回避のため、
     * バックエンド例外の非同期記録処理は別 Bean に切り出している。
     * 同一クラス内の @Async self-invocation は Spring AOP プロキシをバイパスするため、
     * 本サービスから DI 経由で Executor を呼ぶことで proxy を確実に通す。
     */
    private final ErrorReportAsyncExecutor asyncExecutor;

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
                // F12.5 Phase 2 — リグレッション時に workflow_stage / assignee_id をリセット
                report.setWorkflowStage(null);
                report.setAssigneeId(null);
                // F10.6 Phase 10-δ — REOPENED 時に SLA 期限を再設定し、通知済みフラグをリセット
                report.setSlaDueAt(SlaPolicy.calcDueAt(report.getSeverity(), LocalDateTime.now()));
                redisTemplate.delete("error-report:sla-overdue-notified:" + report.getId());
                int affectedCount = trackAffectedUser(errorHash, request.getUserId());
                if (affectedCount > 0) {
                    report.setAffectedUserCount(affectedCount);
                }
                if (request.getUserComment() != null) {
                    report.setLatestUserComment(request.getUserComment());
                }
                errorReportNotifier.notifyRegression(report);
                // F12.5 Phase 2-C — REOPEN かつ severity HIGH 以上なら AI 即時分析をキック
                if (report.getSeverity().ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
                    aiAnalysisDispatcher.analyzeAfterCommit(report.getId(), null);
                }
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
        LocalDateTime now = LocalDateTime.now();

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
                .slaDueAt(SlaPolicy.calcDueAt(severity, now))
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
            // F12.5 Phase 2-C — 新規 HIGH/CRITICAL は AI 即時分析をキック
            aiAnalysisDispatcher.analyzeAfterCommit(saved.getId(), null);
        }

        log.info("エラーレポート新規作成: id={}, hash={}, severity={}", saved.getId(), errorHash, severity);
        return saved;
    }

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — バックエンド由来の例外を error_reports に記録する。
     *
     * <p>{@link com.mannschaft.app.common.GlobalExceptionHandler} および
     * {@link com.mannschaft.app.config.RequestLoggingFilter}（10秒超のスローリクエスト）から呼ばれる。
     * 既存の {@link #createOrAggregate(ErrorReportRequest, String)} と同じ集約ロジック（SHA-256 ハッシュ）を
     * 使い、繰り返し発生時は occurrence_count をインクリメントする。</p>
     *
     * <p>記録対象は設計書 F10.6 §5.2 の方針に従う:
     * バリデーションエラー（4xx）/ ビジネスエラー（4xx）は呼び出し側で除外すること。</p>
     *
     * <p>F10.5/F10.6 Phase 10-β 後続-⑥:
     * 同期で {@link HttpServletRequest} から各種属性を抽出し、抽出後の値を {@link ErrorReportAsyncExecutor}
     * へ渡して非同期記録を依頼する（HttpServletRequest は非同期スレッドで参照不可のため）。
     * Executor を別 Bean として切り出すことで Spring AOP プロキシを確実に通し、
     * @Async が proxy バイパスで無視される問題を根治した。</p>
     *
     * @param ex       記録対象の例外
     * @param request  HTTP リクエスト（pageUrl / userAgent / ipAddress 抽出用、NULL 可）
     * @param severity 重要度
     * @return 互換のため常に {@code null} を返す（実際の記録は非同期スレッドで行われ、戻り値は取得不可）
     */
    public ErrorReportEntity recordBackendException(Throwable ex,
                                                    HttpServletRequest request,
                                                    ErrorReportSeverity severity) {
        // HttpServletRequest は非同期スレッドへ持ち越せない（リクエストライフサイクル外で属性が無効化される）
        // ため、呼び出し側スレッドで属性を抽出してから Executor へ渡す。
        String pageUrl = null;
        String userAgent = null;
        String ipAddress = null;
        if (request != null) {
            pageUrl = resolvePageUrl(request);
            userAgent = request.getHeader("User-Agent");
            String forwarded = request.getHeader("X-Forwarded-For");
            ipAddress = (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : request.getRemoteAddr();
        }
        // requestId は MDC から呼び出し側スレッドで取得しておく
        // （@Async + MdcTaskDecorator で伝播されるが、ここで明示的に拾うことで疎通を保証する）
        String requestId = MDC.get("requestId");
        asyncExecutor.recordBackendException(ex, pageUrl, userAgent, ipAddress, requestId, severity);
        return null;
    }

    /**
     * F10.5/F10.6 Phase 10-β 後続フォローアップ — pageUrl 等を直接受け取るオーバーロード。
     *
     * <p>{@link com.mannschaft.app.config.RequestLoggingFilter} のスローリクエスト検知時など、
     * URI テンプレート化済みの {@code pageUrl} を渡したいケースで使う。
     * {@code HttpServletRequest} を {@code null} で渡す代わりにこちらを使うと、
     * {@code error_reports.page_url} が "backend" 固定にならず実 path で保存される。</p>
     *
     * <p>F10.5/F10.6 Phase 10-β 後続-⑥: {@link ErrorReportAsyncExecutor} へ委譲する薄いラッパー。
     * Executor 側で {@code @Async} が proxy 経由で適用され、別スレッドで実行される。</p>
     *
     * @param ex         記録対象の例外
     * @param pageUrl    pageUrl（URI テンプレートまたは raw path、NULL 可）
     * @param userAgent  User-Agent ヘッダ（NULL 可）
     * @param ipAddress  クライアント IP（X-Forwarded-For 優先、NULL 可）
     * @param requestId  MDC requestId（NULL 可、@Async 伝播されない場合のフォールバック）
     * @param severity   重要度
     */
    public void recordBackendException(Throwable ex,
                                        String pageUrl,
                                        String userAgent,
                                        String ipAddress,
                                        String requestId,
                                        ErrorReportSeverity severity) {
        asyncExecutor.recordBackendException(ex, pageUrl, userAgent, ipAddress, requestId, severity);
    }

    /**
     * F10.6 Phase 10-γ-① — インフラコンポーネントの Health DOWN を error_reports に記録する。
     *
     * <p>component 固有の固定 error_hash（{@code sha256("HealthDown:" + component)}）で既存レコードを
     * 検索し、なければ新規作成、あれば occurrence_count をインクリメントする。
     * これにより同一コンポーネントの DOWN が繰り返されても同一レコードに集約される。</p>
     *
     * <p>DOWN 時に呼ばれ、返却された error_report_id を {@code componentToReportId} に保存する。
     * 復旧（DOWN→UP）時に {@code HEALTH_RECOVERED} アクティビティに紐付けるために使用する。</p>
     *
     * <p>本メソッドは {@code @Scheduled} スレッド（{@link com.mannschaft.app.health.HealthStatusListener}）
     * から同期的に呼ばれる。HealthStatusListener は healthドメインのため、errorreportドメインの
     * Service を呼ぶクロスドメイン依存になるが、インフラ横断的な性質上やむを得ない。
     * TODO: 将来的には HealthDownEvent を発行し errorreport ドメインが購読する形に分離予定。</p>
     *
     * @param component Health component 名（"db" / "redis" 等）
     * @return 作成または更新された error_report_id
     */
    @Transactional
    public Long findOrCreateHealthDownReport(String component) {
        String errorHash = sha256("HealthDown:" + component);
        LocalDateTime now = LocalDateTime.now();

        Optional<ErrorReportEntity> existing = errorReportRepository.findByErrorHash(errorHash);
        if (existing.isPresent()) {
            ErrorReportEntity report = existing.get();
            // 既存レコードを occurrence_count インクリメントして更新
            errorReportRepository.incrementOccurrence(errorHash, now, null);
            log.info("Health DOWN 集約: component={}, reportId={}", component, report.getId());
            return report.getId();
        }

        // 新規作成
        ErrorReportEntity newReport = ErrorReportEntity.builder()
                .errorMessage("Health DOWN: " + component)
                .pageUrl("health:" + component)
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.CRITICAL)
                .slaDueAt(SlaPolicy.calcDueAt(ErrorReportSeverity.CRITICAL, now))
                .errorHash(errorHash)
                .occurrenceCount(1)
                .affectedUserCount(0)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
        ErrorReportEntity saved = errorReportRepository.save(newReport);
        log.info("Health DOWN 新規記録: component={}, reportId={}", component, saved.getId());
        return saved.getId();
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
     * F10.5/F10.6 Phase 10-β 後続フォローアップ — HttpServletRequest から pageUrl を解決する。
     *
     * <p>Spring MVC の {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} 属性が
     * セットされていればそれ（URI テンプレート: {@code /api/v1/users/{id}}）を採用し、
     * フィルター段階等で未セットなら raw {@code requestURI} にフォールバックする。
     * これにより同一エンドポイントが ID 違いで連続発生しても集約キーが分裂しない。</p>
     */
    private String resolvePageUrl(HttpServletRequest request) {
        if (request == null) return null;
        Object pattern = request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) {
            return s;
        }
        String uri = request.getRequestURI();
        return (uri != null && !uri.isBlank()) ? uri : null;
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
     * ヘルス復旧（DOWN→UP）時にエラーレポートを自動解決する。
     * {@link com.mannschaft.app.health.HealthStatusListener} から呼ばれる。
     *
     * @param reportId エラーレポートID
     */
    @Transactional
    public void resolveHealthReport(Long reportId) {
        errorReportRepository.findById(reportId).ifPresent(report -> {
            if (report.getStatus() != ErrorReportStatus.RESOLVED) {
                report.resolve(null);
                log.info("ヘルス復旧によりエラーレポートを自動解決: id={}", reportId);
            }
        });
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
     * 文字列を指定長に切り詰める。
     * ErrorReportNotifier / ErrorReportWeeklySummaryService から静的に参照される。
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
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
}
