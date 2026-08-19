package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Issue #2715 CMP-055 ロットC-1 — {@link NotificationCreditExpiryBatch} の単体テスト。
 * 有効期限アラート・失効アラートの件名・本文が受信者 locale で組み立てられることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCreditExpiryBatch 単体テスト")
class NotificationCreditExpiryBatchTest {

    @Mock
    private NotificationCreditPurchaseRepository purchaseRepository;
    @Mock
    private OrganizationNotificationBalanceRepository balanceRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private NotificationCreditExpiryBatch batch;

    private void useRealMessageSource() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(batch, "messageSource", realMessageSource);
    }

    @Test
    @DisplayName("sendExpiryAlertAsync: 受信者 locale が en なら件名・本文が英語になる")
    void sendExpiryAlertAsync_en() {
        useRealMessageSource();
        given(userRoleRepository.findAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));

        ReflectionTestUtils.invokeMethod(batch, "sendExpiryAlertAsync",
                1L, 200L, LocalDateTime.of(2026, 9, 1, 0, 0), 30);

        ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> builderCaptor =
                ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
        verify(notificationHelper).notifyAllLocalized(
                eq(List.of(5L)),
                eq("NOTIFICATION_CREDIT_EXPIRY_ALERT"),
                eq("NOTIFICATION_CREDIT"),
                eq(1L),
                eq(NotificationScopeType.ORGANIZATION),
                eq(1L),
                anyString(),
                isNull(),
                builderCaptor.capture());

        NotificationHelper.LocalizedMessage message = builderCaptor.getValue().build(5L, Locale.ENGLISH);
        assertThat(message.title()).isEqualTo("Your notification credits expire in 30 day(s)");
        assertThat(message.body()).doesNotContain("{0}").doesNotContain("{1}")
                .contains("200").contains("2026-09-01");
    }

    @Test
    @DisplayName("sendCreditExpiredAlertAsync: 受信者 locale が en なら件名・本文が英語になる")
    void sendCreditExpiredAlertAsync_en() {
        useRealMessageSource();
        given(userRoleRepository.findAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));

        ReflectionTestUtils.invokeMethod(batch, "sendCreditExpiredAlertAsync", 1L, 50L);

        ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> builderCaptor =
                ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
        verify(notificationHelper).notifyAllLocalized(
                eq(List.of(5L)),
                eq("NOTIFICATION_CREDIT_EXPIRED"),
                eq("NOTIFICATION_CREDIT"),
                eq(1L),
                eq(NotificationScopeType.ORGANIZATION),
                eq(1L),
                anyString(),
                isNull(),
                builderCaptor.capture());

        NotificationHelper.LocalizedMessage message = builderCaptor.getValue().build(5L, Locale.ENGLISH);
        assertThat(message.title()).isEqualTo("Notification credits have expired");
        assertThat(message.body()).doesNotContain("{0}").contains("50");
    }
}
