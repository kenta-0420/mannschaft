package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftBudgetThresholdAlertNotificationListener} の単体テスト（Issue #2990 L13）。
 *
 * <p>i18n の検証群は是正前 {@code ThresholdAlertEvaluationServiceTest.NotificationI18n} が
 * 固定していた資産であり、通知の組み立てが本リスナーへ移ったので同じ内容をここへ移設した
 * （Issue #2715 CMP-055 ロットC-4 で確立した検証観点をそのまま維持する）。</p>
 *
 * <p><b>Codex 検分 P1-a</b>: 受信者単位の配送失敗が {@code NOTIFICATION_SEND} の failed event として
 * 残ることを固定する。残らなければ本 PR で作った再送の仕組みがそもそも起動せず、
 * 「alert は確定済みなのに通知は失われ、再送経路も無い」回復不能な状態になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetThresholdAlertNotificationListener 単体テスト")
class ShiftBudgetThresholdAlertNotificationListenerTest {

    private static final Long ALLOCATION_ID = 42L;
    private static final Long ORG_ID = 1L;
    private static final Long ALERT_ID = 900L;

    private static final Pattern JAPANESE_CHAR = Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private ShiftBudgetFailedEventService failedEventService;

    private ShiftBudgetThresholdAlertNotificationListener listener;

    @BeforeEach
    void setUp() {
        // 実物の MessageSource を使う（モックが引数をそのまま返す形だと鍵の欠落もフォーマット崩れも
        // 検出できないため。Issue #2715 CMP-055 ロットC-4 の方針を踏襲）。
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        listener = new ShiftBudgetThresholdAlertNotificationListener(
                notificationDeliveryRunner, userLocaleCache, failedEventService, messageSource);
    }

