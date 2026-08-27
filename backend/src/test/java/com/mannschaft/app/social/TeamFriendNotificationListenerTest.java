package com.mannschaft.app.social;



import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.social.event.TeamFriendNotificationEvent;
import com.mannschaft.app.social.event.TeamFriendNotificationListener;
import com.mannschaft.app.team.service.TeamService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 第1群ロットB — {@link TeamFriendNotificationListener} のユニットテスト。
 *
 * <p>受け入れ条件との対応:</p>
 * <ul>
 *   <li>AC-1/AC-2: {@link #コミット後に非同期で発火する}（{@code AFTER_COMMIT} 契約）。是正前は
 *       通知の DB 例外が rollback-only を立ててフレンド成立／解除ごと巻き戻していた
 *       （PR #2861 Codex 検分 P1 の自認箇所）。</li>
 *   <li>AC-3: {@link #一人の配送失敗が他を巻き添えにしない} / {@link #一人の組み立て失敗が他を巻き添えにしない}</li>
 *   <li>AC-4: {@link #denyのみなら集計ログはWARN} / {@link #例外が1件でもあれば集計ログはERROR}</li>
 *   <li>削除済み source を参照しない: {@link #解除通知は生存しているチームのフレンド一覧を指す}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFriendNotificationListener ユニットテスト")
class TeamFriendNotificationListenerTest {

    private static final Long TEAM_ID = 11L;
    private static final Long TARGET_TEAM_ID = 22L;
    private static final Long TEAM_FRIEND_ID = 333L;
    private static final Long ACTOR_ID = 9L;
    private static final Long SELF_ADMIN = 101L;
    private static final Long TARGET_ADMIN_A = 201L;
    private static final Long TARGET_ADMIN_B = 202L;

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private TeamService teamService;

    @Mock
    private RoleService roleService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    private TeamFriendNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TeamFriendNotificationListener(
                notificationDeliveryRunner, teamService, roleService, userLocaleCache, messageSource);
        lenient().when(teamService.getNamesByIds(any()))
                .thenReturn(Map.of(TEAM_ID, "自チーム", TARGET_TEAM_ID, "相手チーム"));
        lenient().when(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                .thenReturn(List.of(SELF_ADMIN));
        lenient().when(roleService.getUserIdsByTeamIdAndRoleName(TARGET_TEAM_ID, "ADMIN"))
                .thenReturn(List.of(TARGET_ADMIN_A, TARGET_ADMIN_B));
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        lenient().when(notificationDeliveryRunner.sendOne(any()))
                .thenReturn(NotificationDeliveryResult.DELIVERED);
    }

    private TeamFriendNotificationEvent event(TeamFriendNotificationEvent.Kind kind) {
        return new TeamFriendNotificationEvent(kind, TEAM_ID, TARGET_TEAM_ID, TEAM_FRIEND_ID, ACTOR_ID);
    }

    @Test
    @DisplayName("AC-1/AC-2: 配送は AFTER_COMMIT + event-pool の非同期で起動する")
    void コミット後に非同期で発火する() throws Exception {
        Method method = TeamFriendNotificationListener.class.getMethod(
                "onTeamFriendNotification", TeamFriendNotificationEvent.class);

        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);

        Async async = method.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");
    }

    @Test
    @DisplayName("成立: 両チームの ADMIN 全員に1件ずつ送り、相手先チーム名を入れ替えて伝える")
    void 成立通知は両チームのADMINへ送られる() {
        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactlyInAnyOrder(SELF_ADMIN, TARGET_ADMIN_A, TARGET_ADMIN_B);
        assertThat(captor.getAllValues())
                .allMatch(r -> "FRIEND_ESTABLISHED".equals(r.notificationType()))
                .allMatch(r -> "TEAM_FRIEND".equals(r.sourceType()))
                .allMatch(r -> TEAM_FRIEND_ID.equals(r.sourceId()))
                .allMatch(r -> r.scopeType() == NotificationScopeType.FRIEND_TEAM)
                .allMatch(r -> ACTOR_ID.equals(r.actorId()));

        NotificationDeliveryRequest self = captor.getAllValues().stream()
                .filter(r -> SELF_ADMIN.equals(r.recipientUserId())).findFirst().orElseThrow();
        assertThat(self.scopeId()).isEqualTo(TEAM_ID);
        assertThat(self.actionUrl()).isEqualTo("/teams/" + TEAM_ID + "/friends");
        assertThat(self.body()).contains("相手チーム");

        NotificationDeliveryRequest target = captor.getAllValues().stream()
                .filter(r -> TARGET_ADMIN_A.equals(r.recipientUserId())).findFirst().orElseThrow();
        assertThat(target.scopeId()).isEqualTo(TARGET_TEAM_ID);
        assertThat(target.actionUrl()).isEqualTo("/teams/" + TARGET_TEAM_ID + "/friends");
        assertThat(target.body()).contains("自チーム");
    }

    @Test
    @DisplayName("解除: 通知種別は FRIEND_DISSOLVED になり、遷移先は削除済みフレンド関係ではなく生存中のチームのフレンド一覧")
    void 解除通知は生存しているチームのフレンド一覧を指す() {
        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.DISSOLVED));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(r -> "FRIEND_DISSOLVED".equals(r.notificationType()))
                // sourceType=TEAM_FRIEND は NotificationSourceTypeMapper 未登録＝fail-soft のため、
                // unfollow で物理削除された team_friends を sourceId に持っても deny されない。
                .allMatch(r -> "TEAM_FRIEND".equals(r.sourceType()))
                .allMatch(r -> r.actionUrl() != null && r.actionUrl().endsWith("/friends"));
    }

    @Test
    @DisplayName("locale はバルク解決される（N+1防止・受信者は両チーム分まとめて1回）")
    void ロケールはバルク解決される() {
        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        verify(userLocaleCache, times(1)).getLocales(any());
    }

    @Test
    @DisplayName("AC-3: 1受信者の配送例外が他受信者の配送を止めず、リスナー外へも漏れない")
    void 一人の配送失敗が他を巻き添えにしない() {
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && TARGET_ADMIN_A.equals(r.recipientUserId())));

        assertThatCode(() -> listener.onTeamFriendNotification(
                event(TeamFriendNotificationEvent.Kind.ESTABLISHED))).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("AC-3: 1受信者の組み立て例外も他受信者を巻き添えにしない")
    void 一人の組み立て失敗が他を巻き添えにしない() {
        given(messageSource.getMessage(eq("notification.social.teamFriend.established.body"),
                any(), anyString(), any(Locale.class)))
                .willAnswer(inv -> inv.getArgument(2))
                .willThrow(new RuntimeException("模擬組み立て失敗"))
                .willAnswer(inv -> inv.getArgument(2));

        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        verify(notificationDeliveryRunner, times(2)).sendOne(any());
    }

    @Test
    @DisplayName("AC-4: visibility deny（null 復帰）は例外扱いせず、後続受信者の配送も続く")
    void denyは例外扱いされず後続も続く() {
        given(notificationDeliveryRunner.sendOne(
                argThat(r -> r != null && TARGET_ADMIN_A.equals(r.recipientUserId())))).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        assertThatCode(() -> listener.onTeamFriendNotification(
                event(TeamFriendNotificationEvent.Kind.ESTABLISHED))).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, times(3)).sendOne(any());
    }

    @Test
    @DisplayName("ADMIN 解決が失敗したら誰にも送らない（受信者共通の解決失敗は一括で諦める）")
    void 受信者解決失敗なら誰にも送らない() {
        given(roleService.getUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                .willThrow(new RuntimeException("模擬受信者解決失敗"));

        assertThatCode(() -> listener.onTeamFriendNotification(
                event(TeamFriendNotificationEvent.Kind.ESTABLISHED))).doesNotThrowAnyException();

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("チーム名が解決できなくても既定表示で送る（是正前の orElse(\"チーム\") を維持）")
    void チーム名不明でも既定表示で送る() {
        given(teamService.getNamesByIds(any())).willReturn(Map.of());

        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(3)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> r.body().contains("チーム"));
    }

    // ------------------------------------------------------------------
    // AC-4: 集計ログのレベル（deny=WARN / 例外=ERROR の区別）
    // ------------------------------------------------------------------

    /** 集計ログを識別するための固定文言。 */
    private static final String SUMMARY_MARKER = "フレンドチーム通知一括配送の結果";

    private Logger listenerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logAppender;

    /**
     * 集計ログを捕捉する。ロガーレベルは<b>このテスト自身で明示設定する</b>
     * （{@link ListAppender} のレベル継承による偽緑を避けるため）。
     */
    private void captureLogs() {
        listenerLogger = (Logger) LoggerFactory.getLogger(TeamFriendNotificationListener.class);
        originalLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.WARN);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        if (listenerLogger != null) {
            listenerLogger.detachAppender(logAppender);
            listenerLogger.setLevel(originalLevel);
            listenerLogger = null;
        }
    }

    private List<ILoggingEvent> summaryEvents() {
        return logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains(SUMMARY_MARKER))
                .toList();
    }

    @Test
    @DisplayName("AC-4: deny のみ（例外ゼロ）なら集計ログは WARN であり ERROR は出ない")
    void denyのみなら集計ログはWARN() {
        captureLogs();
        given(notificationDeliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logAppender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("AC-4: 例外が1件でもあれば集計ログは ERROR になる")
    void 例外が1件でもあれば集計ログはERROR() {
        captureLogs();
        willThrow(new RuntimeException("模擬配送失敗")).given(notificationDeliveryRunner)
                .sendOne(argThat(r -> r != null && TARGET_ADMIN_A.equals(r.recipientUserId())));

        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        List<ILoggingEvent> summaries = summaryEvents();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("全件成功なら集計ログ自体が出ない")
    void 全件成功なら集計ログは出ない() {
        captureLogs();

        listener.onTeamFriendNotification(event(TeamFriendNotificationEvent.Kind.ESTABLISHED));

        assertThat(summaryEvents()).isEmpty();
    }
}
