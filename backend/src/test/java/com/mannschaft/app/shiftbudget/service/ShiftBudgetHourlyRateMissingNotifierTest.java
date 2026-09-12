package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.event.HourlyRateMissingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetHourlyRateMissingNotifier} 単体テスト（CMP-260910-1555）。
 *
 * <p>本サービスは「時給未設定で消化記録をスキップした」ことを利用者へ届ける唯一の経路であり、
 * ここが黙ると是正前（0 円で静かに成功扱い）と同じ状態に戻る。したがって
 * <b>届いたこと・届かなかったときに再送経路へ載ること</b>を試す。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetHourlyRateMissingNotifier 単体テスト")
class ShiftBudgetHourlyRateMissingNotifierTest {

    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 12L;
    private static final Long SCHEDULE_ID = 244L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private RoleService roleService;
    @Mock
    private ShiftBudgetFailedEventService failedEventService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ShiftBudgetHourlyRateMissingNotifier notifier;

    private void givenRecipients(List<Long> admins, List<Long> budgetAdmins) {
        given(roleService.getAdminUserIdsByOrganizationId(ORG_ID)).willReturn(admins);
        given(roleService.getUserIdsByOrganizationIdAndPermissionName(ORG_ID, "BUDGET_ADMIN"))
                .willReturn(budgetAdmins);
    }

    private void givenMessages() {
        given(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .willReturn("メッセージ");
    }

    @Test
    @DisplayName("時給未設定ユーザーがいれば予算管理者全員へ通知し監査ログを残す")
    void 通知と監査ログ() {
        givenRecipients(List.of(10L, 11L), List.of(11L, 12L));
        givenMessages();
        given(userLocaleCache.getLocales(any())).willReturn(Map.of(10L, "ja", 11L, "en", 12L, "ja"));
        given(notificationDeliveryRunner.sendOne(any()))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        notifier.onHourlyRateMissing(event("team-alpha", List.of(100L, 101L)));

        // ADMIN ∪ BUDGET_ADMIN の重複を除いた 3 名へ配送される
        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(10L, 11L, 12L);
        assertThat(captor.getAllValues()).allSatisfy(req -> {
            assertThat(req.notificationType()).isEqualTo("SHIFT_BUDGET_HOURLY_RATE_MISSING");
            // 画面の入口（チーム設定 > 時給設定）へ誘導できていること
            assertThat(req.actionUrl()).isEqualTo("/teams/team-alpha/settings/hourly-rate");
        });
        verify(auditLogService).record(eq("SHIFT_BUDGET_HOURLY_RATE_MISSING"),
                any(), any(), eq(TEAM_ID), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("未設定ユーザーが 0 名なら何もしない（誤報を出さない）")
    void 未設定ゼロなら何もしない() {
        notifier.onHourlyRateMissing(event("team-alpha", List.of()));

        verify(notificationDeliveryRunner, never()).sendOne(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("1 名の配送失敗で他受信者を諦めず、失敗者だけ NOTIFICATION_SEND として再送経路へ載せる")
    void 配送失敗は失敗者のみ記録() {
        givenRecipients(List.of(10L, 11L), List.of());
        givenMessages();
        given(userLocaleCache.getLocales(any())).willReturn(Map.of(10L, "ja", 11L, "ja"));
        given(notificationDeliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("db down"))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        notifier.onHourlyRateMissing(event("team-alpha", List.of(100L)));

        // 1 名目が落ちても 2 名目の配送は試みられる
        verify(notificationDeliveryRunner, times(2)).sendOne(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(failedEventService).recordFailure(eq(ORG_ID),
                eq(ShiftBudgetFailedEventType.NOTIFICATION_SEND), eq(SCHEDULE_ID),
                payloadCaptor.capture(), anyString());
        // 成功した 11L を混ぜると再送で重複配信になる
        assertThat(payloadCaptor.getValue()).containsEntry("user_ids", List.of(10L));
        // 再送側が受信者 locale で組み立て直せるよう i18n キーを残す
        assertThat(payloadCaptor.getValue())
                .containsEntry("title_key", "notification.shiftBudget.hourlyRateMissing.title")
                .containsEntry("body_key", "notification.shiftBudget.hourlyRateMissing.body");
    }

    @Test
    @DisplayName("locale の一括解決が総崩れしたら全員を再送対象として記録する")
    void locale解決失敗は全員記録() {
        givenRecipients(List.of(10L, 11L), List.of());
        // failed event の payload には運用ログ用の ja 固定文言も載るため messageSource を使う
        givenMessages();
        willThrow(new RuntimeException("locale down")).given(userLocaleCache).getLocales(any());

        notifier.onHourlyRateMissing(event("team-alpha", List.of(100L)));

        verify(notificationDeliveryRunner, never()).sendOne(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(failedEventService).recordFailure(eq(ORG_ID),
                eq(ShiftBudgetFailedEventType.NOTIFICATION_SEND), eq(SCHEDULE_ID),
                payloadCaptor.capture(), anyString());
        assertThat(payloadCaptor.getValue()).containsEntry("user_ids", List.of(10L, 11L));
    }

    @Test
    @DisplayName("受信ロールが 0 名でも監査ログは残す（記録が消えない）")
    void 受信者ゼロでも監査ログ() {
        givenRecipients(List.of(), List.of());

        notifier.onHourlyRateMissing(event("team-alpha", List.of(100L)));

        verify(auditLogService).record(eq("SHIFT_BUDGET_HOURLY_RATE_MISSING"),
                any(), any(), eq(TEAM_ID), eq(ORG_ID), any(), any(), any(), any());
        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    private HourlyRateMissingEvent event(String teamSlug, List<Long> missingUserIds) {
        return new HourlyRateMissingEvent(
                ORG_ID, TEAM_ID, teamSlug, SCHEDULE_ID, missingUserIds);
    }
}
