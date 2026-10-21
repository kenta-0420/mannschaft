package com.mannschaft.app.digest.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Issue #2997 G7-a: {@link DigestGenerationNotificationListener} のユニットテスト。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DigestGenerationNotificationListener ユニットテスト")
class DigestGenerationNotificationListenerTest {

    @Mock private NotificationDeliveryRunner deliveryRunner;
    @Mock private UserLocaleCache userLocaleCache;

    private DigestGenerationNotificationListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(userLocaleCache.getLocale(any())).thenReturn("ja");
        listener = new DigestGenerationNotificationListener(deliveryRunner, userLocaleCache, new StaticMessageSource());
    }

    @Test
    @DisplayName("完了イベントは作成者へ DIGEST_COMPLETED（NORMAL・/digests/{id}・TEAM スコープ）を配送する")
    void 完了通知を配送する() {
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onDigestGenerationNotification(new DigestGenerationNotificationEvent(
                7L, DigestGenerationNotificationEvent.Kind.COMPLETED, 100L, "TEAM", 50L));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest r = captor.getValue();
        assertThat(r.recipientUserId()).isEqualTo(100L);
        assertThat(r.notificationType()).isEqualTo("DIGEST_COMPLETED");
        assertThat(r.priority()).isEqualTo(NotificationPriority.NORMAL);
        assertThat(r.sourceType()).isEqualTo("DIGEST");
        assertThat(r.sourceId()).isEqualTo(7L);
        assertThat(r.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(r.scopeId()).isEqualTo(50L);
        assertThat(r.actionUrl()).isEqualTo("/digests/7");
        assertThat(r.title()).isEqualTo("ダイジェスト生成完了");
    }

    @Test
    @DisplayName("失敗イベントは DIGEST_FAILED（HIGH）を配送し、TEAM 以外のスコープは ORGANIZATION になる")
    void 失敗通知を配送する() {
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onDigestGenerationNotification(new DigestGenerationNotificationEvent(
                7L, DigestGenerationNotificationEvent.Kind.FAILED, 100L, "ORGANIZATION", 9L));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().notificationType()).isEqualTo("DIGEST_FAILED");
        assertThat(captor.getValue().priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(captor.getValue().scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("triggeredBy が null なら配送しない")
    void 起動者不明なら配送しない() {
        listener.onDigestGenerationNotification(new DigestGenerationNotificationEvent(
                7L, DigestGenerationNotificationEvent.Kind.COMPLETED, null, "TEAM", 50L));

        verify(deliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("配送が例外になっても例外を外へ出さない（確定済みのダイジェストに影響しない）")
    void 配送失敗は外へ出さない() {
        given(deliveryRunner.sendOne(any())).willThrow(new RuntimeException("DB 障害（検証用）"));

        assertThatCode(() -> listener.onDigestGenerationNotification(new DigestGenerationNotificationEvent(
                7L, DigestGenerationNotificationEvent.Kind.COMPLETED, 100L, "TEAM", 50L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("visibility deny でも例外を出さずに終える")
    void deny_でも例外を出さない() {
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onDigestGenerationNotification(new DigestGenerationNotificationEvent(
                7L, DigestGenerationNotificationEvent.Kind.COMPLETED, 100L, "TEAM", 50L)))
                .doesNotThrowAnyException();
    }
}
