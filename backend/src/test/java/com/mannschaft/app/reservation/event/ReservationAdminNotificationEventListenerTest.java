package com.mannschaft.app.reservation.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * CMP-261030-0648: 管理者向け予約通知（{@link ReservationAdminNotificationEventListener}）の actionUrl を slug にする。
 *
 * <p>FE のルートは {@code /teams/[slug]/...} しか受けないため、数値 teamId の URL では予約画面に着かない。
 * slug が解決できない（null・例外）ときは数値 URL を作らず {@code /notifications} で通知を送る。
 * slug は宛先ループの前に1回だけ解決する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationAdminNotificationEventListener — 管理者向け予約通知の遷移先 slug 化（CMP-261030-0648）")
class ReservationAdminNotificationEventListenerTest {

    private static final long TEAM_ID = 100L;
    private static final long OTHER_TEAM_ID = 200L;
    private static final long RESERVATION_ID = 9001L;
    private static final long ACTOR_ID = 42L;
    private static final long ADMIN_A = 10L;
    private static final long ADMIN_B = 11L;
    private static final long DEPUTY_C = 12L;
    private static final String SLUG = "lions-fc";
    /** 30文字・ハイフン入りの slug（AC-12）。 */
    private static final String LONG_SLUG = "abcdefghij-klmnopqrs-tuvwxyz01";
    private static final String NUMERIC_TEAM_URL = ".*/teams/\\d+(/|\\?|$).*";

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AdminBusinessAlertService adminBusinessAlertService;
    @Mock
    private MessageSource messageSource;
    @Mock
    private TeamService teamService;

