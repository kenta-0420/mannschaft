package com.mannschaft.app.chat.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * F10.7 {@link InquiryChatEventListener} 単体テスト（実機 E2E 障害の回帰防止）。
 *
 * <p>実機で「問い合わせ通知が一件も作成されない」障害を捕捉した。真因は 2 点:</p>
 * <ol>
 *   <li><b>sourceType/sourceId 不整合</b>: {@code sourceType="CHAT_MESSAGE"} なのに sourceId に
 *       <b>チャンネル ID</b> を渡していた。CHAT_MESSAGE の Resolver はメッセージ ID を期待するため、
 *       正しくは <b>メッセージ ID</b> を渡さねばならない。</li>
 *   <li><b>リアルタイム配信の未結線</b>: {@code createNotification} で DB 作成した通知を
 *       {@code NotificationDispatchService.dispatch} に渡しておらず、WS/Push 配信が起きなかった。</li>
 * </ol>
 *
 * <p>本テストは両欠陥を実証する（修正前 red / 修正後 green）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InquiryChatEventListener — 問い合わせ通知の生成と配信")
class InquiryChatEventListenerTest {

    private static final long TEAM_ID = 100L;
    private static final long CHANNEL_ID = 7001L;
    private static final long MESSAGE_ID = 5001L;
    private static final long SENDER_ID = 42L;
    private static final long ADMIN_ID = 10L;
    private static final long DEPUTY_ID = 11L;
    private static final long OTHER_TEAM_ID = 200L;
    private static final String SLUG = "lions-fc";
    /** 30文字・ハイフン入りの slug（AC-12）。 */
    private static final String LONG_SLUG = "abcdefghij-klmnopqrs-tuvwxyz01";
    private static final String NUMERIC_TEAM_URL = ".*/teams/\\d+(/|\\?|$).*";

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AdminBusinessAlertService adminBusinessAlertService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private MessageSource messageSource;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private TeamService teamService;

    private InquiryChatEventListener listener;
    private ListAppender<ILoggingEvent> logs;
    private Logger listenerLogger;

    @BeforeEach
    void setUp() {
        listener = new InquiryChatEventListener(
                userRoleRepository,
                notificationService,
                redisTemplate,
                adminBusinessAlertService,
                dispatchService,
                messageSource,
                userLocaleCache,
                teamService);
        lenient().when(userLocaleCache.getLocales(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of());
        lenient().when(messageSource.getMessage(
                        anyString(), any(), anyString(), org.mockito.ArgumentMatchers.any(Locale.class)))
                .thenReturn("stub-message");
        listenerLogger = (Logger) LoggerFactory.getLogger(InquiryChatEventListener.class);
        logs = new ListAppender<>();
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logs);
    }

