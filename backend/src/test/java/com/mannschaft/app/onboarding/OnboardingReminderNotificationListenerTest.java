package com.mannschaft.app.onboarding;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2834 / CMP-056 第1群ロットA — {@link OnboardingReminderNotificationListener} のユニットテスト。
 *
 * <p><b>複数受信者</b>側の金型（型確立PR #2910 の {@code EventAdvanceNoticeNotificationListener} と同型）
 * の番人。AC-3「1受信者の失敗が他受信者の通知を消さない」を、配送失敗・組み立て失敗の両方について
 * 検証する。是正前は同一トランザクション内でループしていたため、1件の DB 例外が rollback-only を残し、
 * catch して続行した他受信者の通知もコミット時にまとめて消えていた。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingReminderNotificationListener ユニットテスト")
class OnboardingReminderNotificationListenerTest {

    private static final Long SCOPE_ID = 10L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private OnboardingReminderNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new OnboardingReminderNotificationListener(
                notificationDeliveryRunner, userLocaleCache, messageSource);
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationEntity.builder().userId(USER_A).build());
    }

    private OnboardingReminderNotificationEvent event(String scopeType) {
        return new OnboardingReminderNotificationEvent(scopeType, SCOPE_ID, List.of(
                new OnboardingReminderNotificationEvent.Recipient(USER_A, 1L),
                new OnboardingReminderNotificationEvent.Recipient(USER_B, 2L),
                new OnboardingReminderNotificationEvent.Recipient(USER_C, 3L)));
    }

    @Test
    @DisplayName("受信者数ぶん sendOne が1件ずつ呼ばれ、locale はバルク解決される（N+1防止）")
    void 受信者数ぶんsendOneが1件ずつ呼ばれる() {
        listener.onOnboardingReminderNotification(event("TEAM"));

        verify(userLocaleCache, times(1)).getLocales(any());
        verify(userLocaleCache, never()).getLocale(anyLong());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);
        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("ONBOARDING_REMINDER");
        assertThat(first.sourceType()).isEqualTo("ONBOARDING");
        assertThat(first.sourceId()).isEqualTo(1L);
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(first.scopeId()).isEqualTo(SCOPE_ID);
        assertThat(first.actionUrl()).isEqualTo("/onboarding/progress/1");
        assertThat(first.actorId()).isNull();
    }

    @Test
    @DisplayName("scopeType が TEAM 以外なら通知スコープは ORGANIZATION になる（是正前の分岐を維持）")
    void 組織スコープではORGANIZATIONになる() {
        listener.onOnboardingReminderNotification(event("ORGANIZATION"));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).allMatch(
                r -> r.scopeType() == NotificationScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("AC-3: 1受信者の配送例外が他受信者の配送を止めない")
    void 一人の配送失敗が他を巻き添えにしない() {
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && USER_B.equals(r.recipientUserId())));

        listener.onOnboardingReminderNotification(event("TEAM"));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);
    }

    @Test
    @DisplayName("AC-3: 1受信者の組み立て例外も他受信者を巻き添えにしない（組み立ても受信者単位で隔離）")
    void 一人の組み立て失敗が他を巻き添えにしない() {
        // 2人目（受信者B）ぶんの本文組み立てだけを失敗させる。
        given(messageSource.getMessage(eq("notification.onboarding.reminder.body"),
                any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2))
                .willThrow(new RuntimeException("模擬組み立て失敗"))
                .willAnswer(inv -> inv.getArgument(2));

        listener.onOnboardingReminderNotification(event("TEAM"));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_C);
    }

    @Test
    @DisplayName("AC-4: visibility deny（null 復帰）は例外扱いせず、後続受信者の配送も続く")
    void denyは例外扱いされず後続も続く() {
        given(notificationDeliveryRunner.sendOne(
                argThat(r -> r != null && USER_B.equals(r.recipientUserId())))).willReturn(null);

        assertThatCode(() -> listener.onOnboardingReminderNotification(event("TEAM")))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("locale のバルク解決が失敗しても既定 locale で全員ぶん配送を続ける（PR #2873 の挙動を維持）")
    void バルクロケール解決失敗でも配送は続く() {
        given(userLocaleCache.getLocales(any())).willThrow(new RuntimeException("locale一括解決失敗"));

        listener.onOnboardingReminderNotification(event("TEAM"));

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("受信者が空なら配送は一切試みられない")
    void 受信者が空なら何もしない() {
        listener.onOnboardingReminderNotification(
                new OnboardingReminderNotificationEvent("TEAM", SCOPE_ID, List.of()));

        verifyNoInteractions(notificationDeliveryRunner, userLocaleCache);
    }

    // ------------------------------------------------------------------
    // 集計ログのレベル（deny=WARN / 例外=ERROR の区別）
    // ------------------------------------------------------------------

    /** 集計ログを識別するための固定文言。 */
    private static final String SUMMARY_MARKER = "オンボーディングリマインド一括配送の結果";

    private Logger listenerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 集計ログを捕捉する。
     *
     * <p>ロガーレベルは<b>このテスト自身で明示設定する</b>。{@link ListAppender} はフォークをまたいで
     * ロガーレベルを継承するため、レベルを設定しないと単体実行では拾えて全体実行では拾えない
     * （あるいはその逆の）偽緑になる。</p>
     */
    private void captureLogs() {
        listenerLogger = (Logger) LoggerFactory.getLogger(OnboardingReminderNotificationListener.class);
        originalLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.WARN);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        if (listenerLogger != null) {
            listenerLogger.detachAppender(logAppender);
            listenerLogger.setLevel(originalLevel);
            listenerLogger = null;
        }
    }

    private List<ILoggingEvent> summaryEvents() {
        return logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains(SUMMARY_MARKER))
                .toList();
    }

    @Test
    @DisplayName("deny のみ（例外ゼロ）なら集計ログは WARN であり ERROR は出ない")
    void denyのみなら集計ログはWARN() {
        captureLogs();
        given(notificationDeliveryRunner.sendOne(any())).willReturn(null);

        listener.onOnboardingReminderNotification(event("TEAM"));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("例外が1件でもあれば集計ログは ERROR になる")
    void 例外が1件でもあれば集計ログはERROR() {
        captureLogs();
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && USER_B.equals(r.recipientUserId())));

        listener.onOnboardingReminderNotification(event("TEAM"));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("全件成功なら集計ログ自体が出ない")
    void 全件成功なら集計ログは出ない() {
        captureLogs();

        listener.onOnboardingReminderNotification(event("TEAM"));

        assertThat(summaryEvents()).isEmpty();
    }
}
