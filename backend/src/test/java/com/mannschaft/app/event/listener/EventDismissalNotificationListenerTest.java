package com.mannschaft.app.event.listener;



import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventDismissalNotificationEvent;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
 * Issue #2834 / CMP-056 第1群ロットB — {@link EventDismissalNotificationListener} のユニットテスト。
 *
 * <p>受け入れ条件との対応:</p>
 * <ul>
 *   <li>AC-1/AC-2（通知例外で本処理が巻き戻らない・ロールバック時は通知しない）:
 *       {@link #コミット後に非同期で発火する}（{@code AFTER_COMMIT} 契約）</li>
 *   <li>AC-3（1受信者の失敗が他受信者の通知を消さない）: {@link #一人の配送失敗が他を巻き添えにしない} /
 *       {@link #一人の組み立て失敗が他を巻き添えにしない} / {@link #見守り者通知の失敗が他を巻き添えにしない}</li>
 *   <li>AC-4（deny と例外を区別して観測できる）: {@link #denyのみなら集計ログはWARN} /
 *       {@link #例外が1件でもあれば集計ログはERROR}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDismissalNotificationListener ユニットテスト")
class EventDismissalNotificationListenerTest {

    private static final Long EVENT_ID = 700L;
    private static final Long TEAM_ID = 20L;
    private static final Long OPERATOR_ID = 9L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CareLinkService careLinkService;

    @Mock
    private CareEventNotificationService careEventNotificationService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private EventDismissalNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new EventDismissalNotificationListener(
                notificationDeliveryRunner, eventRepository, careLinkService,
                careEventNotificationService, userLocaleCache, messageSource);
        lenient().when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(
                EventEntity.builder().id(EVENT_ID).slug("event-slug").subtitle("練習試合").build()));
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private EventDismissalNotificationEvent event(String customMessage, boolean notifyGuardians) {
        return new EventDismissalNotificationEvent(
                EVENT_ID, TEAM_ID, OPERATOR_ID, customMessage, notifyGuardians,
                List.of(USER_A, USER_B, USER_C));
    }

    @Test
    @DisplayName("AC-1/AC-2: 配送は AFTER_COMMIT + event-pool の非同期で起動する")
    void コミット後に非同期で発火する() throws Exception {
        Method method = EventDismissalNotificationListener.class.getMethod(
                "onEventDismissalNotification", EventDismissalNotificationEvent.class);

        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");
    }

    @Test
    @DisplayName("参加者数ぶん sendOne が1件ずつ呼ばれ、locale はバルク解決される（N+1防止）")
    void 参加者数ぶんsendOneが1件ずつ呼ばれる() {
        listener.onEventDismissalNotification(event(null, false));

        verify(userLocaleCache, times(1)).getLocales(any());
        verify(userLocaleCache, never()).getLocale(anyLong());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);

        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("EVENT_DISMISSAL");
        assertThat(first.sourceType()).isEqualTo("EVENT");
        assertThat(first.sourceId()).isEqualTo(EVENT_ID);
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.PERSONAL);
        assertThat(first.scopeId()).isEqualTo(first.recipientUserId());
        assertThat(first.actionUrl()).isEqualTo("/teams/" + TEAM_ID + "/events/" + EVENT_ID);
        assertThat(first.actorId()).isNull();
    }

    @Test
    @DisplayName("カスタムメッセージが指定されていれば本文にそのまま使う（i18n 対象外・是正前と同じ）")
    void カスタム本文が優先される() {
        listener.onEventDismissalNotification(event("駅前で解散しました", false));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> "駅前で解散しました".equals(r.body()));
        verify(messageSource, never()).getMessage(
                eq("notification.event.dismissal.defaultBody"), any(), anyString(), any(Locale.class));
    }

    @Test
    @DisplayName("AC-3: 1受信者の配送例外が他受信者の配送を止めず、リスナー外へも漏れない")
    void 一人の配送失敗が他を巻き添えにしない() {
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && USER_B.equals(r.recipientUserId())));

        assertThatCode(() -> listener.onEventDismissalNotification(event(null, false)))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("AC-3: 1受信者の組み立て例外も他受信者を巻き添えにしない")
    void 一人の組み立て失敗が他を巻き添えにしない() {
        given(messageSource.getMessage(eq("notification.event.dismissal.title"),
                any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2))
                .willThrow(new RuntimeException("模擬組み立て失敗"))
                .willAnswer(inv -> inv.getArgument(2));

        listener.onEventDismissalNotification(event(null, false));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_C);
    }

    @Test
    @DisplayName("notifyGuardians=true なら見守り者通知もリスナー側（業務TX外）で行う")
    void 見守り者通知もリスナーで行う() {
        given(careLinkService.isUnderCare(anyLong())).willReturn(true);

        listener.onEventDismissalNotification(event(null, true));

        verify(careEventNotificationService).notifyDismissal(USER_A, EVENT_ID);
        verify(careEventNotificationService).notifyDismissal(USER_B, EVENT_ID);
        verify(careEventNotificationService).notifyDismissal(USER_C, EVENT_ID);
    }

    @Test
    @DisplayName("notifyGuardians=false なら見守り者通知は一切行わない（是正前の分岐を維持）")
    void 見守り者通知は指定時のみ() {
        listener.onEventDismissalNotification(event(null, false));

        verifyNoInteractions(careLinkService, careEventNotificationService);
    }

    @Test
    @DisplayName("AC-3: 1人ぶんの見守り者通知の失敗が他のケア対象者を巻き添えにしない")
    void 見守り者通知の失敗が他を巻き添えにしない() {
        given(careLinkService.isUnderCare(anyLong())).willReturn(true);
        willThrow(new RuntimeException("模擬ケア通知失敗"))
                .given(careEventNotificationService).notifyDismissal(USER_B, EVENT_ID);

        assertThatCode(() -> listener.onEventDismissalNotification(event(null, true)))
                .doesNotThrowAnyException();

        verify(careEventNotificationService).notifyDismissal(USER_A, EVENT_ID);
        verify(careEventNotificationService).notifyDismissal(USER_C, EVENT_ID);
    }

    @Test
    @DisplayName("AC-4: visibility deny（null 復帰）は例外扱いせず、後続受信者の配送も続く")
    void denyは例外扱いされず後続も続く() {
        given(notificationDeliveryRunner.sendOne(
                argThat(r -> r != null && USER_B.equals(r.recipientUserId())))).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onEventDismissalNotification(event(null, false)))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("イベントが解決できない場合は誰にも送らない")
    void イベントが見つからなければ誰にも送らない() {
        given(eventRepository.findById(EVENT_ID)).willReturn(Optional.empty());

        listener.onEventDismissalNotification(event(null, true));

        verify(notificationDeliveryRunner, never()).sendOne(any());
        verifyNoInteractions(careEventNotificationService);
    }

    @Test
    @DisplayName("参加者が空ならイベント解決すら行わない")
    void 参加者が空なら何もしない() {
        listener.onEventDismissalNotification(new EventDismissalNotificationEvent(
                EVENT_ID, TEAM_ID, OPERATOR_ID, null, true, List.of()));

        verifyNoInteractions(notificationDeliveryRunner, eventRepository, userLocaleCache,
                careLinkService, careEventNotificationService);
    }

    // ------------------------------------------------------------------
    // AC-4: 集計ログのレベル（deny=WARN / 例外=ERROR の区別）
    // ------------------------------------------------------------------

    /** 集計ログを識別するための固定文言。 */
    private static final String SUMMARY_MARKER = "解散通知一括配送の結果";

    private Logger listenerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 集計ログを捕捉する。ロガーレベルは<b>このテスト自身で明示設定する</b>
     * （{@link ListAppender} のレベル継承による偽緑を避けるため）。
     */
    private void captureLogs() {
        listenerLogger = (Logger) LoggerFactory.getLogger(EventDismissalNotificationListener.class);
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
    @DisplayName("AC-4: deny のみ（例外ゼロ）なら集計ログは WARN であり ERROR は出ない")
    void denyのみなら集計ログはWARN() {
        captureLogs();
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onEventDismissalNotification(event(null, false));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("AC-4: 例外が1件でもあれば集計ログは ERROR になる")
    void 例外が1件でもあれば集計ログはERROR() {
        captureLogs();
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && USER_B.equals(r.recipientUserId())));

        listener.onEventDismissalNotification(event(null, false));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("全件成功なら集計ログ自体が出ない")
    void 全件成功なら集計ログは出ない() {
        captureLogs();

        listener.onEventDismissalNotification(event(null, false));

        assertThat(summaryEvents()).isEmpty();
    }
}
