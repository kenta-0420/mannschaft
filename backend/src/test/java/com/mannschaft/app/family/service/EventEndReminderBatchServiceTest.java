package com.mannschaft.app.family.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.family.event.EventEndReminderDueEvent;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventEndReminderBatchService} のユニットテスト。F03.12 §16。
 */
@ExtendWith(MockitoExtension.class)
class EventEndReminderBatchServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private UserLocaleCache userLocaleCache;

    @Mock
    private MessageSource messageSource;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EventEndReminderBatchService batchService;

    // テスト定数
    private static final Long EVENT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORGANIZER_USER_ID = 100L;
    private static final Long ADMIN_USER_ID_1 = 201L;
    private static final Long ADMIN_USER_ID_2 = 202L;

    @org.junit.jupiter.api.BeforeEach
    void setUpLocale() {
        // Issue #2715 ロットC-2: 新規依存 UserLocaleCache/MessageSource の既定スタブ
        // （未スタブだと null 返却/NPE で通知が握りつぶされ、既存テストが偽装的に失敗する）。
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    /** 実物の MessageSource（messages*.properties）を差し込む（Issue #2715 テスト方針）。 */
    private void useRealMessageSource() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(batchService, "messageSource", realMessageSource);
    }

    // =========================================================
    // runEndReminderCheck
    // =========================================================

    @Nested
    @DisplayName("runEndReminderCheck")
    class RunEndReminderCheck {

        @Test
        @DisplayName("1回目リマインド送信: count=0のイベント → NORMAL優先度で主催者に通知。F03.12 Phase11: actionUrl にチームID 含む")
        void 一回目リマインド送信() {
            // Arrange: count=0（未送信）の終了済みイベント
            EventEntity event = buildEventWithReminderCount(0);
            // Issue #2990 L6: 実配送は業務コミット後の入口 deliverReminder から行う。
            // 本文の組み立て・優先度・宛先の検証内容は是正前と同一で、入口だけが変わっている。
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willReturn(buildNotification(ORGANIZER_USER_ID));

            // Act
            batchService.deliverReminder(EVENT_ID, 0);

            // Assert: NORMAL 優先度で主催者に送信。actionUrl は /teams/{teamId}/events/{eventId} 形式
            String expectedActionUrl = "/teams/" + TEAM_ID + "/events/" + EVENT_ID;
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.NORMAL),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), eq(expectedActionUrl), any());
            verify(dispatchService).dispatch(any(NotificationEntity.class));
            // ADMINには通知しない
            verify(userRoleRepository, never()).findUserIdsByTeamIdAndRoleName(any(), any());
        }

        @Test
        @DisplayName("2回目リマインド送信: count=1 → HIGH優先度で主催者のみに通知")
        void 二回目リマインド送信() {
            // Arrange: count=1（1回目送信済み）
            EventEntity event = buildEventWithReminderCount(1);
            // Issue #2990 L6: 実配送は業務コミット後の入口 deliverReminder から行う。
            // 本文の組み立て・優先度・宛先の検証内容は是正前と同一で、入口だけが変わっている。
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willReturn(buildNotification(ORGANIZER_USER_ID));

            // Act
            batchService.deliverReminder(EVENT_ID, 1);

            // Assert: HIGH 優先度で主催者に送信
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.HIGH),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), any(), any());
            // ADMINには通知しない
            verify(userRoleRepository, never()).findUserIdsByTeamIdAndRoleName(any(), any());
        }

        @Test
        @DisplayName("3回目リマインド送信: count=2 → URGENT優先度でADMIN全員にも通知")
        void 三回目リマインド送信_ADMIN全員に通知() {
            // Arrange: count=2（2回目送信済み）
            EventEntity event = buildEventWithReminderCount(2);
            // Issue #2990 L6: 実配送は業務コミット後の入口 deliverReminder から行う。
            // 本文の組み立て・優先度・宛先の検証内容は是正前と同一で、入口だけが変わっている。
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willAnswer(inv -> buildNotification(inv.getArgument(0)));
            // チームADMINリスト: 主催者(100) + 別ADMIN(201・202)
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(ORGANIZER_USER_ID, ADMIN_USER_ID_1, ADMIN_USER_ID_2));

            // Act
            batchService.deliverReminder(EVENT_ID, 2);

            // Assert: 主催者 + ADMIN 2名（計3名）に URGENT 通知（主催者は重複排除で2回目）
            // 主催者は sendReminderByCount 内の 3回目ブランチで一度呼ばれる
            // sendAdminReminders では主催者を除外するので ADMIN_USER_ID_1・2 のみ
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
            verify(notificationService).createNotification(
                    eq(ADMIN_USER_ID_1),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
            verify(notificationService).createNotification(
                    eq(ADMIN_USER_ID_2),
                    eq("EVENT_DISMISSAL_REMINDER"),
                    eq(NotificationPriority.URGENT),
                    any(), any(), any(), any(), any(), any(), any(), any());
        }

        // ============================================================
        // Issue #2715 CMP-055 ロットC-2: 通知本文の locale 別組み立て
        // ============================================================

        @Test
        @DisplayName("1回目リマインド: 主催者 locale が en なら件名・本文が英語になる")
        void 一回目リマインド_主催者locale別に英語化される() {
            useRealMessageSource();
            EventEntity event = buildEventWithReminderCount(0);
            // Issue #2990 L6: 本文の locale 別組み立ては業務コミット後の入口 deliverReminder で行う。
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(userLocaleCache.getLocale(ORGANIZER_USER_ID)).willReturn("en");
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willReturn(buildNotification(ORGANIZER_USER_ID));

            batchService.deliverReminder(EVENT_ID, 0);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(ORGANIZER_USER_ID), eq("EVENT_DISMISSAL_REMINDER"), eq(NotificationPriority.NORMAL),
                    titleCaptor.capture(), bodyCaptor.capture(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), any(), any());
            assertThat(titleCaptor.getValue()).isEqualTo("Did you forget to send the dismissal notice?");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").contains("テストイベント");
        }

        @Test
        @DisplayName("3回目リマインド: ADMIN通知は locale をバルク解決する（N+1防止）")
        void 三回目リマインド_ADMIN通知はバルク解決() {
            useRealMessageSource();
            EventEntity event = buildEventWithReminderCount(2);
            // Issue #2990 L6: 本文の locale 別組み立ては業務コミット後の入口 deliverReminder で行う。
            given(eventRepository.findById(EVENT_ID)).willReturn(Optional.of(event));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), any()))
                    .willAnswer(inv -> buildNotification(inv.getArgument(0)));
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(ORGANIZER_USER_ID, ADMIN_USER_ID_1, ADMIN_USER_ID_2));
            given(userLocaleCache.getLocales(List.of(ORGANIZER_USER_ID, ADMIN_USER_ID_1, ADMIN_USER_ID_2)))
                    .willReturn(Map.of(ADMIN_USER_ID_1, "en", ADMIN_USER_ID_2, "en"));

            batchService.deliverReminder(EVENT_ID, 2);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(ADMIN_USER_ID_1), eq("EVENT_DISMISSAL_REMINDER"), eq(NotificationPriority.URGENT),
                    titleCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
            assertThat(titleCaptor.getValue()).isEqualTo("🚨 Dismissal notice not sent yet (urgent)");

            // AC-3: N+1 防止 — ADMIN 通知経路の locale 解決はバルク (getLocales) を用い、
            // ADMIN個別の getLocale は呼ばれない。
            verify(userLocaleCache, never()).getLocale(eq(ADMIN_USER_ID_1));
            verify(userLocaleCache, never()).getLocale(eq(ADMIN_USER_ID_2));
        }

        @Test
        @DisplayName("#2990 L6: 業務TX内では通知を作らず、カウンタを進めて配送要求イベントを publish する")
        void 業務TX内ではpublishのみ() {
            EventEntity event = buildEventWithReminderCount(1);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));

            batchService.runEndReminderCheck();

            // 業務TX内で通知を作ってはならない（作ると通知の失敗でバッチのTXが
            // rollback-only になり、その回のカウンタ増分が全イベントぶん巻き戻る）。
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(dispatchService, never()).dispatch(any(NotificationEntity.class));

            // 代わりに eventId と段階だけを載せたイベントを publish する。
            ArgumentCaptor<EventEndReminderDueEvent> captor =
                    ArgumentCaptor.forClass(EventEndReminderDueEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().eventId()).isEqualTo(EVENT_ID);
            assertThat(captor.getValue().stage())
                    .as("stage はインクリメント前のカウント（＝送るべき段階）でなければならない")
                    .isEqualTo(1);

            // カウンタは業務TX内で進めてコミットする（配送失敗でも同じ段階を再送しないため）。
            assertThat(event.getOrganizerReminderSentCount().intValue()).isEqualTo(2);
            verify(eventRepository).save(event);
        }

        @Test
        @DisplayName("解散済みイベントはスキップ: findDismissalReminderTargets が空 → 通知なし")
        void 解散済みイベントはスキップ() {
            // Arrange: dismissal_notification_sent_at が設定済み → リポジトリが空リストを返す
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of());

            // Act
            batchService.runEndReminderCheck();

            // Assert: 通知なし。是正後（#2990 L6）は createNotification が呼ばれないのは自明なので、
            // 配送要求イベントの publish が起きないことまで見る。
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any(EventEndReminderDueEvent.class));
        }

        @Test
        @DisplayName("上限3回到達済みはスキップ: count=3 → 通知なし（リポジトリ除外済みを想定）")
        void 上限到達済みはスキップ() {
            // Arrange: count=3（上限到達済み）→ findDismissalReminderTargets が除外済み
            EventEntity event = buildEventWithReminderCount(3);
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of(event));

            // Act
            batchService.runEndReminderCheck();

            // Assert: count >= MAX_REMINDER_COUNT でスキップ。是正後（#2990 L6）は
            // 配送要求イベントの publish が起きないことまで見る。
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any(EventEndReminderDueEvent.class));
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    /**
     * 指定リマインド回数のイベントエンティティを構築する。
     *
     * @param reminderCount リマインド送信回数（0〜3）
     * @return イベントエンティティ
     */
    private EventEntity buildEventWithReminderCount(int reminderCount) {
        EventEntity event = EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(TEAM_ID)
                .slug("test-event")
                .subtitle("テストイベント")
                .createdBy(ORGANIZER_USER_ID)
                .attendanceMode(EventAttendanceMode.REGISTRATION)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .build();
        // BaseEntity.id は @GeneratedValue 由来で Lombok ビルダーから設定できないため、
        // リフレクションで明示的に注入する。バッチが event.getId() を通知の sourceId として使うため必須。
        ReflectionTestUtils.setField(event, "id", EVENT_ID);

        // incrementOrganizerReminder を指定回数呼び出してカウントを設定
        for (int i = 0; i < reminderCount; i++) {
            event.incrementOrganizerReminder();
        }
        return event;
    }

    /**
     * テスト用の通知エンティティを構築する。
     *
     * @param userId 通知先ユーザーID
     * @return 通知エンティティ
     */
    private NotificationEntity buildNotification(Long userId) {
        return NotificationEntity.builder()
                .userId(userId)
                .notificationType("EVENT_DISMISSAL_REMINDER")
                .title("テスト通知")
                .body("テスト本文")
                .sourceType("EVENT")
                .sourceId(EVENT_ID)
                .scopeType(NotificationScopeType.PERSONAL)
                .scopeId(userId)
                .build();
    }

    // =========================================================
    // 回帰: 閉栓や障害を跨いだ「古いイベント」を対象にしないこと
    // =========================================================

    @Nested
    @DisplayName("鮮度の下限（Codex 検分 P2 の根治）")
    class 鮮度の下限 {

        /**
         * 初版のクエリは上限（endAt < cutoff）しか持たず、
         * 「未解散かつリマインド 3 回未満」の過去イベントを何ヶ月前のものでも拾っていた。
         * そのため長期停止からの再開時に、とうに終わったイベントの主催者へ
         * 段階リマインドが 3 回飛び、最後には管理者への緊急通知まで発火する。
         *
         * <p>本テストはバッチが【鮮度の下限（now - 24時間）をクエリへ渡していること】を固定する。
         * 下限そのものの効き（古い行が返らないこと）は JPQL 側の条件で担保する。</p>
         */
        @Test
        @DisplayName("クエリには now-24時間 の鮮度下限が渡り、古いイベントは初めから対象にならない")
        void 古いイベントは対象外() {
            given(eventRepository.findDismissalReminderTargets(
                    any(LocalDateTime.class), any(LocalDateTime.class),
                    any(LocalDateTime.class), anyInt()))
                    .willReturn(List.of());

            LocalDateTime before = LocalDateTime.now();
            batchService.runEndReminderCheck();
            LocalDateTime after = LocalDateTime.now();

            ArgumentCaptor<LocalDateTime> nowCap = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> cutoffCap = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> staleCap = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(eventRepository).findDismissalReminderTargets(
                    nowCap.capture(), cutoffCap.capture(), staleCap.capture(), anyInt());

            // 鮮度下限は now から 24 時間前（実行時刻の揺らぎを許容して範囲で見る）
            assertThat(staleCap.getValue())
                    .as("終了から 24 時間より古いイベントを除外する下限が渡らねばならない")
                    .isAfterOrEqualTo(before.minusHours(24))
                    .isBeforeOrEqualTo(after.minusHours(24));

            // 下限は上限より前（区間が成立している＝空区間で全件除外していない）
            assertThat(staleCap.getValue())
                    .as("鮮度下限は経過フィルタ（cutoff）より前でなければ、対象が常に空になる")
                    .isBefore(cutoffCap.getValue());
        }
    }

}
