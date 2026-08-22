package com.mannschaft.app.event;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.event.listener.EventAdvanceNoticeNotificationListener;
import com.mannschaft.app.event.service.EventService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2834 / CMP-056 型確立PR — {@link EventAdvanceNoticeNotificationListener} のユニットテスト。
 *
 * <p>Codex 独立検分 [P2]（2026-08-21）是正後: 通知の文面組み立て（主催者・見守り者の解決・
 * ロケール解決・件名/本文組み立て）はサービスではなく本リスナーの責務になったため、
 * ここで組み立てロジックそのものを検証する。</p>
 *
 * <h2>AC-7 の番人</h2>
 * <p>複数受信者（主催者 + 見守り者）がある経路で、{@link NotificationDeliveryRunner#sendOne} が
 * 受信者数ぶん<b>1件ずつ</b>呼ばれることを検証する。</p>
 *
 * <h2>組み立て例外の隔離（Codex検分[P2]是正の裏付け）</h2>
 * <p>組み立て中（{@code userLocaleCache.getLocale} / {@code messageSource.getMessage}）の例外が
 * リスナー呼び出し元（AFTER_COMMIT・非TX）へ伝播しないことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventAdvanceNoticeNotificationListener ユニットテスト")
class EventAdvanceNoticeNotificationListenerTest {

    private static final Long EVENT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR = 200L;
    private static final Long TARGET_USER_ID = 100L;
    private static final Long ORGANIZER = OPERATOR;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private EventService eventService;

    @Mock
    private UserService userService;

    @Mock
    private CareLinkService careLinkService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private EventAdvanceNoticeNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new EventAdvanceNoticeNotificationListener(
                notificationDeliveryRunner, eventService, userService,
                careLinkService, userLocaleCache, messageSource);
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationEntity.builder().userId(1L).build());
    }

    private EventEntity buildEvent(Long organizerId) {
        EventEntity event = EventEntity.builder().createdBy(organizerId).build();
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        return event;
    }

    private EventAdvanceNoticeNotificationEvent lateEvent() {
        return new EventAdvanceNoticeNotificationEvent(
                EVENT_ID, TEAM_ID, OPERATOR, TARGET_USER_ID,
                EventAdvanceNoticeNotificationEvent.Kind.LATE, 30, null);
    }

    @Test
    @DisplayName("主催者へ1件送信される（見守り者なし）")
    void 主催者へ通知される() {
        given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent(ORGANIZER));
        given(userService.getDisplayName(TARGET_USER_ID)).willReturn("テスト太郎");
        given(careLinkService.getActiveWatchers(TARGET_USER_ID, "RSVP")).willReturn(List.of());

        listener.onEventAdvanceNoticeNotification(lateEvent());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(1)).sendOne(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(ORGANIZER);
        assertThat(captor.getValue().body()).contains("テスト太郎").contains("30");
    }

    @Test
    @DisplayName("主催者locale=enなら件名・本文が英語になる（実MessageSource使用）")
    void 主催者ロケールがenなら英語になる() {
        ResourceBundleMessageSource real = new ResourceBundleMessageSource();
        real.setBasename("messages");
        real.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(listener, "messageSource", real);

        given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent(ORGANIZER));
        given(userService.getDisplayName(TARGET_USER_ID)).willReturn("テスト太郎");
        given(userLocaleCache.getLocale(ORGANIZER)).willReturn("en");
        given(careLinkService.getActiveWatchers(TARGET_USER_ID, "RSVP")).willReturn(List.of());

        listener.onEventAdvanceNoticeNotification(lateEvent());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Late arrival notice");
        assertThat(captor.getValue().body()).doesNotContain("{0}").doesNotContain("{1}");
    }

    @Test
    @DisplayName("AC-7: 主催者+見守り者2名で sendOne が3回・1件ずつ呼ばれ、locale はバルク解決される")
    void 受信者数ぶんsendOneが1件ずつ呼ばれる() {
        Long watcher1 = 301L;
        Long watcher2 = 302L;

        given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent(ORGANIZER));
        given(userService.getDisplayName(TARGET_USER_ID)).willReturn("テスト太郎");
        // OPERATOR 自身が見守り者の1人（＝代理送信）で、他に watcher1/watcher2 がいる
        given(careLinkService.getActiveWatchers(TARGET_USER_ID, "RSVP"))
                .willReturn(List.of(OPERATOR, watcher1, watcher2));

        listener.onEventAdvanceNoticeNotification(lateEvent());

        // AC-3系: N+1 防止 — バルク解決 (getLocales) が使われ、単体解決 (getLocale) は見守り者分呼ばれない。
        verify(userLocaleCache, times(1)).getLocales(any());
        verify(userLocaleCache, never()).getLocale(eq(watcher1));
        verify(userLocaleCache, never()).getLocale(eq(watcher2));

        // 主催者(OPERATOR) + 見守り者2名（OPERATOR自身は除外）で計3件、1件ずつ sendOne が呼ばれる。
        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(ORGANIZER, watcher1, watcher2);
    }

    @Test
    @DisplayName("1受信者のRunner例外が他の受信者への配送を止めない")
    void 一人の例外が他の受信者への配送を止めない() {
        Long watcher1 = 301L;
        given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent(ORGANIZER));
        given(userService.getDisplayName(TARGET_USER_ID)).willReturn("テスト太郎");
        given(careLinkService.getActiveWatchers(TARGET_USER_ID, "RSVP"))
                .willReturn(List.of(OPERATOR, watcher1));
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(org.mockito.ArgumentMatchers.argThat(
                        r -> r != null && r.recipientUserId().equals(ORGANIZER)));

        listener.onEventAdvanceNoticeNotification(lateEvent());

        // 主催者(ORGANIZER=OPERATOR)分が例外を投げても、見守り者(watcher1)分の sendOne 呼び出しに到達する。
        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("Codex検分[P2]是正の裏付け: 組み立て中（getLocale）の例外はリスナー呼び出し元へ伝播しない"
            + "（＝業務トランザクションを巻き込まない。AFTER_COMMIT・非TXで発火するため実DB上は"
            + "ContactRequestNotificationTransactionIT で裏取りする）")
    void 組み立て例外は呼び出し元へ伝播しない() {
        given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent(ORGANIZER));
        given(userService.getDisplayName(TARGET_USER_ID)).willReturn("テスト太郎");
        given(userLocaleCache.getLocale(ORGANIZER)).willThrow(new RuntimeException("locale解決失敗"));

        // 例外を投げずに正常終了すること（呼び出し元=AFTER_COMMITリスナーの実行スレッドを巻き込まない）。
        listener.onEventAdvanceNoticeNotification(lateEvent());

        // 組み立てが失敗したため、配送は一切試みられない。
        verifyNoInteractions(notificationDeliveryRunner);
    }
}
