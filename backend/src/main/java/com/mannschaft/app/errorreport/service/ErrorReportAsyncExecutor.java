package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * F10.5/F10.6 Phase 10-β 後続-⑥ — バックエンド由来例外を error_reports に非同期で記録する実行 Bean。
 *
 * <p>本クラスは {@link ErrorReportService} から物理的に切り出された @Async 専用 Bean である。
 * Spring AOP の標準プロキシ方式では同一インスタンス内の self-invocation で {@code @Async}
 * アノテーションが無視されるため、{@code ErrorReportService} 自身の @Async メソッドを
 * {@code this.} 経由で呼んでも proxy をバイパスし、リクエスト処理スレッドで同期実行されてしまう。
 * これを回避するために別 Bean として切り出し、{@code ErrorReportService} から DI 経由で
 * 呼ぶことで proxy を通すように根治した。</p>
 *
 * <p>プロジェクト内の前例: {@link com.mannschaft.app.digest.service.DigestAsyncExecutor}</p>
 *
 * <p>循環依存を避けるため、本クラスは {@link ErrorReportService} を参照しない。
 * {@code error_reports} の集約ロジックに必要な {@link ErrorReportRepository} に直接依存し、
 * 独立して動作する。</p>
 *
 * <p>Issue #2990 L11: 通知は本クラスから直接発火しない。業務TX内では ID だけを載せた
 * 業務イベント（{@link ErrorReportRaisedEvent} 等）を publish し、
 * {@link ErrorReportNotificationListener} が {@code AFTER_COMMIT} で配送する。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ErrorReportAsyncExecutor {

    private final ErrorReportRepository errorReportRepository;
    /**
     * Issue #2990 L11 — 通知は業務TX内で発火せず、ID だけを載せた業務イベントを publish する。
     * 実配送は {@link ErrorReportNotificationListener} が {@code AFTER_COMMIT} で行う。
     */
    private final ApplicationEventPublisher eventPublisher;
    /**
     * F10.6 §5.6-③ — 集約バッファ。
     * バックエンド由来エラーの 2 通目以降は Slack 即時通知を抑制し、
     * 5 分毎の集約サマリ（{@link com.mannschaft.app.errorreport.batch.ErrorAggregationFlushBatch}）
     * に流す。
     */
    private final ErrorReportAggregator aggregator;

    /**
     * F10.5 Phase 10-β / F10.6 Phase 10-β-1 — バックエンド由来の例外を error_reports に非同期で記録する。
     *
     * <p>{@code @Async("event-pool")} により別スレッドで実行され、本体リクエストの応答を遅らせない。
     * MDC は {@link com.mannschaft.app.config.AsyncConfig.MdcTaskDecorator} で呼び出し側から伝播される。
     * 念のため呼び出し側で抽出した {@code requestId} を引数で受け取り、
     * MDC が空でも記録に残せるようフォールバックさせる。</p>
     *
     * @param ex         記録対象の例外
     * @param pageUrl    pageUrl（URI テンプレートまたは raw path、NULL 可）
     * @param userAgent  User-Agent ヘッダ（NULL 可）
     * @param ipAddress  クライアント IP（X-Forwarded-For 優先、NULL 可）
     * @param requestId  MDC requestId（NULL 可、@Async 伝播されない場合のフォールバック）
     * @param severity   重要度
     */
    // @Async("event-pool") により AOP プロキシ経由で呼ばれるため @Transactional が有効に機能する。
    // 内部の doRecordBackendException() は self-invocation（同一インスタンス呼び出し）なので
    // AOP プロキシを経由せず @Transactional が無視される。
    // 本メソッドにトランザクションを開始することで doRecordBackendException() が
    // 既存トランザクションに参加し、@Modifying な incrementOccurrence() が
    // TransactionRequiredException を発生させずに実行される。
    @Async("event-pool")
    @Transactional
    public void recordBackendException(Throwable ex,
                                        String pageUrl,
                                        String userAgent,
                                        String ipAddress,
                                        String requestId,
                                        ErrorReportSeverity severity) {
        try {
            doRecordBackendException(ex, pageUrl, userAgent, ipAddress, requestId, severity);
        } catch (Exception inner) {
            // 非同期スレッドで投げると拾える人がいないため、ここで握って構造化ログだけ残す。
            log.warn("非同期 recordBackendException 失敗: severity={}, ex={}",
                    severity, ex.getClass().getName(), inner);
        }
    }

    /**
     * 同期実行用の集約コア（テスト容易性のため戻り値を保持）。
     *
     * <p>{@link #recordBackendException} の本体実装。@Async プロキシ越しでは戻り値が取れないため、
     * 単体テストではこちらを直接呼んで集約ロジック（重複検知 / リグレッション / Slack 通知判定）を検証する。</p>
     *
     * @return 作成または更新されたエラーレポートエンティティ
     */
    @Transactional
    public ErrorReportEntity doRecordBackendException(Throwable ex,
                                                       String pageUrl,
                                                       String userAgent,
                                                       String ipAddress,
                                                       String requestId,
                                                       ErrorReportSeverity severity) {
        String exClassName = ex.getClass().getName();
        String errorMessage = ex.getMessage() != null
                ? exClassName + ": " + ex.getMessage()
                : exClassName;
        // error_message カラム上限 1000 字
        if (errorMessage.length() > 1000) {
            errorMessage = errorMessage.substring(0, 1000);
        }

        // ハッシュ対象: 例外クラス名 + 先頭スタックフレーム（メソッド単位の集約）
        String firstFrame = "";
        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null && stack.length > 0) {
            firstFrame = stack[0].toString();
        }
        String errorHash = sha256(exClassName + "|" + firstFrame);

        // pageUrl は呼び出し側で抽出済み（URI テンプレート / raw path / null）。
        // 設計書 F10.6 §5.2: スロー検知 / 想定外例外いずれの場合も実 path / テンプレートが入る想定。
        // NULL なら "backend" を入れる（バッチ・スケジューラ等から呼ばれた場合のフォールバック）。
        String resolvedPageUrl = (pageUrl != null && !pageUrl.isBlank()) ? pageUrl : "backend";
        // pageUrl カラム上限 2048
        if (resolvedPageUrl.length() > 2048) {
            resolvedPageUrl = resolvedPageUrl.substring(0, 2048);
        }
        String resolvedUserAgent = userAgent;
        if (resolvedUserAgent != null && resolvedUserAgent.length() > 500) {
            resolvedUserAgent = resolvedUserAgent.substring(0, 500);
        }
        String resolvedIpAddress = ipAddress;
        if (resolvedIpAddress != null && resolvedIpAddress.length() > 45) {
            resolvedIpAddress = resolvedIpAddress.substring(0, 45);
        }

        // requestId: 引数で渡されていればそちらを優先、無ければ MDC（@Async + MdcTaskDecorator で伝播される）から拾う
        String resolvedRequestId = (requestId != null && !requestId.isBlank())
                ? requestId
                : MDC.get("requestId");

        // stack_trace を 2000 字に切り詰め
        String stackTrace = renderStackTrace(ex);

        LocalDateTime now = LocalDateTime.now();

        Optional<ErrorReportEntity> existing = errorReportRepository.findByErrorHash(errorHash);
        if (existing.isPresent()) {
            ErrorReportEntity report = existing.get();

            if (report.getStatus() == ErrorReportStatus.RESOLVED) {
                report.reopen(now);
                report.setWorkflowStage(null);
                report.setAssigneeId(null);
                eventPublisher.publishEvent(new ErrorReportRegressionDetectedEvent(report.getId()));
                log.info("バックエンド例外リグレッション検知: id={}, hash={}, ex={}",
                        report.getId(), errorHash, exClassName);
                return report;
            }

            if (report.getStatus() != ErrorReportStatus.IGNORED) {
                ErrorReportSeverity oldSeverity = report.getSeverity();
                errorReportRepository.incrementOccurrence(errorHash, now, null);
                ErrorReportEntity updated = errorReportRepository.findByErrorHash(errorHash).orElseThrow();
                ErrorReportSeverity newSeverity = updated.getSeverity();
                if (newSeverity.ordinal() > oldSeverity.ordinal()) {
                    eventPublisher.publishEvent(new ErrorReportSeverityEscalatedEvent(
                            updated.getId(), oldSeverity, newSeverity));
                }
                // F10.6 §5.6-③ — 重複発火を集約バッファに蓄積（severity 昇格通知とは独立）。
                // 既存レポートの 2 回目以降の発火なので必ず BUFFERED 扱いになる想定。
                aggregator.addOccurrence(errorHash,
                        updated.getErrorMessage(), updated.getSeverity());
                log.info("バックエンド例外重複集約: id={}, hash={}, count={}, ex={}",
                        updated.getId(), errorHash, updated.getOccurrenceCount(), exClassName);
                return updated;
            }
        }

        // 新規作成
        ErrorReportEntity newReport = ErrorReportEntity.builder()
                .errorMessage(errorMessage)
                .stackTrace(stackTrace)
                .pageUrl(resolvedPageUrl)
                .userAgent(resolvedUserAgent)
                .userId(null)
                .organizationId(null)
                .requestId(resolvedRequestId)
                .ipAddress(resolvedIpAddress)
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash(errorHash)
                .occurrenceCount(1)
                .affectedUserCount(0)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
        ErrorReportEntity saved = errorReportRepository.save(newReport);

        // F10.6 §5.6-③ — 新規発火を集約バッファに記録。
        // FIRST_OCCURRENCE が返るのが期待値（同一プロセス内で初回発火）。
        // 2 通目以降のフロー（既存レコード or バッファ entry あり）は上の重複集約ブロックで処理済み。
        ErrorReportAggregator.AggregationResult aggResult = aggregator.addOccurrence(
                errorHash, saved.getErrorMessage(), severity);

        // HIGH 以上は Slack + SYSTEM_ADMIN 通知（フロント由来と同じ閾値）。
        // 集約バッファが BUFFERED を返した場合（極稀: 同じ error_hash の新規 ErrorReport 行が
        // 前の expire 内に再生成されたケース）は Slack 即時通知を抑制し、5 分毎の集約サマリ送りにする。
        // SYSTEM_ADMIN プッシュ通知は既存仕様通り送る（重要インシデントの埋没防止）。
        if (severity.ordinal() >= ErrorReportSeverity.HIGH.ordinal()) {
            eventPublisher.publishEvent(new ErrorReportRaisedEvent(
                    saved.getId(),
                    aggResult == ErrorReportAggregator.AggregationResult.FIRST_OCCURRENCE));
        }

        log.info("バックエンド例外記録: id={}, hash={}, severity={}, aggResult={}, ex={}",
                saved.getId(), errorHash, severity, aggResult, exClassName);
        return saved;
    }

    /**
     * Throwable のスタックトレースを文字列化し、2000 字までに切り詰める。
     */
    private static String renderStackTrace(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        sb.append(ex.getClass().getName());
        if (ex.getMessage() != null) {
            sb.append(": ").append(ex.getMessage());
        }
        sb.append('\n');
        StackTraceElement[] stack = ex.getStackTrace();
        if (stack != null) {
            for (StackTraceElement frame : stack) {
                sb.append("\tat ").append(frame.toString()).append('\n');
                if (sb.length() > 2000) break;
            }
        }
        if (sb.length() > 2000) {
            return sb.substring(0, 2000);
        }
        return sb.toString();
    }

    /**
     * SHA-256 ハッシュを計算する。
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
