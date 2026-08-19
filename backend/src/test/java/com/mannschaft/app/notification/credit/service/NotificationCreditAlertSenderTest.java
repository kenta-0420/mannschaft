package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Issue #2715 CMP-055 ロットC-1 — {@link NotificationCreditAlertSender} の単体テスト。
 * 残高マイナスアラートの件名・本文が受信者 locale で組み立てられることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCreditAlertSender 単体テスト")
class NotificationCreditAlertSenderTest {

    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private NotificationCreditAlertSender sender;

    @Test
    @DisplayName("受信者 locale が en なら件名・本文が英語になりプレースホルダが残らない")
    void en_localizesTitleAndBody() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(sender, "messageSource", realMessageSource);

        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));

        sender.sendNegativeBalanceAlert(1L, -500L);

        ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> builderCaptor =
                ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
        verify(notificationHelper).notifyAllLocalized(
                eq(List.of(5L)),
                eq("NOTIFICATION_CREDIT_NEGATIVE"),
                eq("NOTIFICATION_CREDIT"),
                eq(1L),
                eq(NotificationScopeType.ORGANIZATION),
                eq(1L),
                anyString(),
                isNull(),
                builderCaptor.capture());

        NotificationHelper.LocalizedMessage message = builderCaptor.getValue().build(5L, Locale.ENGLISH);
        assertThat(message.title()).isEqualTo("Notification credit balance is negative");
        assertThat(message.body()).doesNotContain("{0}").contains("-500");
    }
}
