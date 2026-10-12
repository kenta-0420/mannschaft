package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.shift.service.ShiftPreferenceReminderRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftPreferenceReminderNotificationListener} のユニットテスト（Issue #2997 / CMP-260827-1152 G6）。
 *
 * <p>是正前は {@code ShiftPreferenceReminderBatchServiceTest} が {@code notifyAllLocalized} の呼び出しとして
 * 固定していた配送の意味論（通知種別・source・スコープ・遷移先・文面・locale）を、配送リスナー側で固定し直す。
 * 実物の {@code MessageSource} を使う（モックが引数をそのまま返す形だと鍵の欠落もフォーマット崩れも検出できない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftPreferenceReminderNotificationListener 単体テスト")
class ShiftPreferenceReminderNotificationListenerTest {

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final String TITLE = "テストシフト";
    private static final Pattern JAPANESE_CHAR = Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    @Mock private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private ShiftPreferenceReminderRunner reminderRunner;

    private ShiftPreferenceReminderNotificationListener listener;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        listener = new ShiftPreferenceReminderNotificationListener(
                notificationDeliveryRunner, userLocaleCache, messageSource, reminderRunner);
        Mockito.lenient().when(userLocaleCache.getLocales(anyList()))
                .thenReturn(Map.of(USER_A, "ja", USER_B, "en"));
        Mockito.lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private ShiftPreferenceReminderNotificationEvent event(ShiftPreferenceReminderKind kind, Long... users) {
        return new ShiftPreferenceReminderNotificationEvent(kind, SCHEDULE_ID, TEAM_ID, TITLE, List.of(users));
    }

    private List<NotificationDeliveryRequest> sent(int expected) {
        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(expected)).sendOne(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("48h: 受信者ごとに、通知種別・source・スコープ・遷移先が是正前と同じ配送要求になる")
    void reminder48hDeliversWithPreservedSemantics() {
        listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B));

