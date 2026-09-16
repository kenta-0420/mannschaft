package com.mannschaft.app.payment;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.event.MembershipPayerWithdrawalNotificationEvent;
import com.mannschaft.app.payment.event.MembershipPayerWithdrawalNotificationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 柱③-B PR-3（CMP-260901-1538）AC-13 の通知側 — {@link MembershipPayerWithdrawalNotificationListener} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MembershipPayerWithdrawalNotificationListener 単体テスト（柱③-B PR-3・AC-13）")
class MembershipPayerWithdrawalNotificationListenerTest {

    private static final UUID SUBSCRIPTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final Long BENEFICIARY = 4002L;
    private static final Long PAYER = 4001L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private MembershipPayerWithdrawalNotificationListener listener;

    private static MembershipPayerWithdrawalNotificationEvent event(ScopeKind scopeKind, Long beneficiary) {
        return new MembershipPayerWithdrawalNotificationEvent(
                SUBSCRIPTION_ID, beneficiary, scopeKind, 30L, LocalDate.of(2026, 10, 31), PAYER);
    }

    @Test
    @DisplayName("AC-13: 受益者へ期末解約の予告通知を配送する")
    void 受益者へ配送する() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of(BENEFICIARY, "ja"));
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onMembershipPayerWithdrawalNotification(event(ScopeKind.TEAM, BENEFICIARY));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(BENEFICIARY);
        assertThat(request.notificationType()).isEqualTo("MEMBERSHIP_PAYER_WITHDRAWAL_CANCELLED");
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(request.actorId()).isEqualTo(PAYER);
        assertThat(request.body()).contains("2026-10-31");
    }

    @Test
    @DisplayName("ORG スコープは ORGANIZATION へ綴り変換される（valueOf(\"ORG\") は即死する）")
    void ORGはORGANIZATIONへ変換される() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onMembershipPayerWithdrawalNotification(event(ScopeKind.ORG, BENEFICIARY));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("受益者不在なら配送しない")
    void 受益者不在ならスキップする() {
        listener.onMembershipPayerWithdrawalNotification(event(ScopeKind.TEAM, null));

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("配送失敗は呼び出し元へ伝播しない（AFTER_COMMIT の非同期リスナー）")
    void 配送失敗は伝播しない() {
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2));
        willThrow(new IllegalStateException("通知基盤障害"))
                .given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> listener.onMembershipPayerWithdrawalNotification(
                event(ScopeKind.TEAM, BENEFICIARY))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("契約: AFTER_COMMIT かつ event-pool で非同期に発火する")
    void コミット後に非同期で発火する() throws NoSuchMethodException {
        Method method = MembershipPayerWithdrawalNotificationListener.class.getMethod(
                "onMembershipPayerWithdrawalNotification", MembershipPayerWithdrawalNotificationEvent.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo("event-pool");
    }

    @Test
    @DisplayName("locale 解決の失敗は既定 locale で継続する")
    void locale解決失敗でも配送する() {
        given(userLocaleCache.getLocales(List.of(BENEFICIARY)))
                .willThrow(new IllegalStateException("cache 障害"));
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onMembershipPayerWithdrawalNotification(event(ScopeKind.TEAM, BENEFICIARY));

        verify(notificationDeliveryRunner).sendOne(any());
    }
}
