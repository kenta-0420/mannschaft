package com.mannschaft.app.circulation;



import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.event.CirculationReminderNotificationEvent;
import com.mannschaft.app.circulation.event.CirculationReminderNotificationListener;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
 * Issue #2834 / CMP-056 第1群ロットB — {@link CirculationReminderNotificationListener} のユニットテスト。
 *
 * <p>受け入れ条件との対応:</p>
 * <ul>
 *   <li>AC-1（通知の DB 例外が起きても本処理はコミットされる）: 配送が {@code AFTER_COMMIT} で
 *       起動することを注釈で検証（{@link #コミット後に非同期で発火する}）。業務TXは既に閉じており、
 *       配送例外がリスナー外へ漏れないことも併せて検証する。</li>
 *   <li>AC-2（本処理がロールバックすれば通知は作られない）: 同じく {@code AFTER_COMMIT} 契約
 *       （ロールバック時はリスナー自体が発火しない）。</li>
 *   <li>AC-3（1受信者の失敗が他受信者の通知を消さない）: {@link #一人の配送失敗が他を巻き添えにしない}
 *       / {@link #一人の組み立て失敗が他を巻き添えにしない}</li>
 *   <li>AC-4（deny と例外を区別して観測できる）: {@link #denyのみなら集計ログはWARN} /
 *       {@link #例外が1件でもあれば集計ログはERROR}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationReminderNotificationListener ユニットテスト")
class CirculationReminderNotificationListenerTest {

    private static final Long DOCUMENT_ID = 500L;
    private static final Long ACTOR_ID = 9L;
    private static final Long SCOPE_ID = 10L;
    private static final Long USER_A = 101L;
    private static final Long USER_B = 102L;
    private static final Long USER_C = 103L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private CirculationReminderNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new CirculationReminderNotificationListener(
                notificationDeliveryRunner, documentRepository, userLocaleCache, messageSource);
        lenient().when(documentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document("TEAM")));
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private CirculationDocumentEntity document(String scopeType) {
        return CirculationDocumentEntity.builder()
                .id(DOCUMENT_ID)
                .scopeType(scopeType)
                .scopeId(SCOPE_ID)
                .title("年度末回覧")
                .build();
    }

    private CirculationReminderNotificationEvent event() {
        return new CirculationReminderNotificationEvent(
                DOCUMENT_ID, ACTOR_ID, List.of(USER_A, USER_B, USER_C));
    }

    @Test
    @DisplayName("AC-1/AC-2: 配送は AFTER_COMMIT + event-pool の非同期で起動する")
    void コミット後に非同期で発火する() throws Exception {
        Method method = CirculationReminderNotificationListener.class.getMethod(
                "onCirculationReminderNotification", CirculationReminderNotificationEvent.class);

        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");
    }

    @Test
    @DisplayName("受信者数ぶん sendOne が1件ずつ呼ばれ、locale はバルク解決される（N+1防止）")
    void 受信者数ぶんsendOneが1件ずつ呼ばれる() {
        listener.onCirculationReminderNotification(event());

        verify(userLocaleCache, times(1)).getLocales(any());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);

        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("CIRCULATION_REMINDER");
        assertThat(first.sourceType()).isEqualTo("CIRCULATION_DOCUMENT");
        assertThat(first.sourceId()).isEqualTo(DOCUMENT_ID);
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(first.scopeId()).isEqualTo(SCOPE_ID);
        assertThat(first.actionUrl()).isEqualTo("/circulations/" + DOCUMENT_ID);
        assertThat(first.actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("scopeType が ORGANIZATION なら通知スコープも ORGANIZATION になる（是正前の分岐を維持）")
    void 組織スコープではORGANIZATIONになる() {
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document("ORGANIZATION")));

        listener.onCirculationReminderNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).allMatch(
                r -> r.scopeType() == NotificationScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("AC-3: 1受信者の配送例外が他受信者の配送を止めず、リスナー外へも漏れない")
    void 一人の配送失敗が他を巻き添えにしない() {
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && USER_B.equals(r.recipientUserId())));

        assertThatCode(() -> listener.onCirculationReminderNotification(event()))
                .doesNotThrowAnyException();

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(USER_A, USER_B, USER_C);
    }

    @Test
    @DisplayName("AC-3: 1受信者の組み立て例外も他受信者を巻き添えにしない（組み立ても受信者単位で隔離）")
    void 一人の組み立て失敗が他を巻き添えにしない() {
        given(messageSource.getMessage(eq("notification.circulation.reminder.body"),
                any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2))
                .willThrow(new RuntimeException("模擬組み立て失敗"))
                .willAnswer(inv -> inv.getArgument(2));

        listener.onCirculationReminderNotification(event());

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
                argThat(r -> r != null && USER_B.equals(r.recipientUserId())))).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onCirculationReminderNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("locale のバルク解決が失敗しても既定 locale で全員ぶん配送を続ける")
    void バルクロケール解決失敗でも配送は続く() {
        given(userLocaleCache.getLocales(any())).willThrow(new RuntimeException("locale一括解決失敗"));

        listener.onCirculationReminderNotification(event());

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("文書が解決できない場合は誰にも送らない（受信者共通の解決失敗は一括で諦める）")
    void 文書が見つからなければ誰にも送らない() {
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.empty());

        listener.onCirculationReminderNotification(event());

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("受信者が空なら文書解決すら行わない")
    void 受信者が空なら何もしない() {
        listener.onCirculationReminderNotification(
                new CirculationReminderNotificationEvent(DOCUMENT_ID, ACTOR_ID, List.of()));

        verifyNoInteractions(notificationDeliveryRunner, documentRepository, userLocaleCache);
    }

    // ------------------------------------------------------------------
    // AC-4: 集計ログのレベル（deny=WARN / 例外=ERROR の区別）
    // ------------------------------------------------------------------

    /** 集計ログを識別するための固定文言。 */
    private static final String SUMMARY_MARKER = "回覧リマインド一括配送の結果";

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
        listenerLogger = (Logger) LoggerFactory.getLogger(CirculationReminderNotificationListener.class);
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

        listener.onCirculationReminderNotification(event());

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

        listener.onCirculationReminderNotification(event());

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("全件成功なら集計ログ自体が出ない")
    void 全件成功なら集計ログは出ない() {
        captureLogs();

        listener.onCirculationReminderNotification(event());

        assertThat(summaryEvents()).isEmpty();
    }
}