    private BudgetThresholdAlertTriggeredEvent event(int thresholdPercent, List<Long> recipients) {
        return new BudgetThresholdAlertTriggeredEvent(
                ALERT_ID, ALLOCATION_ID, ORG_ID, thresholdPercent, recipients);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> capturePayload() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private List<NotificationDeliveryRequest> captureRequests(int expectedCount) {
        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(expectedCount)).sendOne(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("受信者ごとに sendOne を呼び、locale 解決は 1 回だけ（N+1 防止）")
    void 受信者ごとに配送する() {
        given(userLocaleCache.getLocales(List.of(10L, 11L))).willReturn(Map.of(10L, "ja", 11L, "en"));

        listener.onBudgetThresholdAlertTriggered(event(80, List.of(10L, 11L)));

        List<NotificationDeliveryRequest> requests = captureRequests(2);
        assertThat(requests).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(10L, 11L);
        assertThat(requests.get(0).title()).isEqualTo("シフト予算 警告 (80%)");
        assertThat(requests.get(0).body()).isEqualTo("予算 80% に到達しました");
        assertThat(requests.get(1).title()).isEqualTo("Shift budget warning (80%)");
        assertThat(requests.get(1).body()).isEqualTo("Budget has reached 80%");
        assertThat(requests.get(0).sourceType()).isEqualTo("SHIFT_BUDGET_ALLOCATION");
        assertThat(requests.get(0).sourceId()).isEqualTo(ALLOCATION_ID);
        assertThat(requests.get(0).scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
        assertThat(requests.get(0).scopeId()).isEqualTo(ORG_ID);
        assertThat(requests.get(0).actionUrl()).isEqualTo("/shift-budget/allocations/" + ALLOCATION_ID);

        verify(userLocaleCache, times(1)).getLocales(any());
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("P1-a: 受信者 1 名の配送が落ちたら、その受信者だけを NOTIFICATION_SEND の failed event に残す")
    void 途中失敗した受信者を再試行台帳に残す() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        willAnswer(inv -> {
            NotificationDeliveryRequest req = inv.getArgument(0);
            if (Long.valueOf(11L).equals(req.recipientUserId())) {
                throw new IllegalStateException("boom");
            }
            return NotificationDeliveryResult.DELIVERED;
        }).given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(100, List.of(10L, 11L, 12L))))
                .as("配送の失敗を業務スレッドへ例外として返さない")
                .doesNotThrowAnyException();

        // 失敗した 11L の後ろにいる 12L にも配送が続いていること（被害半径の分離）。
        assertThat(captureRequests(3)).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(10L, 11L, 12L);

        ArgumentCaptor<Map<String, Object>> payload = capturePayload();
        verify(failedEventService, times(1)).recordFailure(
                eq(ORG_ID), eq(ShiftBudgetFailedEventType.NOTIFICATION_SEND), eq(ALLOCATION_ID),
                payload.capture(), any());
        assertThat(payload.getValue().get("user_ids"))
                .as("""
                        成功済みの受信者を混ぜると再送で重複配信になる。
                        失敗した受信者だけを再送対象として記録すること。""")
                .isEqualTo(List.of(11L));
    }

    @Test
    @DisplayName("P1-a: 全員の配送が落ちても failed event が残る（notifyAllLocalized ではこれが残らなかった）")
    void 全員失敗でも再試行台帳に残る() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        willThrow(new IllegalStateException("DB connection lost"))
                .given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(100, List.of(10L, 11L))))
                .doesNotThrowAnyException();

        ArgumentCaptor<Map<String, Object>> payload = capturePayload();
        verify(failedEventService, times(1)).recordFailure(
                eq(ORG_ID), eq(ShiftBudgetFailedEventType.NOTIFICATION_SEND), eq(ALLOCATION_ID),
                payload.capture(), any());
        assertThat(payload.getValue())
                .containsEntry("user_ids", List.of(10L, 11L))
                .containsEntry("threshold_percent", 100)
                .containsEntry("scope_id", ORG_ID)
                .containsEntry("action_url", "/shift-budget/allocations/" + ALLOCATION_ID);
        // 運用ログ用の title / body は受信者 locale と独立に ja で確定させる契約。
        assertThat(payload.getValue().get("title")).isEqualTo("シフト予算 警告 (100%)");
        assertThat(payload.getValue().get("body")).isEqualTo("予算を超過しました");
        // Issue #2908: リトライ経路が受信者 locale で組み立て直せるよう i18n キーも保存する。
        assertThat(payload.getValue())
                .containsEntry("title_key", "notification.shiftBudget.thresholdAlert.title")
                .containsEntry("body_key", "notification.shiftBudget.thresholdAlert.body100");
    }

    @Test
    @DisplayName("P1-a: locale の一括解決で総崩れしたら、1 名も配送せず全員を failed event に残す")
    void locale解決の総崩れも再試行台帳に残る() {
        given(userLocaleCache.getLocales(any()))
                .willThrow(new IllegalStateException("DB connection lost"));

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(120, List.of(10L, 11L))))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationDeliveryRunner);
        ArgumentCaptor<Map<String, Object>> payload = capturePayload();
        verify(failedEventService, times(1)).recordFailure(
                eq(ORG_ID), eq(ShiftBudgetFailedEventType.NOTIFICATION_SEND), eq(ALLOCATION_ID),
                payload.capture(), any());
        assertThat(payload.getValue()).containsEntry("user_ids", List.of(10L, 11L));
    }

    @Test
    @DisplayName("受信者 0 名 → 配送も失敗記録も行わない（空・0件の攻め口）")
    void 受信者ゼロ() {
        listener.onBudgetThresholdAlertTriggered(event(80, List.of()));

        verifyNoInteractions(notificationDeliveryRunner);
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("受信者リストが null → NPE を出さずに打ち切る（null の攻め口）")
    void 受信者null() {
        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(80, null)))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationDeliveryRunner);
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("失敗記録自体も失敗 → 例外を外へ出さない（配送スレッドを殺さない）")
    void 失敗記録の失敗も握る() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        willThrow(new IllegalStateException("DB connection lost"))
                .given(notificationDeliveryRunner).sendOne(any());
        willThrow(new IllegalStateException("failed_events insert failed"))
                .given(failedEventService).recordFailure(anyLong(), any(), anyLong(), any(), any());

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(120, List.of(10L))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("120% 閾値: en ロケールで件名・本文が英語になりプレースホルダが残らない（i18n 移設分）")
    void 閾値120_en() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of(10L, "en"));

        listener.onBudgetThresholdAlertTriggered(event(120, List.of(10L)));

        NotificationDeliveryRequest req = captureRequests(1).get(0);
        assertThat(JAPANESE_CHAR.matcher(req.title()).find()).isFalse();
        assertThat(JAPANESE_CHAR.matcher(req.body()).find()).isFalse();
        assertThat(req.title()).isEqualTo("Shift budget warning (120%)");
        assertThat(req.body()).isEqualTo("Budget has exceeded 120% (critical)");
    }

    @Test
    @DisplayName("100% 閾値: en ロケールで件名・本文が英語になりプレースホルダが残らない（i18n 移設分）")
    void 閾値100_en() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of(10L, "en"));

        listener.onBudgetThresholdAlertTriggered(event(100, List.of(10L)));

        NotificationDeliveryRequest req = captureRequests(1).get(0);
        assertThat(JAPANESE_CHAR.matcher(req.title()).find()).isFalse();
        assertThat(JAPANESE_CHAR.matcher(req.body()).find()).isFalse();
        assertThat(req.title()).isEqualTo("Shift budget warning (100%)");
        assertThat(req.body()).isEqualTo("Budget has been exceeded");
    }
}