    private void stubDedupPass() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
    }

    private InquiryReceivedEvent event() {
        return event(TEAM_ID);
    }

    private InquiryReceivedEvent event(long teamId) {
        return new InquiryReceivedEvent(
                teamId, CHANNEL_ID, "問い合わせ", SENDER_ID, "山田太郎", MESSAGE_ID);
    }

    @Test
    @DisplayName("sourceType=CHAT_MESSAGE に対し sourceId は【メッセージ ID】を渡す（チャンネル ID ではない）")
    void createNotification_uses_messageId_as_sourceId() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        lenient().when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(mock(NotificationEntity.class));

        listener.onInquiryReceived(event());

        ArgumentCaptor<String> sourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sourceId = ArgumentCaptor.forClass(Long.class);
        verify(notificationService).createNotification(
                eq(ADMIN_ID), eq("INQUIRY_RECEIVED"), eq(NotificationPriority.HIGH),
                anyString(), anyString(),
                sourceType.capture(), sourceId.capture(),
                eq(NotificationScopeType.TEAM), eq(TEAM_ID), anyString(), eq(SENDER_ID));

        assertThat(sourceType.getValue()).isEqualTo("CHAT_MESSAGE");
        assertThat(sourceId.getValue())
                .as("sourceId は sourceType=CHAT_MESSAGE の実体（メッセージ ID）でなければならない")
                .isEqualTo(MESSAGE_ID)
                .isNotEqualTo(CHANNEL_ID);
    }

    @Test
    @DisplayName("作成した通知は NotificationDispatchService.dispatch でリアルタイム配信される")
    void created_notification_is_dispatched() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        NotificationEntity created = mock(NotificationEntity.class);
        when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(created);

        listener.onInquiryReceived(event());

        verify(dispatchService, times(1)).dispatch(created);
    }

    @Test
    @DisplayName("visibility deny 等で通知が null の場合は dispatch を呼ばない")
    void null_notification_is_not_dispatched() {
        stubDedupPass();
        when(userRoleRepository.findAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of(ADMIN_ID));
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(TEAM_ID)).thenReturn(List.of());
        when(notificationService.createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(null);

        listener.onInquiryReceived(event());

        verify(dispatchService, org.mockito.Mockito.never()).dispatch(any());
    }

    // ========================================================================
    // CMP-261030-0648: actionUrl を /teams/{slug}/chat?channel={id} にする
    // ========================================================================

    private void stubRecipients(long teamId, List<Long> admins, List<Long> deputies) {
        when(userRoleRepository.findAdminUserIdsByTeamId(teamId)).thenReturn(admins);
        when(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(teamId)).thenReturn(deputies);
    }

    /** 宛先ごとの actionUrl を取り出す。 */
    private List<String> captureActionUrls(int expectedCalls, ArgumentCaptor<Long> recipients) {
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(expectedCalls)).createNotification(
                recipients.capture(), eq("INQUIRY_RECEIVED"), eq(NotificationPriority.HIGH),
                anyString(), anyString(), eq("CHAT_MESSAGE"), eq(MESSAGE_ID),
                eq(NotificationScopeType.TEAM), any(), urls.capture(), eq(SENDER_ID));
        return urls.getAllValues();
    }

    private List<String> captureActionUrls(int expectedCalls) {
        return captureActionUrls(expectedCalls, ArgumentCaptor.forClass(Long.class));
    }

    @Test
    @DisplayName("AC-2/AC-4/AC-19a: actionUrl は /teams/{slug}/chat?channel={channelId} で、getSlugById は宛先が複数でも1回")
    void ac2_action_url_uses_slug_and_channel_once() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(ADMIN_ID), List.of(DEPUTY_ID));
        when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);

        listener.onInquiryReceived(event());

        List<String> urls = captureActionUrls(2);
        assertThat(urls).containsOnly("/teams/" + SLUG + "/chat?channel=" + CHANNEL_ID);
        assertThat(urls).allSatisfy(u -> assertThat(u).doesNotMatch(NUMERIC_TEAM_URL));
        verify(teamService, times(1)).getSlugById(TEAM_ID);
    }

    @Test
    @DisplayName("AC-12: 30文字・ハイフン入りの slug がそのまま URL に入る")
    void ac12_long_hyphenated_slug_is_used_verbatim() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(ADMIN_ID), List.of());
        when(teamService.getSlugById(TEAM_ID)).thenReturn(LONG_SLUG);

        listener.onInquiryReceived(event());

        assertThat(captureActionUrls(1)).containsExactly("/teams/" + LONG_SLUG + "/chat?channel=" + CHANNEL_ID);
    }

    @Test
    @DisplayName("AC-17: getSlugById は event の teamId だけを引数に呼ばれる")
    void ac17_slug_is_resolved_for_event_team_only() {
        stubDedupPass();
        stubRecipients(OTHER_TEAM_ID, List.of(ADMIN_ID), List.of());
        when(teamService.getSlugById(OTHER_TEAM_ID)).thenReturn("other-team");

        listener.onInquiryReceived(event(OTHER_TEAM_ID));

        assertThat(captureActionUrls(1)).containsExactly("/teams/other-team/chat?channel=" + CHANNEL_ID);
        verify(teamService).getSlugById(OTHER_TEAM_ID);
        verifyNoMoreInteractions(teamService);
    }

    @Test
    @DisplayName("AC-8: slug が null なら、自己除外後の受信者全員に /notifications で送る")
    void ac8_null_slug_falls_back_to_notifications_for_all() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(ADMIN_ID, SENDER_ID), List.of(DEPUTY_ID));
        when(teamService.getSlugById(TEAM_ID)).thenReturn(null);

        listener.onInquiryReceived(event());

        ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
        List<String> urls = captureActionUrls(2, recipients);
        assertThat(recipients.getAllValues()).containsExactlyInAnyOrder(ADMIN_ID, DEPUTY_ID);
        assertThat(urls).containsOnly("/notifications");
    }

    @Test
    @DisplayName("AC-14/AC-23: getSlugById が例外でも /notifications で送り WARN を出す。閲覧できない受信者（作成結果 null）には配信しない")
    void ac14_ac23_slug_exception_falls_back_and_keeps_visibility_gate() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(ADMIN_ID), List.of(DEPUTY_ID));
        when(teamService.getSlugById(TEAM_ID)).thenThrow(new IllegalStateException("db down"));
        NotificationEntity visible = mock(NotificationEntity.class);
        when(notificationService.createNotification(eq(ADMIN_ID), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any())).thenReturn(visible);
        // DEPUTY_ID は NotificationService の可視性判定で弾かれる（保存されず null が返る）
        when(notificationService.createNotification(eq(DEPUTY_ID), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any())).thenReturn(null);

        assertThatCode(() -> listener.onInquiryReceived(event())).doesNotThrowAnyException();

        assertThat(captureActionUrls(2)).containsOnly("/notifications");
        verify(dispatchService, times(1)).dispatch(visible);
        verify(dispatchService, times(1)).dispatch(any());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("slug");
        });
    }

    @Test
    @DisplayName("AC-10/AC-19b: 宛先が送信者本人だけなら slug を解決せず、通知も作らない")
    void ac10_only_actor_skips_resolution() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(SENDER_ID), List.of());

        listener.onInquiryReceived(event());

        verifyNoInteractions(teamService);
        verify(notificationService, never()).createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("AC-10/AC-19b: 宛先が0人なら slug を解決しない")
    void ac10_no_recipients_skips_resolution() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(), List.of());

        listener.onInquiryReceived(event());

        verifyNoInteractions(teamService);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("AC-15: ある宛先の通知作成が失敗しても、ほかの宛先には届く")
    void ac15_failure_for_one_recipient_does_not_stop_others() {
        stubDedupPass();
        stubRecipients(TEAM_ID, List.of(ADMIN_ID), List.of(DEPUTY_ID));
        when(teamService.getSlugById(TEAM_ID)).thenReturn(SLUG);
        when(notificationService.createNotification(eq(ADMIN_ID), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any())).thenThrow(new RuntimeException("boom"));
        NotificationEntity created = mock(NotificationEntity.class);
        when(notificationService.createNotification(eq(DEPUTY_ID), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any())).thenReturn(created);

        listener.onInquiryReceived(event());

        verify(notificationService).createNotification(eq(DEPUTY_ID), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), eq("/teams/" + SLUG + "/chat?channel=" + CHANNEL_ID), any());
        verify(dispatchService).dispatch(created);
    }

    private static <T> T mock(Class<T> c) {
        return org.mockito.Mockito.mock(c);
    }
}
