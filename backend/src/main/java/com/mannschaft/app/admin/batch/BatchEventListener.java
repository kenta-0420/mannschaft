package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAsyncExecutor;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F10.X 第一陣 — バッチ実行イベントの受信側。SYSTEM_ADMIN 通知と F12.5 起票を行う。
 *
 * <p>{@link BatchCompletedEvent} は SYSTEM_ADMIN への通知タブ配信のみ（priority=LOW）。
 * {@link BatchFailedEvent} は F12.5 への error_reports 起票（severity=HIGH）に加えて
 * SYSTEM_ADMIN への通知タブ配信（priority=HIGH）を行う。</p>
 *
 * <h2>トランザクション境界（Issue #2997 G7-b / CMP-260827-1152、番人 NotificationTransactionBoundaryGuardTest）</h2>
 * <p>{@code @Async("event-pool")} + {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}
 * で受け、管理者ごとに {@link NotificationDeliveryRunner#sendOne}（1 件ごと {@code REQUIRES_NEW}）で配送する。
 * 1 人の配送失敗が他の管理者に波及しない。</p>
 *
 * <p>{@link BatchExecutionAspect} が {@code @Order(20)} で本体 TX の<b>外側</b>に居るため、
 * 本イベントは本体 TX の commit / rollback が確定した後に publish される。その時点では待つべき TX が無い
 * （{@code fallbackExecution = true} が即時実行する）。本体の commit 失敗も Aspect の例外経路に入り、
 * 失敗イベントになる。保証範囲: エンドポイント自身の TX が確定した後に必ず通知する。
 * 外側の TX から呼ばれた場合は、その外側 TX の commit 後の通知になる（外側が rollback すれば通知されない）が、
 * 現行の呼出元（スケジューラ・SystemAdminBatchController → BatchEndpointRegistry）は外側 TX を持たない。</p>
 *
 * <p>AsyncConfig の MDC TaskDecorator により requestId 等のコンテキストは引き継がれる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchEventListener {

    /** バッチ完了通知の type 文字列（既存の汎用 type を流用、systemAdmin 向け固定）。 */
    static final String NOTIFICATION_TYPE_BATCH_COMPLETED = "BATCH_COMPLETED";
    /** バッチ失敗通知の type 文字列。 */
    static final String NOTIFICATION_TYPE_BATCH_FAILED = "BATCH_FAILED";
    /** 失敗通知本文に載せる原因メッセージの最大長。 */
    static final int CAUSE_MAX_LENGTH = 200;
    /** 原因が無いときの本文。 */
    static final String NO_CAUSE = "(no cause)";

    private static final String SOURCE_TYPE = "BATCH_JOB_LOG";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserRoleRepository userRoleRepository;
    private final ErrorReportAsyncExecutor errorReportAsyncExecutor;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。バッチ完了・失敗の運用通知であり、閉栓対象の機能ではなく運用基盤に属する。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCompleted(BatchCompletedEvent event) {
        List<Long> adminIds;
        try {
            adminIds = userRoleRepository.findSystemAdminUserIds();
        } catch (Exception ex) {
            log.error("BatchCompletedEvent: SYSTEM_ADMIN の取得に失敗（通知なし）: name={}", event.name(), ex);
            return;
        }
        int processedCount = event.log() != null && event.log().getProcessedCount() != null
                ? event.log().getProcessedCount() : 0;
        Long sourceId = event.log() != null ? event.log().getId() : null;
        Map<Long, String> locales = resolveLocales(adminIds, event.name());
        for (Long adminUserId : adminIds) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.admin.batchCompleted.title",
                        new Object[]{event.name()}, "バッチ完了: " + event.name(), locale);
                String body = messageSource.getMessage(
                        "notification.admin.batchCompleted.body",
                        new Object[]{processedCount}, "処理件数: " + processedCount + " 件", locale);
                notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                        adminUserId,
                        NOTIFICATION_TYPE_BATCH_COMPLETED,
                        NotificationPriority.LOW,
                        title,
                        body,
                        SOURCE_TYPE,
                        sourceId,
                        NotificationScopeType.SYSTEM,
                        null,
                        actionUrl(sourceId),
                        null));
            } catch (Exception ex) {
                // 1 人の失敗で残りの管理者を諦めない（被害半径の分離）。
                log.error("BatchCompletedEvent 通知配信失敗（当該管理者のみスキップして継続）: name={}, adminUserId={}",
                        event.name(), adminUserId, ex);
            }
        }
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。バッチ完了・失敗の運用通知であり、閉栓対象の機能ではなく運用基盤に属する。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFailed(BatchFailedEvent event) {
        // F12.5 起票（severity=HIGH 固定。バッチ失敗は本番では必ず HIGH 以上で扱う）
        try {
            errorReportAsyncExecutor.recordBackendException(
                    event.cause(),
                    "batch://" + event.name(),
                    "system-batch",
                    null,
                    null,
                    ErrorReportSeverity.HIGH);
        } catch (Exception ex) {
            log.warn("バッチ失敗の F12.5 起票に失敗: name={}", event.name(), ex);
        }

        // SYSTEM_ADMIN への通知配信
        List<Long> adminIds;
        try {
            adminIds = userRoleRepository.findSystemAdminUserIds();
        } catch (Exception ex) {
            log.error("BatchFailedEvent: SYSTEM_ADMIN の取得に失敗（通知なし）: name={}", event.name(), ex);
            return;
        }
        // causeMessage は例外メッセージそのもの（動的な技術的文字列）であり、固定日本語リテラルではないため
        // i18n 対象外（AC-1 は notify/createNotification へ直接渡る「日本語リテラル」を対象とする）。
        String causeMessage = event.cause() != null && event.cause().getMessage() != null
                ? event.cause().getMessage()
                : event.cause() != null ? event.cause().getClass().getSimpleName() : NO_CAUSE;
        String truncatedCause = truncate(causeMessage, CAUSE_MAX_LENGTH);
        Long sourceId = event.log() != null ? event.log().getId() : null;
        Map<Long, String> locales = resolveLocales(adminIds, event.name());
        for (Long adminUserId : adminIds) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.admin.batchFailed.title",
                        new Object[]{event.name()}, "バッチ失敗: " + event.name(), locale);
                String body = messageSource.getMessage(
                        "notification.admin.batchFailed.body",
                        new Object[]{truncatedCause}, truncatedCause, locale);
                notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                        adminUserId,
                        NOTIFICATION_TYPE_BATCH_FAILED,
                        NotificationPriority.HIGH,
                        title,
                        body,
                        SOURCE_TYPE,
                        sourceId,
                        NotificationScopeType.SYSTEM,
                        null,
                        actionUrl(sourceId),
                        null));
            } catch (Exception ex) {
                log.error("BatchFailedEvent 通知配信失敗（当該管理者のみスキップして継続）: name={}, adminUserId={}",
                        event.name(), adminUserId, ex);
            }
        }
    }

    /**
     * 受信者ごとの locale を一括解決する（N+1 防止）。解決に失敗しても通知自体は止めず、
     * 既定ロケール（ja）で配送する。通知の本質（管理者に知らせる）を locale 解決の失敗で落とさないため。
     */
    private Map<Long, String> resolveLocales(List<Long> adminIds, String batchName) {
        try {
            return userLocaleCache.getLocales(adminIds);
        } catch (Exception ex) {
            log.warn("バッチ通知の locale 一括解決に失敗（ja で配送する）: name={}", batchName, ex);
            return Map.of();
        }
    }

    private static String actionUrl(Long sourceId) {
        return "/system-admin/batch-jobs" + (sourceId != null ? "/" + sourceId : "");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