        List<NotificationDeliveryRequest> requests = sent(2);
        assertThat(requests).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(USER_A, USER_B);
        assertThat(requests).allSatisfy(r -> {
            assertThat(r.notificationType()).isEqualTo("SHIFT_REQUEST_REMINDER_48H");
            assertThat(r.priority()).isEqualTo(NotificationPriority.NORMAL);
            assertThat(r.sourceType()).isEqualTo("SHIFT_SCHEDULE");
            assertThat(r.sourceId()).isEqualTo(SCHEDULE_ID);
            assertThat(r.scopeType()).isEqualTo(NotificationScopeType.TEAM);
            assertThat(r.scopeId()).isEqualTo(TEAM_ID);
            assertThat(r.actionUrl()).isEqualTo("/my/shift-request?teamId=" + TEAM_ID + "&scheduleId=" + SCHEDULE_ID);
            assertThat(r.actorId()).isNull();
        });
    }

    @Test
    @DisplayName("24h / 手動: 通知種別が是正前と同じ")
    void otherKindsKeepNotificationType() {
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.REMINDER_24H, USER_A));
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.MANUAL, USER_A));

        assertThat(sent(2)).extracting(NotificationDeliveryRequest::notificationType)
                .containsExactly("SHIFT_REQUEST_REMINDER", "SHIFT_REQUEST_REMINDER_MANUAL");
    }

    @Test
    @DisplayName("48h: 受信者の locale に応じて件名・本文が組み立てられ、en では日本語もプレースホルダも残らない")
    void reminder48hLocalized() {
        listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B));

        NotificationDeliveryRequest ja = sent(2).get(0);
        NotificationDeliveryRequest en = sent(2).get(1);
        assertThat(ja.title()).isEqualTo("シフト希望の提出期限 48 時間前です");
        assertThat(ja.body()).contains(TITLE);
        assertThat(en.title()).isEqualTo("Shift preference submission deadline in 48 hours");
        // body 中の {0} はスケジュールタイトル（ユーザー入力の日本語）そのものなので、静的な文言部分だけを検証する。
        assertThat(JAPANESE_CHAR.matcher(en.body().replace(TITLE, "")).find()).isFalse();
        assertThat(en.body()).contains(TITLE).doesNotContain("{0}");
    }

    @Test
    @DisplayName("24h: en ロケールで件名・本文が英語になる")
    void reminder24hEnglish() {
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.REMINDER_24H, USER_B));

        NotificationDeliveryRequest en = sent(1).get(0);
        assertThat(JAPANESE_CHAR.matcher(en.title()).find()).isFalse();
        assertThat(JAPANESE_CHAR.matcher(en.body().replace(TITLE, "")).find()).isFalse();
    }

    @Test
    @DisplayName("手動: en ロケールで件名・本文が英語になる")
    void manualEnglish() {
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.MANUAL, USER_B));

        NotificationDeliveryRequest en = sent(1).get(0);
        assertThat(en.title()).isEqualTo("Reminder to submit your shift preference");
        assertThat(JAPANESE_CHAR.matcher(en.body().replace(TITLE, "")).find()).isFalse();
    }

    @Test
    @DisplayName("1 人ぶんの配送が例外でも、他の受信者への配送は続行される（AC-B2）")
    void oneFailureDoesNotStopOthers() {
        given(notificationDeliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("模擬DB例外"))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        assertThatCode(() -> listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B))).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("visibility deny でも例外にせず、後続受信者へ送り続ける")
    void denyDoesNotStopLoop() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B))).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("locale の一括解決が失敗しても既定 locale（ja）で配送を続行する")
    void localeResolutionFailureFallsBackToJa() {
        given(userLocaleCache.getLocales(anyList())).willThrow(new RuntimeException("模擬キャッシュ障害"));

        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.REMINDER_48H, USER_B));

        assertThat(sent(1).get(0).title()).isEqualTo("シフト希望の提出期限 48 時間前です");
    }

    @Test
    @DisplayName("AC-D: 全員配送できたら配送後に印を立てる")
    void marksSentAfterDelivery() {
        listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B));

        verify(reminderRunner).markSent(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H);
    }

    @Test
    @DisplayName("AC-D: 全員が例外で失敗したら印を立てない（次回バッチで再送）")
    void allFailedDoesNotMark() {
        given(notificationDeliveryRunner.sendOne(any())).willThrow(new RuntimeException("模擬DB例外"));

        listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A, USER_B));

        Mockito.verifyNoInteractions(reminderRunner);
    }

    @Test
    @DisplayName("AC-D: 一部だけ失敗なら印を立てる（成功済みにも失敗者にも再送しない）")
    void partialFailureMarks() {
        given(notificationDeliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("模擬DB例外"))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_24H, USER_A, USER_B));

        verify(reminderRunner).markSent(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_24H);
    }

    @Test
    @DisplayName("AC-D: 全員 deny でも印を立てる")
    void denyMarks() {
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A));

        verify(reminderRunner).markSent(SCHEDULE_ID, ShiftPreferenceReminderKind.REMINDER_48H);
    }

    @Test
    @DisplayName("AC-D: 印の保存が失敗してもリスナーは例外を投げない")
    void markFailureIsContained() {
        given(reminderRunner.markSent(any(), any())).willThrow(new RuntimeException("模擬保存失敗"));

        assertThatCode(() -> listener.onShiftPreferenceReminderNotification(
                event(ShiftPreferenceReminderKind.REMINDER_48H, USER_A))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("手動リマインドは印を持たないので markSent を呼ばない")
    void manualDoesNotMark() {
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.MANUAL, USER_A));

        Mockito.verifyNoInteractions(reminderRunner);
    }

    @Test
    @DisplayName("受信者が空なら配送 Runner を呼ばない")
    void noRecipientsDoesNothing() {
        listener.onShiftPreferenceReminderNotification(event(ShiftPreferenceReminderKind.REMINDER_48H));

        Mockito.verifyNoInteractions(notificationDeliveryRunner);
    }
}
