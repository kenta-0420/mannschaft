package com.mannschaft.app.reservation.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner.Target;
import com.mannschaft.app.reservation.service.ReservationWaitlistNotifyRunner.Targets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationWaitlistNotificationEventListener} の単体テスト（F03.4.5 §6.1 W-2 / Issue #2997 G3）。
 *
 * <p>空き復帰で WAITING 全員へ HIGH 通知する／受信者 locale で文面を組み立てる／行ごとの条件付き claim（取れなければ送らない）／
 * 例外なら prev に戻す／deny は戻さない／戻しの失敗でも例外を外に出さない／1 人の失敗が他の行に波及しないことを検証する。
 * DB を伴う振る舞い（実 claim・戻し・古い戻しが新しい claim を消さない）は
 * {@code ReservationNotificationTransactionIT} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationWaitlistNotificationEventListener 単体テスト（F03.4.5 §6.1 キャンセル待ち）")
class ReservationWaitlistNotificationEventListenerTest {

    private static final Long TEAM_ID = 700L;
    private static final Long SLOT_ID = 8001L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock
    private ReservationWaitlistNotifyRunner notifyRunner;
    @Mock
    private NotificationDeliveryRunner deliveryRunner;
    @Mock
    private UserLocaleCache userLocaleCache;

    /** 実プロパティファイル（messages*.properties）を読む実体（i18n の locale 分岐を実データで検証するため）。 */
    private final ReloadableResourceBundleMessageSource messageSource = realMessageSource();

    private ReservationWaitlistNotificationEventListener listener;

    private static ReloadableResourceBundleMessageSource realMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @BeforeEach
    void setUp() {
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        listener = new ReservationWaitlistNotificationEventListener(
                notifyRunner, deliveryRunner, userLocaleCache, messageSource, CLOCK);
    }

    private static Target target(long userId, LocalDateTime prev) {
        return new Target(UUID.randomUUID(), userId, prev == null ? null : prev.toInstant(ZoneOffset.UTC));
    }

    private void givenTargets(Target... targets) {
        given(notifyRunner.findTargets(eq(SLOT_ID), any())).willReturn(new Targets("枠A", List.of(targets)));
    }

    private void fire() {
        listener.onSlotReopened(new ReservationSlotReopenedEvent(TEAM_ID, SLOT_ID));
    }

