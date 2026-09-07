package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftBudgetRetryExecutor} の単体テスト（Issue #2990 L13 で新設）。
 *
 * <p><b>是正前は本クラスの単体テストが 1 本も存在しなかった</b>。
 * {@code ShiftBudgetFailedEventServiceTest} も {@code ShiftBudgetRetryBatchJobTest} も
 * {@code ShiftBudgetRetryExecutor} をモックで差し替えており、リトライ 3 種の分岐も
 * 通知の再送経路も一度も実行されていなかった。L13 の欠陥
 * （通知の失敗が着手マーク・FAILED 化を巻き戻す）が長く見えていなかった一因である。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetRetryExecutor 単体テスト")
class ShiftBudgetRetryExecutorTest {

    private static final Long ALLOCATION_ID = 42L;

    @Mock
    private ShiftBudgetFailedEventRepository repository;
    @Mock
    private ThresholdAlertEvaluationService thresholdAlertEvaluationService;
    @Mock
    private ShiftBudgetNotificationResendService notificationResendService;

    private ShiftBudgetRetryExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ShiftBudgetRetryExecutor(
                repository, thresholdAlertEvaluationService, notificationResendService,
                new ObjectMapper());
    }

    private ShiftBudgetFailedEventEntity entity(ShiftBudgetFailedEventType type,
                                                Long sourceId, String payload) {
        return ShiftBudgetFailedEventEntity.builder()
                .organizationId(1L)
                .eventType(type)
                .sourceId(sourceId)
                .payload(payload)
                .retryCount(0)
                .status(ShiftBudgetFailedEventStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("NOTIFICATION_SEND 成功 → 再送サービスへ payload を渡し SUCCEEDED になる")
    void 通知再送成功() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.NOTIFICATION_SEND, ALLOCATION_ID,
                "{\"user_ids\":[10,11],\"type\":\"SHIFT_BUDGET_THRESHOLD_ALERT\",\"title\":\"t\","
                        + "\"body\":\"b\",\"title_key\":\"k.title\",\"body_key\":\"k.body80\","
                        + "\"threshold_percent\":80,"
                        + "\"source_type\":\"SHIFT_BUDGET_ALLOCATION\","
                        + "\"source_id\":42,\"scope_id\":1,\"action_url\":\"/u\"}");

        boolean result = executor.execute(e);

        assertThat(result).isTrue();
        assertThat(e.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.SUCCEEDED);
        // Issue #2908: 保存済みの ja 固定文字列ではなく i18n キーと閾値を渡すこと。
        verify(notificationResendService).resend(
                eq(List.of(10L, 11L)), eq("SHIFT_BUDGET_THRESHOLD_ALERT"),
                eq("k.title"), eq("k.body80"), eq(80),
                eq("t"), eq("b"),
                eq("SHIFT_BUDGET_ALLOCATION"), eq(42L), eq(1L), eq("/u"));
    }

    @Test
    @DisplayName("NOTIFICATION_SEND 失敗 → FAILED として記録し retry_count が積み上がる（rollback で消えない）")
    void 通知再送失敗でも記録が残る() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.NOTIFICATION_SEND, ALLOCATION_ID,
                "{\"user_ids\":[10],\"title\":\"t\",\"body\":\"b\",\"title_key\":\"k.title\","
                        + "\"body_key\":\"k.body80\",\"threshold_percent\":80,"
                        + "\"source_id\":42,\"scope_id\":1}");
        willThrow(new IllegalStateException("Notification resend failed for 1/1 recipients: [10]"))
                .given(notificationResendService).resend(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        boolean result = executor.execute(e);

        assertThat(result).isFalse();
        assertThat(e.getRetryCount()).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.PENDING);
        assertThat(e.getErrorMessage()).contains("Notification resend failed");
        // 着手マーク（saveAndFlush）と結果記録（save）の両方が呼ばれていること。
        verify(repository, times(1)).saveAndFlush(e);
        verify(repository, times(1)).save(e);
    }

    @Test
    @DisplayName("NOTIFICATION_SEND payload に user_ids が無い → 再送を呼ばず FAILED（空・0件の攻め口）")
    void 通知再送_受信者なしペイロード() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.NOTIFICATION_SEND, ALLOCATION_ID, "{\"user_ids\":[]}");

        assertThat(executor.execute(e)).isFalse();
        assertThat(e.getErrorMessage()).contains("no user_ids");
        verifyNoInteractions(notificationResendService);
    }

    @Test
    @DisplayName("Issue #2908: payload に i18n キーが無い → 再送せず FAILED（ja 固定へ暗黙フォールバックしない）")
    void 通知再送_i18nキー欠落() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.NOTIFICATION_SEND, ALLOCATION_ID,
                "{\"user_ids\":[10],\"title\":\"t\",\"body\":\"b\"}");

        assertThat(executor.execute(e)).isFalse();
        assertThat(e.getErrorMessage()).contains("title_key");
        verifyNoInteractions(notificationResendService);
    }

    @Test
    @DisplayName("THRESHOLD_ALERT → 閾値再評価へ委譲し SUCCEEDED になる")
    void 閾値再評価() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.THRESHOLD_ALERT, ALLOCATION_ID, "{}");

        assertThat(executor.execute(e)).isTrue();
        verify(thresholdAlertEvaluationService).evaluateAndTrigger(ALLOCATION_ID);
        assertThat(e.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("THRESHOLD_ALERT で source_id が null → FAILED（null の攻め口）")
    void 閾値再評価_sourceIdなし() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.THRESHOLD_ALERT, null, "{}");

        assertThat(executor.execute(e)).isFalse();
        assertThat(e.getErrorMessage()).contains("no source_id");
        verifyNoInteractions(thresholdAlertEvaluationService);
    }

    @Test
    @DisplayName("WORKFLOW_START → payload の allocation_id で閾値再評価へ委譲する")
    void ワークフロー再開始() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.WORKFLOW_START, 900L, "{\"allocation_id\":42}");

        assertThat(executor.execute(e)).isTrue();
        verify(thresholdAlertEvaluationService).evaluateAndTrigger(ALLOCATION_ID);
    }

    @Test
    @DisplayName("MAX_RETRY 到達で EXHAUSTED（境界値の攻め口）")
    void 上限到達でEXHAUSTED() {
        ShiftBudgetFailedEventEntity e = ShiftBudgetFailedEventEntity.builder()
                .organizationId(1L)
                .eventType(ShiftBudgetFailedEventType.THRESHOLD_ALERT)
                .sourceId(null)
                .payload("{}")
                .retryCount(ShiftBudgetRetryExecutor.MAX_RETRY - 1)
                .status(ShiftBudgetFailedEventStatus.PENDING)
                .build();

        assertThat(executor.execute(e)).isFalse();
        assertThat(e.getRetryCount()).isEqualTo(ShiftBudgetRetryExecutor.MAX_RETRY);
        assertThat(e.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.EXHAUSTED);
        assertThat(executor.isTerminal(e.getStatus())).isTrue();
    }

    @Test
    @DisplayName("CONSUMPTION_RECORD は自動再実行せず即 EXHAUSTED（手動補正運用）")
    void 消化記録は手動補正運用() {
        ShiftBudgetFailedEventEntity e = entity(
                ShiftBudgetFailedEventType.CONSUMPTION_RECORD, 7L, "{}");

        assertThat(executor.execute(e)).isFalse();
        assertThat(e.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.EXHAUSTED);
        verifyNoInteractions(thresholdAlertEvaluationService);
        verifyNoInteractions(notificationResendService);
    }
}
