package com.mannschaft.app.payment;



import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.event.PaymentAdvanceSettledNotificationEvent;
import com.mannschaft.app.payment.event.PaymentAdvanceSettledNotificationListener;
import com.mannschaft.app.role.service.RoleService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 * Issue #2834 / CMP-056 第1群ロットB — {@link PaymentAdvanceSettledNotificationListener} のユニットテスト。
 *
 * <p>受け入れ条件との対応:</p>
 * <ul>
 *   <li>AC-1/AC-2: {@link #コミット後に非同期で発火する}（{@code AFTER_COMMIT} 契約）</li>
 *   <li>AC-3: {@link #一人の配送失敗が他を巻き添えにしない} / {@link #一人の組み立て失敗が他を巻き添えにしない}</li>
 *   <li>AC-4: {@link #denyのみなら集計ログはWARN} / {@link #例外が1件でもあれば集計ログはERROR}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAdvanceSettledNotificationListener ユニットテスト")
class PaymentAdvanceSettledNotificationListenerTest {

    private static final UUID ADVANCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Long ORG_ID = 30L;
    private static final Long ACTOR_ID = 9L;
    private static final Long ADMIN_A = 201L;
    private static final Long ADMIN_B = 202L;
    private static final Long ADMIN_C = 203L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private RoleService roleService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private PaymentAdvanceSettledNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentAdvanceSettledNotificationListener(
                notificationDeliveryRunner, roleService, userLocaleCache, messageSource);
        lenient().when(roleService.getAdminUserIdsByOrganizationId(ORG_ID))
                .thenReturn(List.of(ADMIN_A, ADMIN_B, ADMIN_C));
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private PaymentAdvanceSettledNotificationEvent event() {
        return new PaymentAdvanceSettledNotificationEvent(ADVANCE_ID, ORG_ID, 12000, "JPY", ACTOR_ID);
    }

    @Test
    @DisplayName("AC-1/AC-2: 配送は AFTER_COMMIT + event-pool の非同期で起動する")
    void コミット後に非同期で発火する() throws Exception {
        Method method = PaymentAdvanceSettledNotificationListener.class.getMethod(
                "onPaymentAdvanceSettledNotification", PaymentAdvanceSettledNotificationEvent.class);

        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");
    }

    @Test
    @DisplayName("協会 ADMIN 全員に sendOne が1件ずつ呼ばれ、locale はバルク解決される")
    void 協会ADMIN全員に1件ずつ送る() {
        listener.onPaymentAdvanceSettledNotification(event());

        verify(userLocaleCache, times(1)).getLocales(any());
        // 是正前の LocaleContextHolder（操作者スレッドの locale）ではなく受信者ごとの locale を使う。
        verify(userLocaleCache, never()).getLocale(anyLong());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(ADMIN_A, ADMIN_B, ADMIN_C);

        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("PAYMENT_ADVANCE_SETTLED");
        assertThat(first.sourceType()).isEqualTo("PAYMENT_ADVANCE");
        assertThat(first.sourceId()).isNull();
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
        assertThat(first.scopeId()).isEqualTo(ORG_ID);
        assertThat(first.actionUrl()).isEqualTo("/organizations/" + ORG_ID + "/payment-requests");
        assertThat(first.actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("AC-3: 1受信者の配送例外が他受信者の配送を止めず、リスナー外へも漏れない")
    void 一人の配送失敗が他を巻き添えにしない() {
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && ADMIN_B.equals(r.recipientUserId())));

        assertThatCode(() -> listener.onPaymentAdvanceSettledNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("AC-3: 1受信者の組み立て例外も他受信者を巻き添えにしない")
    void 一人の組み立て失敗が他を巻き添えにしない() {
        given(messageSource.getMessage(eq("notification.payment_advance.settled.body"),
                any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2))
                .willThrow(new RuntimeException("模擬組み立て失敗"))
                .willAnswer(inv -> inv.getArgument(2));

        listener.onPaymentAdvanceSettledNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(ADMIN_A, ADMIN_C);
    }

    @Test
    @DisplayName("AC-4: visibility deny（null 復帰）は例外扱いせず、後続受信者の配送も続く")
    void denyは例外扱いされず後続も続く() {
        given(notificationDeliveryRunner.sendOne(
                argThat(r -> r != null && ADMIN_B.equals(r.recipientUserId())))).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onPaymentAdvanceSettledNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("協会 ADMIN の解決が失敗したら誰にも送らない（受信者共通の解決失敗は一括で諦める）")
    void 受信者解決失敗なら誰にも送らない() {
        given(roleService.getAdminUserIdsByOrganizationId(ORG_ID))
                .willThrow(new RuntimeException("模擬受信者解決失敗"));

        assertThatCode(() -> listener.onPaymentAdvanceSettledNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("協会 ADMIN が不在ならスキップする（是正前の分岐を維持）")
    void 協会ADMIN不在ならスキップ() {
        given(roleService.getAdminUserIdsByOrganizationId(ORG_ID)).willReturn(List.of());

        listener.onPaymentAdvanceSettledNotification(event());

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("organizationId が無い立替（過去データ）は通知しない（是正前の分岐を維持）")
    void 組織ID不明なら何もしない() {
        listener.onPaymentAdvanceSettledNotification(
                new PaymentAdvanceSettledNotificationEvent(ADVANCE_ID, null, 12000, "JPY", ACTOR_ID));

        verifyNoInteractions(notificationDeliveryRunner, roleService, userLocaleCache);
    }

    // ------------------------------------------------------------------
    // AC-4: 集計ログのレベル（deny=WARN / 例外=ERROR の区別）
    // ------------------------------------------------------------------

    /** 集計ログを識別するための固定文言。 */
    private static final String SUMMARY_MARKER = "精算確認通知一括配送の結果";

    private Logger listenerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 集計ログを捕捉する。ロガーレベルは<b>このテスト自身で明示設定する</b>
     * （{@link ListAppender} のレベル継承による偽緑を避けるため）。
     */
    private void captureLogs() {
        listenerLogger = (Logger) LoggerFactory.getLogger(PaymentAdvanceSettledNotificationListener.class);
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

        listener.onPaymentAdvanceSettledNotification(event());

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
                .sendOne(argThat(r -> r != null && ADMIN_B.equals(r.recipientUserId())));

        listener.onPaymentAdvanceSettledNotification(event());

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("全件成功なら集計ログ自体が出ない")
    void 全件成功なら集計ログは出ない() {
        captureLogs();

        listener.onPaymentAdvanceSettledNotification(event());

        assertThat(summaryEvents()).isEmpty();
    }
}