    @Test
    @DisplayName("W-2: 空き復帰で WAITING 全員を claim し、HIGH 通知を sendOne で配送する（preference を 1 行ずつ）")
    void 空き復帰で全員通知() {
        Target t1 = target(901L, null);
        Target t2 = target(902L, null);
        givenTargets(t1, t2);
        given(notifyRunner.claim(any(), any(), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        verify(notifyRunner).claim(eq(t1.entryId()), eq(NOW), eq(NOW.minusMinutes(60)));
        verify(notifyRunner).claim(eq(t2.entryId()), eq(NOW), eq(NOW.minusMinutes(60)));
        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(901L, 902L);
        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo("RESERVATION_WAITLIST_OPENING");
        assertThat(first.priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(first.sourceType()).isEqualTo("RESERVATION");
        assertThat(first.sourceId()).isEqualTo(SLOT_ID);
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(first.scopeId()).isEqualTo(TEAM_ID);
        assertThat(first.actionUrl()).isEqualTo("/teams/" + TEAM_ID + "/reservations");
        assertThat(first.actorId()).isNull();
        verify(notifyRunner, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("i18n: 受信者の locale が en の場合、通知本文が英語で組み立てられる")
    void 空き復帰通知は受信者localeで組み立てられる() {
        givenTargets(target(901L, null), target(902L, null));
        given(userLocaleCache.getLocale(901L)).willReturn("ja");
        given(userLocaleCache.getLocale(902L)).willReturn("en");
        given(notifyRunner.claim(any(), any(), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner, times(2)).sendOne(captor.capture());
        String jaBody = captor.getAllValues().get(0).body();
        String enBody = captor.getAllValues().get(1).body();
        assertThat(jaBody).contains("空きが出ました");
        assertThat(enBody).contains("slot has opened up");
        assertThat(jaBody).isNotEqualTo(enBody);
    }

    @Test
    @DisplayName("対象なし（枠が空いていない・WAITING 0 件・全員抑止期限内）は何も送らない")
    void 対象なしは何もしない() {
        given(notifyRunner.findTargets(eq(SLOT_ID), any())).willReturn(null);

        fire();

        verify(notifyRunner, never()).claim(any(), any(), any());
        verify(deliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("claim が取れない行（他の配送が担当済み／抑止期限内）は配送しない")
    void claimが取れない行は送らない() {
        Target taken = target(901L, null);
        Target free = target(902L, null);
        givenTargets(taken, free);
        given(notifyRunner.claim(eq(taken.entryId()), any(), any())).willReturn(false);
        given(notifyRunner.claim(eq(free.entryId()), any(), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        verify(deliveryRunner, times(1)).sendOne(any());
        verify(deliveryRunner, never()).sendOne(org.mockito.ArgumentMatchers.argThat(r -> r.recipientUserId() == 901L));
    }

    @Test
    @DisplayName("M2: 配送が例外なら、その行だけ claimedAt 照合で prev に戻す（他の行には波及しない）")
    void 例外なら該当行だけprevに戻す() {
        LocalDateTime prev = NOW.minusHours(3);
        Target failing = target(901L, prev);
        Target ok = target(902L, null);
        givenTargets(failing, ok);
        given(notifyRunner.claim(any(), any(), any())).willReturn(true);
        doThrow(new RuntimeException("模擬配送失敗")).when(deliveryRunner)
                .sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 901L));
        given(deliveryRunner.sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 902L)))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        verify(notifyRunner).release(failing.entryId(), NOW, prev.toInstant(ZoneOffset.UTC));
        verify(notifyRunner, never()).release(eq(ok.entryId()), any(), any());
        verify(deliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("M2: deny（VISIBILITY_DENIED）は戻さない（印を保持する）")
    void denyは戻さない() {
        givenTargets(target(901L, null));
        given(notifyRunner.claim(any(), any(), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        fire();

        verify(notifyRunner, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("M2: 戻しに失敗しても例外を外に出さず、後続の行を処理する")
    void 戻し失敗でも例外を出さない() {
        Target t1 = target(901L, null);
        Target t2 = target(902L, null);
        givenTargets(t1, t2);
        given(notifyRunner.claim(any(), any(), any())).willReturn(true);
        doThrow(new RuntimeException("模擬配送失敗")).when(deliveryRunner)
                .sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 901L));
        given(deliveryRunner.sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 902L)))
                .willReturn(NotificationDeliveryResult.DELIVERED);
        doThrow(new RuntimeException("模擬戻し失敗")).when(notifyRunner).release(eq(t1.entryId()), any(), any());

        fire();

        verify(notifyRunner).release(eq(t1.entryId()), any(), any());
        verify(deliveryRunner).sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 902L));
    }

    @Test
    @DisplayName("claim 自体が例外なら、その行は送らず後続の行を処理する")
    void claim例外でも後続を処理する() {
        Target t1 = target(901L, null);
        Target t2 = target(902L, null);
        givenTargets(t1, t2);
        given(notifyRunner.claim(eq(t1.entryId()), any(), any())).willThrow(new RuntimeException("模擬 claim 失敗"));
        given(notifyRunner.claim(eq(t2.entryId()), any(), any())).willReturn(true);
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        verify(deliveryRunner, times(1)).sendOne(any());
        verify(notifyRunner, never()).release(any(), any(), any());
    }

    @Test
    @DisplayName("対象取得が例外でも、リスナーは例外を外に出さない")
    void 対象取得の例外を握りつぶさずログのみ() {
        given(notifyRunner.findTargets(eq(SLOT_ID), any())).willThrow(new RuntimeException("模擬 DB 失敗"));

        fire();

        verify(deliveryRunner, never()).sendOne(any());
    }
}
