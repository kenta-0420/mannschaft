package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * F08.7 Phase 10-β: 失敗イベントの再実行アダプタ。
 *
 * <p>{@link ShiftBudgetFailedEventEntity#getEventType()} ごとに、
 * 対応する Service / Helper を呼び出して元処理を再試行する。</p>
 *
 * <p>サービス間の循環依存（{@link ShiftBudgetFailedEventService} ↔ 再実行対象の各 Service）を
 * 切るため、本クラスを別 Component として分離している。</p>
 *
 * <p>各実行は独立トランザクション ({@link Propagation#REQUIRES_NEW}) で動かし、
 * 1 件の失敗が他のリトライ処理を巻き戻さないようにする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetRetryExecutor {

    /** リトライ上限。{@link ShiftBudgetFailedEventEntity#markFailed} と整合させる。 */
    public static final int MAX_RETRY = 3;

    private final ShiftBudgetFailedEventRepository repository;
    private final ThresholdAlertEvaluationService thresholdAlertEvaluationService;
    private final ShiftBudgetNotificationResendService notificationResendService;
    private final ObjectMapper objectMapper;

    /**
     * 1 件の失敗イベントを再実行する。
     *
     * @return 成功 true / 失敗（再試行待ち or EXHAUSTED）false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(ShiftBudgetFailedEventEntity entity) {
        // 着手マーク（PENDING → RETRYING + retry_count++ + last_retried_at セット）
        entity.markRetrying();
        repository.saveAndFlush(entity);

        try {
            switch (entity.getEventType()) {
                case THRESHOLD_ALERT -> retryThresholdAlert(entity);
                case WORKFLOW_START -> retryWorkflowStart(entity);
                case NOTIFICATION_SEND -> retryNotificationSend(entity);
                case CONSUMPTION_RECORD, CONSUMPTION_CANCEL -> {
                    // 消化記録 / キャンセル hook は再実行ロジックが複雑（複数 (slot,user) を跨ぐ）。
                    // 現フェーズでは EXHAUSTED 化させて運用者が DB 直接補正する運用に倒す
                    // （誤再実行で重複 INSERT を起こすリスクを避ける）。
                    log.warn("F08.7 リトライ: {} は手動補正運用とするためバッチ再実行は EXHAUSTED 化: id={}, sourceId={}",
                            entity.getEventType(), entity.getId(), entity.getSourceId());
                    entity.markFailed("Auto-retry not supported for " + entity.getEventType()
                            + "; manual reconciliation required.", 0);
                    repository.save(entity);
                    return false;
                }
                default -> throw new IllegalStateException(
                        "Unknown event type: " + entity.getEventType());
            }
            entity.markSucceeded();
            repository.save(entity);
            log.info("F08.7 リトライ成功: id={}, eventType={}", entity.getId(), entity.getEventType());
            return true;
        } catch (Exception e) {
            log.error("F08.7 リトライ失敗: id={}, eventType={}, retryCount={}",
                    entity.getId(), entity.getEventType(), entity.getRetryCount(), e);
            entity.markFailed(formatError(e), MAX_RETRY);
            repository.save(entity);
            return false;
        }
    }

    private void retryThresholdAlert(ShiftBudgetFailedEventEntity entity) {
        Long allocationId = entity.getSourceId();
        if (allocationId == null) {
            throw new IllegalStateException("THRESHOLD_ALERT failed event has no source_id");
        }
        thresholdAlertEvaluationService.evaluateAndTrigger(allocationId);
    }

    private void retryWorkflowStart(ShiftBudgetFailedEventEntity entity) {
        // WORKFLOW_START のリトライは閾値再評価と同じ allocation 経路で行う。
        // tryStartWorkflow は既に発火済 alert に対しては UNIQUE で skip されるため冪等。
        Map<String, Object> payload = parsePayload(entity);
        Long allocationId = toLong(payload.get("allocation_id"));
        if (allocationId == null) {
            throw new IllegalStateException("WORKFLOW_START payload has no allocation_id");
        }
        thresholdAlertEvaluationService.evaluateAndTrigger(allocationId);
    }

    /**
     * {@code NOTIFICATION_SEND} 失敗イベントの再実行（Issue #2990 L13 で是正）。
     *
     * <p>再送そのものは {@link ShiftBudgetNotificationResendService}（監査済み例外・
     * {@code NOT_SUPPORTED}）へ委譲する。是正前はここで
     * {@code notificationHelper.notifyAll(...)} を直接呼んでおり、その下流の
     * {@code createNotification}（{@code REQUIRED}）が {@link #execute} のトランザクションへ
     * 参加していたため、通知の DB 例外が rollback-only を立てて
     * <b>着手マーク（{@code retry_count++}）も FAILED 化も commit 時に消えていた</b>
     * （＝リトライ回数が積み上がらず、バッチが同じイベントを無限に拾い続ける）。
     * 委譲先の {@code NOT_SUPPORTED} が本メソッドのトランザクションを中断するため、
     * いまは失敗しても {@link #execute} の catch が記録した結果が確実にコミットされる。</p>
     */
    private void retryNotificationSend(ShiftBudgetFailedEventEntity entity) {
        Map<String, Object> payload = parsePayload(entity);
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) payload.get("user_ids");
        if (rawIds == null || rawIds.isEmpty()) {
            throw new IllegalStateException("NOTIFICATION_SEND payload has no user_ids");
        }
        List<Long> userIds = rawIds.stream().map(this::toLong).filter(Objects::nonNull).toList();
        if (userIds.isEmpty()) {
            throw new IllegalStateException("NOTIFICATION_SEND payload has no parsable user_ids");
        }
        String type = String.valueOf(payload.getOrDefault("type", "SHIFT_BUDGET_THRESHOLD_ALERT"));
        String title = String.valueOf(payload.getOrDefault("title", ""));
        String body = String.valueOf(payload.getOrDefault("body", ""));
        String sourceType = String.valueOf(payload.getOrDefault("source_type", "SHIFT_BUDGET_ALLOCATION"));
        Long sourceId = toLong(payload.get("source_id"));
        Long scopeId = toLong(payload.get("scope_id"));
        String actionUrl = (String) payload.get("action_url");
        // Issue #2908: 再送は保存済みの ja 固定文字列ではなく i18n キーから組み立て直す
        // （title / body は運用ログ・フォレンジック用のフォールバックとしてのみ使う）。
        // JSON 往復で Integer / Long / Double に化けるため threshold_percent は toLong で正規化する。
        String titleKey = (String) payload.get("title_key");
        String bodyKey = (String) payload.get("body_key");
        Long thresholdPercent = toLong(payload.get("threshold_percent"));
        if (titleKey == null || bodyKey == null) {
            throw new IllegalStateException(
                    "NOTIFICATION_SEND payload has no title_key/body_key (Issue #2908)");
        }

        notificationResendService.resend(
                userIds, type, titleKey, bodyKey,
                thresholdPercent == null ? null : thresholdPercent.intValue(),
                title, body, sourceType, sourceId, scopeId, actionUrl);
    }

    private Map<String, Object> parsePayload(ShiftBudgetFailedEventEntity entity) {
        try {
            return objectMapper.readValue(
                    entity.getPayload(), new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse payload: " + e.getMessage(), e);
        }
    }

    private Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatError(Throwable t) {
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
        return msg.length() > 4000 ? msg.substring(0, 4000) : msg;
    }

    /** ステータス遷移の整合性検証用（テスト等）。 */
    public boolean isTerminal(ShiftBudgetFailedEventStatus status) {
        return status == ShiftBudgetFailedEventStatus.SUCCEEDED
                || status == ShiftBudgetFailedEventStatus.MANUAL_RESOLVED
                || status == ShiftBudgetFailedEventStatus.EXHAUSTED;
    }
}
