package com.mannschaft.app.reservation.event;

import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationAdminNotificationEventListener} の単体テスト（CMP-261028-2328）。
 *
 * <p>管理者向けの予約通知の遷移先が、数値 teamId ではなく slug（{@code /teams/{slug}/reservations}）で
 * 組み立てられること、slug が解決できないときは数値 URL を作らず {@code /notifications} になることを検証する。
 * FE のルートは {@code pages/teams/[slug]/reservations.vue} で slug しか受けない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationAdminNotificationEventListener 単体テスト")
class ReservationAdminNotificationEventListenerTest {

    private static final Long TEAM_ID = 10L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long ACTOR_ID = 1L;
    private static final Long ADMIN_ID = 50L;

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AdminBusinessAlertService adminBusinessAlertService;
    @Mock
    private TeamService teamService;

    private ReservationAdminNotificationEventListener listener;

    @BeforeEach
    void setUp() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        listener = new ReservationAdminNotificationEventListener(
                userRoleRepository, userLocaleCache, notificationService,
                adminBusinessAlertService, ms);
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "teamService", teamService);
        lenient().when(userLocaleCache.getLocale(ADMIN_ID)).thenReturn("ja");
        lenient().when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        lenient().when(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(TEAM_ID, "MANAGE_RESERVATIONS"))
                .thenReturn(List.of());
    }

    private ReservationCreatedEvent createdEvent() {
        return new ReservationCreatedEvent(
                TEAM_ID, RESERVATION_ID, ACTOR_ID, ApprovalMode.AUTO, "整体60分", "4月2日 10:00");
    }

    private ReservationCancelledByMemberEvent cancelledEvent() {
        return new ReservationCancelledByMemberEvent(TEAM_ID, RESERVATION_ID, ACTOR_ID, "整体60分");
    }

    private void verifyActionUrl(String expected) {
        verify(notificationService).createNotification(
                eq(ADMIN_ID), anyString(), any(), anyString(), anyString(),
                eq("RESERVATION"), eq(RESERVATION_ID), any(), eq(TEAM_ID),
                eq(expected), eq(ACTOR_ID));
    }

    @Test
    @DisplayName("AC1: 予約作成の管理者通知は /teams/{slug}/reservations へ遷移する")
    void 予約作成は_slug_の遷移先() {
        given(teamService.getSlugById(TEAM_ID)).willReturn("my-team");

        listener.onReservationCreated(createdEvent());

        verifyActionUrl("/teams/my-team/reservations");
    }

    @Test
    @DisplayName("AC2: 予約作成で slug が解決できない（null）とき /notifications になり数値 URL は作られない")
    void 予約作成で_slug_null_なら通知一覧() {
        given(teamService.getSlugById(TEAM_ID)).willReturn(null);

        listener.onReservationCreated(createdEvent());

        verifyActionUrl("/notifications");
    }

    @Test
    @DisplayName("AC1: メンバーキャンセルの管理者通知は /teams/{slug}/reservations へ遷移する")
    void メンバーキャンセルは_slug_の遷移先() {
        given(teamService.getSlugById(TEAM_ID)).willReturn("my-team");

        listener.onReservationCancelledByMember(cancelledEvent());

        verifyActionUrl("/teams/my-team/reservations");
    }

    @Test
    @DisplayName("AC2: メンバーキャンセルで slug が解決できない（null）とき /notifications になり数値 URL は作られない")
    void メンバーキャンセルで_slug_null_なら通知一覧() {
        given(teamService.getSlugById(TEAM_ID)).willReturn(null);

        listener.onReservationCancelledByMember(cancelledEvent());

        verifyActionUrl("/notifications");
    }

    @Test
    @DisplayName("受信者が複数でも slug の解決はイベントごとに 1 回だけ")
    void 受信者が複数でも_slug_解決は1回() {
        given(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).willReturn(List.of(ADMIN_ID, 51L, 52L));
        lenient().when(userLocaleCache.getLocale(51L)).thenReturn("ja");
        lenient().when(userLocaleCache.getLocale(52L)).thenReturn("ja");
        given(teamService.getSlugById(TEAM_ID)).willReturn("my-team");

        listener.onReservationCreated(createdEvent());

        verify(teamService, times(1)).getSlugById(TEAM_ID);
    }
}
