package com.mannschaft.app.reservation.event;

import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationAdminNotificationEventListener} の単体テスト。
 *
 * <p>予約枠の title は nullable。title が null・空・空白のみでも通知文に「null」が混ざらず、
 * 共通の既定名へフォールバックすることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationAdminNotificationEventListener 単体テスト")
class ReservationAdminNotificationEventListenerTest {

    private static final Long TEAM_ID = 10L;
    private static final Long RESERVATION_ID = 20L;
    private static final Long ACTOR_ID = 30L;
    private static final Long ADMIN_ID = 40L;

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AdminBusinessAlertService adminBusinessAlertService;

    private ReservationAdminNotificationEventListener listener;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        listener = new ReservationAdminNotificationEventListener(
                userRoleRepository, userLocaleCache, notificationService,
                adminBusinessAlertService, messageSource);
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(TEAM_ID, "MANAGE_RESERVATIONS"))
                .thenReturn(List.of());
        when(userLocaleCache.getLocale(ADMIN_ID)).thenReturn("ja");
    }

    private String captureBody(NotificationPriority priority) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                eq(ADMIN_ID), any(String.class), eq(priority), any(String.class), body.capture(),
                eq("RESERVATION"), eq(RESERVATION_ID), eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                any(String.class), anyLong());
        return body.getValue();
    }

    @Test
    @DisplayName("予約作成(AUTO): title が null/空/空白でも本文に null を含まず既定名になる")
    void 予約作成_AUTO_タイトル未設定は既定名() {
        for (String blank : new String[]{null, "", "   "}) {
            clearInvocations(notificationService);
            listener.onReservationCreated(new ReservationCreatedEvent(
                    TEAM_ID, RESERVATION_ID, ACTOR_ID, ApprovalMode.AUTO, blank, "2026/11/10 10:00"));

            String body = captureBody(NotificationPriority.HIGH);
            assertThat(body).as("title=[%s]", blank).doesNotContain("null");
            assertThat(body).as("title=[%s]", blank).isEqualTo("ご予約が予約されました（2026/11/10 10:00）");
        }
    }

    @Test
    @DisplayName("予約作成(MANUAL): title が null でも本文に null を含まず既定名になる")
    void 予約作成_MANUAL_タイトル未設定は既定名() {
        listener.onReservationCreated(new ReservationCreatedEvent(
                TEAM_ID, RESERVATION_ID, ACTOR_ID, ApprovalMode.MANUAL, null, "2026/11/10 10:00"));

        String body = captureBody(NotificationPriority.HIGH);
        assertThat(body).doesNotContain("null");
        assertThat(body).isEqualTo("ご予約への予約申込があります（2026/11/10 10:00）");
    }

    @Test
    @DisplayName("予約作成: title があればそのまま表示される（回帰）")
    void 予約作成_タイトルありはそのまま() {
        listener.onReservationCreated(new ReservationCreatedEvent(
                TEAM_ID, RESERVATION_ID, ACTOR_ID, ApprovalMode.AUTO, "カット", "2026/11/10 10:00"));

        assertThat(captureBody(NotificationPriority.HIGH)).isEqualTo("カットが予約されました（2026/11/10 10:00）");
    }

    @Test
    @DisplayName("メンバーキャンセル: title が null/空/空白でも本文に null を含まない")
    void キャンセル_タイトル未設定は既定名() {
        for (String blank : new String[]{null, "", "   "}) {
            clearInvocations(notificationService);
            listener.onReservationCancelledByMember(new ReservationCancelledByMemberEvent(
                    TEAM_ID, RESERVATION_ID, ACTOR_ID, blank));

            String body = captureBody(NotificationPriority.NORMAL);
            assertThat(body).as("title=[%s]", blank).doesNotContain("null");
            assertThat(body).as("title=[%s]", blank).isEqualTo("ご予約の予約がキャンセルされました");
        }
    }
}
