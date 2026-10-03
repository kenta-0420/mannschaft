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

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * Issue #2715 CMP-055 ロットC-1 — {@link NotificationCreditAlertSender} の単体テスト。
 * 各アラートの件名・本文が受信者 locale で組み立てられることを検証する。
 *
 * <p>Issue #2990 L4: 期限アラート2種の検体は {@code NotificationCreditExpiryBatchTest} にあったが、
 * 当該メソッドが本 Bean へ移設されたため一緒に移した。ADMIN の解決経路が
 * {@code UserRoleRepository}（Repository 直接）から {@code RoleService}（D-5 準拠）へ
 * 変わっている点以外、検証内容は移設前と同じである。</p>
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


    private void useRealMessageSource() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(sender, "messageSource", realMessageSource);
    }

    @Test
    @DisplayName("受信者 locale が en なら件名・本文が英語になりプレースホルダが残らない")
    void en_localizesTitleAndBody() {
        useRealMessageSource();

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

    @Test
    @DisplayName("sendExpiryAlert: 受信者 locale が en なら件名・本文が英語になる")
    void sendExpiryAlert_en() {
        useRealMessageSource();
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));

        sender.sendExpiryAlert(1L, 200L, LocalDate.of(2026, 9, 1), 30);

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
    @DisplayName("sendCreditExpiredAlert: 受信者 locale が en なら件名・本文が英語になる")
    void sendCreditExpiredAlert_en() {
        useRealMessageSource();
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));

        sender.sendCreditExpiredAlert(1L, 50L);

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

    // ─────────────────────────────────────────────────────────
    // Issue #2990 L4 再検分是正: 同期版（*Now）は送信失敗を握り潰さない
    // ─────────────────────────────────────────────────────────

    /**
     * 是正前は {@code *Now} の本体が {@code catch (Exception)} で送信失敗を飲み込んでいた。
     * バッチの投入拒否フォールバックは {@code *Now} を呼ぶため、
     * <b>フォールバック経路の失敗まで静かに消え</b>、通知が失われたことを誰も知り得なかった。
     */
    @Test
    @DisplayName("sendExpiryAlertNow: 送信失敗は握り潰さず呼び出し元へ投げる")
    void sendExpiryAlertNow_propagatesFailure() {
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));
        willThrow(new IllegalStateException("配送基盤の障害"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), anyString(), anyString(), any(), any(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> sender.sendExpiryAlertNow(1L, 200L, LocalDate.of(2026, 9, 1), 30))
                .as("同期フォールバック経路の失敗は呼び出し元（バッチ）が ERROR ログに出せるよう伝播すること")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sendCreditExpiredAlertNow: 送信失敗は握り潰さず呼び出し元へ投げる")
    void sendCreditExpiredAlertNow_propagatesFailure() {
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));
        willThrow(new IllegalStateException("配送基盤の障害"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), anyString(), anyString(), any(), any(), any(), anyString(), any(), any());

        assertThatThrownBy(() -> sender.sendCreditExpiredAlertNow(1L, 50L))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 非同期経路には例外を受け取る呼び出し元がいない（{@code @Async} の戻り値は void）。
     * こちらは ERROR ログ化したうえで飲み込むのが正しい。
     */
    @Test
    @DisplayName("sendExpiryAlert（非同期経路）は例外を外へ漏らさない")
    void sendExpiryAlert_swallowsAfterLogging() {
        given(roleService.getAdminUserIdsByOrganizationId(1L)).willReturn(List.of(5L));
        willThrow(new IllegalStateException("配送基盤の障害"))
                .given(notificationHelper).notifyAllLocalized(
                        any(), anyString(), anyString(), any(), any(), any(), anyString(), any(), any());

        sender.sendExpiryAlert(1L, 200L, LocalDate.of(2026, 9, 1), 30);
        sender.sendCreditExpiredAlert(1L, 50L);
    }
}