    private ReservationAdminNotificationEventListener listener;
    private ListAppender<ILoggingEvent> logs;
    private Logger listenerLogger;
    /** 対象ロガーの元のレベル（null なら継承。TEST_CONVENTION §2.5）。 */
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        // AC-5: TeamService はコンストラクタで受け取る（フィールド注入ではない）
        listener = new ReservationAdminNotificationEventListener(
                userRoleRepository, userLocaleCache, notificationService,
                adminBusinessAlertService, messageSource, teamService);
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenReturn("stub-message");
        listenerLogger = (Logger) LoggerFactory.getLogger(ReservationAdminNotificationEventListener.class);
        // TEST_CONVENTION §2.5: 同じフォークの先行テストが残したレベルに左右されないよう、実効レベルを自ら設定する
        originalLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logs);
        listenerLogger.setLevel(originalLevel);
    }

    private void stubRecipients(Long teamId, List<Long> admins, List<Long> deputies) {
        if (teamId == null) {
            when(userRoleRepository.findAdminUserIdsByTeamId(isNull())).thenReturn(admins);
            when(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(isNull(), eq("MANAGE_RESERVATIONS")))
                    .thenReturn(deputies);
            return;
        }
        when(userRoleRepository.findAdminUserIdsByTeamId(teamId)).thenReturn(admins);
        when(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(teamId, "MANAGE_RESERVATIONS"))
                .thenReturn(deputies);
    }

    private static ReservationCreatedEvent created(Long teamId, ApprovalMode mode) {
        return new ReservationCreatedEvent(teamId, RESERVATION_ID, ACTOR_ID, mode, "テニスコート", "2026-11-10 10:00");
    }

    private static ReservationCancelledByMemberEvent cancelled(Long teamId) {
        return new ReservationCancelledByMemberEvent(teamId, RESERVATION_ID, ACTOR_ID, "テニスコート");
    }

    /** 宛先ごとの actionUrl を取り出す（宛先 → URL の順で検証できるよう、宛先も併せて捕まえる）。 */
    private List<String> captureActionUrls(String type, int expectedCalls, ArgumentCaptor<Long> recipients) {
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(expectedCalls)).createNotification(
                recipients.capture(), eq(type), any(NotificationPriority.class), anyString(), anyString(),
                eq("RESERVATION"), eq(RESERVATION_ID), eq(NotificationScopeType.TEAM), any(),
                urls.capture(), eq(ACTOR_ID));
        return urls.getAllValues();
    }

    private List<String> captureActionUrls(String type, int expectedCalls) {
        return captureActionUrls(type, expectedCalls, ArgumentCaptor.forClass(Long.class));
    }

    @Nested
    @DisplayName("slug が解決できるとき")
    class SlugResolved {

        @Test
        @DisplayName("AC-1a/AC-4/AC-19a: 予約作成（AUTO）の通知は /teams/{slug}/reservations で、getSlugById は宛先が複数でも1回")
        void ac1a_auto_created_uses_slug_once() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A, ADMIN_B), List.of(DEPUTY_C));
            when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);

            listener.onReservationCreated(created(TEAM_ID, ApprovalMode.AUTO));

            List<String> urls = captureActionUrls("RESERVATION_RECEIVED", 3);
            assertThat(urls).containsOnly("/teams/" + SLUG + "/reservations");
            assertThat(urls).allSatisfy(u -> assertThat(u).doesNotMatch(NUMERIC_TEAM_URL));
            verify(teamService, times(1)).getSlugById(TEAM_ID);
        }

        @Test
        @DisplayName("AC-1b/AC-4: 予約作成（MANUAL）の承認待ち通知も /teams/{slug}/reservations")
        void ac1b_manual_created_uses_slug() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A), List.of());
            when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);

            listener.onReservationCreated(created(TEAM_ID, ApprovalMode.MANUAL));

            List<String> urls = captureActionUrls("RESERVATION_PENDING_APPROVAL", 1);
            assertThat(urls).containsExactly("/teams/" + SLUG + "/reservations");
            assertThat(urls.get(0)).doesNotMatch(NUMERIC_TEAM_URL);
        }

        @Test
        @DisplayName("AC-1c/AC-4/AC-19a: メンバーキャンセルの通知も /teams/{slug}/reservations で、getSlugById は1回")
        void ac1c_cancelled_uses_slug_once() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A, ADMIN_B), List.of());
            when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);

            listener.onReservationCancelledByMember(cancelled(TEAM_ID));

            List<String> urls = captureActionUrls("RESERVATION_CANCELLED_BY_MEMBER", 2);
            assertThat(urls).containsOnly("/teams/" + SLUG + "/reservations");
            assertThat(urls).allSatisfy(u -> assertThat(u).doesNotMatch(NUMERIC_TEAM_URL));
            verify(teamService, times(1)).getSlugById(TEAM_ID);
        }

        @Test
        @DisplayName("AC-12: 30文字・ハイフン入りの slug がそのまま URL に入る")
        void ac12_long_hyphenated_slug_is_used_verbatim() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A), List.of());
            when(teamService.getSlugById(TEAM_ID)).thenReturn(LONG_SLUG);

            listener.onReservationCreated(created(TEAM_ID, ApprovalMode.AUTO));

            assertThat(LONG_SLUG).hasSize(30);
            assertThat(captureActionUrls("RESERVATION_RECEIVED", 1))
                    .containsExactly("/teams/" + LONG_SLUG + "/reservations");
        }

        @Test
        @DisplayName("AC-17: getSlugById は event の teamId だけを引数に呼ばれ、URL はそのチームの slug")
        void ac17_slug_is_resolved_for_event_team_only() {
            stubRecipients(OTHER_TEAM_ID, List.of(ADMIN_A), List.of());
            when(teamService.getSlugById(OTHER_TEAM_ID)).thenReturn("other-team");

            listener.onReservationCreated(created(OTHER_TEAM_ID, ApprovalMode.AUTO));

            assertThat(captureActionUrls("RESERVATION_RECEIVED", 1))
                    .containsExactly("/teams/other-team/reservations");
            verify(teamService).getSlugById(OTHER_TEAM_ID);
            verifyNoMoreInteractions(teamService);
        }
    }

    @Nested
    @DisplayName("slug が解決できないとき（/notifications へ逃がす）")
    class SlugUnresolved {

        @Test
        @DisplayName("AC-8: slug が null（削除済み・存在しない）なら、自己除外後の受信者全員に /notifications で送る")
        void ac8_null_slug_falls_back_to_notifications_for_all() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A, ACTOR_ID), List.of(DEPUTY_C));
            when(teamService.getSlugById(TEAM_ID)).thenReturn(null);

            listener.onReservationCreated(created(TEAM_ID, ApprovalMode.AUTO));

            ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
            List<String> urls = captureActionUrls("RESERVATION_RECEIVED", 2, recipients);
            assertThat(recipients.getAllValues()).containsExactlyInAnyOrder(ADMIN_A, DEPUTY_C);
            assertThat(urls).containsOnly("/notifications");
        }

        @Test
        @DisplayName("AC-9: event の teamId が null でも例外にならず /notifications")
        void ac9_null_team_id_falls_back_without_exception() {
            stubRecipients(null, List.of(ADMIN_A), List.of());

            assertThatCode(() -> listener.onReservationCancelledByMember(cancelled(null)))
                    .doesNotThrowAnyException();

            assertThat(captureActionUrls("RESERVATION_CANCELLED_BY_MEMBER", 1)).containsExactly("/notifications");
        }

        @Test
        @DisplayName("AC-14/AC-23: getSlugById が例外でも、宛先集合を広げずに /notifications で作成要求し、例外は外に出ず WARN を出す")
        void ac14_slug_exception_falls_back_and_warns() {
            // AC-23: 宛先は ADMIN と MANAGE_RESERVATIONS 付き DEPUTY_ADMIN だけ（権限で絞った集合）。
            // 遷移先を /notifications に落としても、この集合の外へは送らない。
            stubRecipients(TEAM_ID, List.of(ADMIN_A, ADMIN_B), List.of());
            when(teamService.getSlugById(TEAM_ID)).thenThrow(new IllegalStateException("db down"));

            assertThatCode(() -> listener.onReservationCreated(created(TEAM_ID, ApprovalMode.MANUAL)))
                    .doesNotThrowAnyException();

            ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
            assertThat(captureActionUrls("RESERVATION_PENDING_APPROVAL", 2, recipients)).containsOnly("/notifications");
            assertThat(recipients.getAllValues()).containsExactlyInAnyOrder(ADMIN_A, ADMIN_B);
            verify(teamService, times(1)).getSlugById(TEAM_ID);
            assertThat(logs.list)
                    .anySatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getFormattedMessage()).contains("slug");
                    });
        }

        @Test
        @DisplayName("AC-14: メンバーキャンセルでも getSlugById の例外は外に出ず /notifications")
        void ac14_cancelled_slug_exception_falls_back() {
            stubRecipients(TEAM_ID, List.of(ADMIN_A), List.of());
            when(teamService.getSlugById(TEAM_ID)).thenThrow(new IllegalStateException("db down"));

            assertThatCode(() -> listener.onReservationCancelledByMember(cancelled(TEAM_ID)))
                    .doesNotThrowAnyException();

            assertThat(captureActionUrls("RESERVATION_CANCELLED_BY_MEMBER", 1)).containsExactly("/notifications");
        }
    }

    @Nested
    @DisplayName("宛先が0人のとき")
    class NoRecipients {

        @Test
        @DisplayName("AC-10/AC-19b: 宛先が0人なら slug を解決せず、通知も作らない")
        void ac10_no_recipients_skips_resolution() {
            stubRecipients(TEAM_ID, List.of(), List.of());

            listener.onReservationCreated(created(TEAM_ID, ApprovalMode.AUTO));

            verifyNoInteractions(teamService);
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("AC-10/AC-19b: 宛先が本人だけなら slug を解決せず、通知も作らない")
        void ac10_only_actor_skips_resolution() {
            stubRecipients(TEAM_ID, List.of(ACTOR_ID), List.of(ACTOR_ID));

            listener.onReservationCancelledByMember(cancelled(TEAM_ID));

            verifyNoInteractions(teamService);
            verifyNoInteractions(notificationService);
        }
    }

    @Test
    @DisplayName("AC-15: ある宛先の通知作成が失敗しても、ほかの宛先には届く")
    void ac15_failure_for_one_recipient_does_not_stop_others() {
        stubRecipients(TEAM_ID, List.of(ADMIN_A, ADMIN_B), List.of());
        when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);
        when(notificationService.createNotification(eq(ADMIN_A), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any())).thenThrow(new RuntimeException("boom"));

        listener.onReservationCreated(created(TEAM_ID, ApprovalMode.AUTO));

        verify(notificationService).createNotification(eq(ADMIN_B), eq("RESERVATION_RECEIVED"), any(), anyString(),
                anyString(), eq("RESERVATION"), eq(RESERVATION_ID), eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                eq("/teams/" + SLUG + "/reservations"), eq(ACTOR_ID));
        verify(adminBusinessAlertService).invalidateCache(ADMIN_B);
        verify(adminBusinessAlertService, never()).invalidateCache(ADMIN_A);
    }
}
