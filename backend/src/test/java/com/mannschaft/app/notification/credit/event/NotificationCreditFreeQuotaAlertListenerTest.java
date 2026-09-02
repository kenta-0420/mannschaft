package com.mannschaft.app.notification.credit.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationCreditFreeQuotaAlertListener} の単体テスト（Issue #2990 L2）。
 *
 * <p>{@code NotificationCreditService#sendFreeQuotaAlertAsync} を廃してリスナーへ移したため、
 * Issue #2715 CMP-055 ロットC-1 が {@code NotificationCreditServiceTest} に置いていた
 * <b>本文 i18n の検証をここへ引き継ぐ</b>（en で件名・本文が英語になり、プレースホルダが残らないこと）。
 * あわせて #2990 の要点である<b>受信者ごとの隔離</b>（1 人の配送失敗が他を巻き添えにしない）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCreditFreeQuotaAlertListener 単体テスト")
class NotificationCreditFreeQuotaAlertListenerTest {

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private RoleService roleService;

    @Mock
    private UserLocaleCache userLocaleCache;

    private NotificationCreditFreeQuotaAlertListener listener;

    private NotificationCreditFreeQuotaAlertListener newListener() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return new NotificationCreditFreeQuotaAlertListener(
                notificationDeliveryRunner, roleService, userLocaleCache, messageSource);
    }

    @Test
    @DisplayName("受信者 locale が en なら件名・本文が英語になりプレースホルダが残らない（#2715 ロットC-1 の引き継ぎ）")
    void en_localizesTitleAndBody() {
        listener = newListener();
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));
        given(userLocaleCache.getLocales(List.of(5L))).willReturn(Map.of(5L, "en"));
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onNotificationCreditFreeQuotaAlert(new NotificationCreditFreeQuotaAlertEvent(1L));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(5L);
        assertThat(request.notificationType()).isEqualTo("NOTIFICATION_CREDIT_ALERT");
        assertThat(request.sourceType()).isEqualTo("NOTIFICATION_CREDIT");
        assertThat(request.sourceId()).isEqualTo(1L);
        assertThat(request.title()).isEqualTo("Your free notification quota is running low");
        assertThat(request.body()).doesNotContain("{0}").contains("90%");
    }

    @Test
    @DisplayName("1人の配送失敗が他の受信者を巻き添えにしない（受信者ごとの隔離）")
    void 受信者ごとに隔離される() {
        listener = newListener();
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L, 6L, 7L));
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);
        willThrow(new RuntimeException("模擬配送失敗"))
                .given(notificationDeliveryRunner)
                .sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && r.recipientUserId() == 6L));

        listener.onNotificationCreditFreeQuotaAlert(new NotificationCreditFreeQuotaAlertEvent(1L));

        // 3 人ぶん試行されている（6 番の失敗で 7 番が飛ばされない）。
        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("受信者解決に失敗したら配送を中止する（握りつぶさずログのみ・誰にも送らない）")
    void 受信者解決失敗で配送中止() {
        listener = newListener();
        given(roleService.getAdminUserIdsByOrganizationId(1L))
                .willThrow(new RuntimeException("模擬 role 解決失敗"));

        listener.onNotificationCreditFreeQuotaAlert(new NotificationCreditFreeQuotaAlertEvent(1L));

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }
}
