package com.mannschaft.app.contact;



import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.contact.event.ContactInviteUsedNotificationEvent;
import com.mannschaft.app.contact.event.ContactInviteUsedNotificationListener;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2834 / CMP-056 第1群ロットA — {@link ContactInviteUsedNotificationListener} のユニットテスト。
 *
 * <p>是正前の {@code ContactInviteTokenService#sendInviteUsedNotification} が業務トランザクション内で
 * 行っていた組み立て（アクター名・ロケール・件名/本文）が、そのままリスナー側へ移り、
 * かつ表示内容（{@code sourceType} / {@code sourceId} / 遷移先 / アクター名フォールバック）が
 * 変わっていないことの番人。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContactInviteUsedNotificationListener ユニットテスト")
class ContactInviteUsedNotificationListenerTest {

    private static final Long ACTOR_ID = 100L;
    private static final Long ISSUER_ID = 200L;
    private static final Long TOKEN_ID = 300L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private UserService userService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private ContactInviteUsedNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new ContactInviteUsedNotificationListener(
                notificationDeliveryRunner, userService, userLocaleCache, messageSource);
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private ContactInviteUsedNotificationEvent event() {
        return new ContactInviteUsedNotificationEvent(ACTOR_ID, ISSUER_ID, TOKEN_ID);
    }

    @Test
    @DisplayName("発行者宛に1件、Runner経由で送られ、本文にアクター名が入る")
    void 発行者宛に1件送られる() {
        given(userService.getFullName(ACTOR_ID)).willReturn(Optional.of("招待 太郎"));
        given(notificationDeliveryRunner.sendOne(any()))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onContactInviteUsedNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(ISSUER_ID);
        assertThat(request.notificationType()).isEqualTo("CONTACT_INVITE_USED");
        assertThat(request.body()).contains("招待 太郎");
        assertThat(request.sourceType()).isEqualTo("CONTACT_INVITE_TOKEN");
        assertThat(request.sourceId()).isEqualTo(TOKEN_ID);
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.PERSONAL);
        assertThat(request.scopeId()).isEqualTo(ISSUER_ID);
        assertThat(request.actionUrl()).isEqualTo("/settings/contact-invite-tokens");
        assertThat(request.actorId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("アクターが解決できない場合は既定表示名にフォールバックする（是正前の挙動を維持）")
    void アクター未解決時は既定表示名になる() {
        given(userService.getFullName(ACTOR_ID)).willReturn(Optional.empty());
        given(notificationDeliveryRunner.sendOne(any()))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onContactInviteUsedNotification(event());

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().body()).contains("ユーザー");
    }

    @Test
    @DisplayName("AC-4: visibility deny（null 復帰）は例外扱いせず、リスナーは正常終了する")
    void denyは例外扱いされない() {
        given(userService.getFullName(ACTOR_ID)).willReturn(Optional.of("招待 太郎"));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onContactInviteUsedNotification(event()))
                .doesNotThrowAnyException();

        verify(notificationDeliveryRunner).sendOne(any());
    }

    @Test
    @DisplayName("AC-1(非TX側): 配送のDB例外は呼び出し元へ伝播しない")
    void 配送例外は呼び出し元へ伝播しない() {
        given(userService.getFullName(ACTOR_ID)).willReturn(Optional.of("招待 太郎"));
        willThrow(new RuntimeException("模擬通知配送失敗"))
                .given(notificationDeliveryRunner).sendOne(any());

        assertThatCode(() -> listener.onContactInviteUsedNotification(event()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("組み立て中（getFullName）の例外も呼び出し元へ伝播せず、配送は試みられない")
    void 組み立て例外は呼び出し元へ伝播しない() {
        given(userService.getFullName(ACTOR_ID)).willThrow(new RuntimeException("ユーザー解決失敗"));

        assertThatCode(() -> listener.onContactInviteUsedNotification(event()))
                .doesNotThrowAnyException();

        verifyNoInteractions(notificationDeliveryRunner);
    }
}
