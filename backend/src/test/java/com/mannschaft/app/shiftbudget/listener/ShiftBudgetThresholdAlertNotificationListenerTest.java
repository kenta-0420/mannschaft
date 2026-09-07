package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.event.BudgetThresholdAlertTriggeredEvent;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetThresholdAlertNotificationListener 単体テスト")
class ShiftBudgetThresholdAlertNotificationListenerTest {

    private static final Long ALLOCATION_ID = 42L;
    private static final Long ORG_ID = 1L;
    private static final Long ALERT_ID = 900L;

    private static final Pattern JAPANESE_CHAR = Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    @Mock
    private NotificationHelper notificationHelper;
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
                notificationHelper, failedEventService, messageSource);
    }

    private BudgetThresholdAlertTriggeredEvent event(int thresholdPercent, List<Long> recipients) {
        return new BudgetThresholdAlertTriggeredEvent(
                ALERT_ID, ALLOCATION_ID, ORG_ID, thresholdPercent, recipients);
    }

    @Test
    @DisplayName("受信者ありイベント → notifyAllLocalized を 1 回だけ呼ぶ（受信者ごとのループを作らない）")
    void 受信者ありで一括配送() {
        listener.onBudgetThresholdAlertTriggered(event(80, List.of(10L, 11L)));

        verify(notificationHelper, times(1)).notifyAllLocalized(
                eq(List.of(10L, 11L)), eq("SHIFT_BUDGET_THRESHOLD_ALERT"),
                eq("SHIFT_BUDGET_ALLOCATION"), eq(ALLOCATION_ID),
                eq(NotificationScopeType.ORGANIZATION), eq(ORG_ID),
                eq("/shift-budget/allocations/" + ALLOCATION_ID), eq(null), any());
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("受信者 0 名 → 配送も失敗記録も行わない（空・0件の攻め口）")
    void 受信者ゼロ() {
        listener.onBudgetThresholdAlertTriggered(event(80, List.of()));

        verifyNoInteractions(notificationHelper);
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("受信者リストが null → NPE を出さずに打ち切る（null の攻め口）")
    void 受信者null() {
        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(80, null)))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationHelper);
        verifyNoInteractions(failedEventService);
    }

    @Test
    @DisplayName("配送が総崩れ → NOTIFICATION_SEND の failed_event を記録し、例外を業務側へ返さない")
    void 配送総崩れで失敗イベント記録() {
        willThrow(new IllegalStateException("DB connection lost"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(100, List.of(10L, 11L))))
                .doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
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
    @DisplayName("失敗記録自体も失敗 → 例外を外へ出さない（配送スレッドを殺さない）")
    void 失敗記録の失敗も握る() {
        willThrow(new IllegalStateException("DB connection lost"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), any(), any(), any(), any(), any(), any(), any(), any());
        willThrow(new IllegalStateException("failed_events insert failed"))
                .given(failedEventService).recordFailure(anyLong(), any(), anyLong(), any(), any());

        assertThatCode(() -> listener.onBudgetThresholdAlertTriggered(event(120, List.of(10L))))
                .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("通知本文の i18n (Issue #2715 CMP-055 ロットC-4 から移設)")
    class NotificationI18n {

        private NotificationHelper.LocalizedMessageBuilder captureBuilder(int thresholdPercent) {
            listener.onBudgetThresholdAlertTriggered(event(thresholdPercent, List.of(10L)));
            ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                    ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper).notifyAllLocalized(
                    any(), eq("SHIFT_BUDGET_THRESHOLD_ALERT"),
                    any(), any(), any(), any(), any(), any(), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("80% 閾値: en ロケールで件名・本文が英語になりプレースホルダが残らない")
        void 閾値80_en() {
            NotificationHelper.LocalizedMessageBuilder builder = captureBuilder(80);

            NotificationHelper.LocalizedMessage en = builder.build(10L, Locale.ENGLISH);
            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            assertThat(JAPANESE_CHAR.matcher(en.body()).find()).isFalse();
            assertThat(en.title()).isEqualTo("Shift budget warning (80%)");
            assertThat(en.body()).isEqualTo("Budget has reached 80%");

            NotificationHelper.LocalizedMessage ja = builder.build(10L, Locale.JAPANESE);
            assertThat(ja.title()).isEqualTo("シフト予算 警告 (80%)");
            assertThat(ja.body()).isEqualTo("予算 80% に到達しました");
        }

        @Test
        @DisplayName("100% 閾値: en ロケールで件名・本文が英語になりプレースホルダが残らない")
        void 閾値100_en() {
            NotificationHelper.LocalizedMessage en = captureBuilder(100).build(10L, Locale.ENGLISH);

            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            assertThat(JAPANESE_CHAR.matcher(en.body()).find()).isFalse();
            assertThat(en.title()).isEqualTo("Shift budget warning (100%)");
            assertThat(en.body()).isEqualTo("Budget has been exceeded");
        }

        @Test
        @DisplayName("120% 閾値: en ロケールで件名・本文が英語になりプレースホルダが残らない（重大表記含む）")
        void 閾値120_en() {
            NotificationHelper.LocalizedMessage en = captureBuilder(120).build(10L, Locale.ENGLISH);

            assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
            assertThat(JAPANESE_CHAR.matcher(en.body()).find()).isFalse();
            assertThat(en.title()).isEqualTo("Shift budget warning (120%)");
            assertThat(en.body()).isEqualTo("Budget has exceeded 120% (critical)");
        }
    }
}
