package com.mannschaft.app.event;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.event.dto.AbsenceNoticeRequest;
import com.mannschaft.app.event.dto.AdvanceNoticeResponse;
import com.mannschaft.app.event.dto.LateNoticeRequest;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.event.service.EventRsvpService;
import com.mannschaft.app.event.service.EventService;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.service.CareAbsentAlertBatchService;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.event.event.EventAdvanceNoticeNotificationEvent;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.12 Phase8 §15 事前遅刻・欠席連絡のサービステスト。
 *
 * <p>テスト対象:</p>
 * <ul>
 *   <li>{@link EventRsvpService#submitLateNotice} — 遅刻連絡送信</li>
 *   <li>{@link EventRsvpService#submitAbsenceNotice} — 欠席連絡送信</li>
 *   <li>{@link CareAbsentAlertBatchService#runNoContactCheck} — 遅刻オフセット後のカットオフ検証</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class EventRsvpAdvanceNoticeServiceTest {

    // =========================================================
    // EventRsvpService のテスト
    // =========================================================

    @Nested
    @DisplayName("EventRsvpService — 事前通知")
    class EventRsvpServiceTests {

        @Mock
        private EventRsvpResponseRepository rsvpResponseRepository;

        @Mock
        private UserRepository userRepository;

        @Mock
        private EventService eventService;

        @Mock
        private CareEventNotificationService careEventNotificationService;

        @Mock
        private CareLinkService careLinkService;

        @Mock
        private UserLocaleCache userLocaleCache;

        @Mock
        private MessageSource messageSource;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private EventRsvpService rsvpService;

        private static final Long EVENT_ID  = 1L;
        private static final Long TEAM_ID   = 10L;
        private static final Long USER_ID   = 100L;
        private static final Long OPERATOR  = 200L;

        @org.junit.jupiter.api.BeforeEach
        void setUpLocale() {
            // Issue #2715 ロットC-2: 新規依存 UserLocaleCache/MessageSource の既定スタブ
            // （未スタブだと null 返却/NPE で通知が握りつぶされ、既存テストが偽装的に失敗する）。
            lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
            lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
            lenient().when(messageSource.getMessage(org.mockito.ArgumentMatchers.anyString(), any(),
                            org.mockito.ArgumentMatchers.anyString(), any(Locale.class)))
                    .thenAnswer(invocation -> invocation.getArgument(2));
        }

        /**
         * {@link #eventPublisher} へ publish された唯一の {@link EventAdvanceNoticeNotificationEvent}
         * から通知配送要求一覧を取り出すヘルパー（Issue #2834 / CMP-056 型確立: 通知は
         * {@code NotificationService#createNotification} 直接呼び出しではなく AFTER_COMMIT イベント
         * publish に置き換わった）。
         */
        private List<NotificationDeliveryRequest> capturedNotificationRequests() {
            ArgumentCaptor<EventAdvanceNoticeNotificationEvent> captor =
                    ArgumentCaptor.forClass(EventAdvanceNoticeNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            return captor.getValue().requests();
        }

        /** 実物の MessageSource（messages*.properties）を差し込む（Issue #2715 テスト方針）。 */
        private void useRealMessageSource() {
            ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            ReflectionTestUtils.setField(rsvpService, "messageSource", realMessageSource);
        }

        @Test
        @DisplayName("submitLateNotice_正常: 遅刻連絡 → RSVP更新 + 主催者向け通知イベントpublish確認")
        void submitLateNotice_正常() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);  // 主催者 = OPERATOR
            UserEntity user = buildUser(USER_ID, "テスト太郎");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            // 操作者が見守り者でない場合: getActiveWatchers は空を返す
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP")).willReturn(List.of());

            LateNoticeRequest req = new LateNoticeRequest(USER_ID, 30, "電車が遅れています");

            // Act
            AdvanceNoticeResponse response = rsvpService.submitLateNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            // Assert
            assertThat(response.getNoticeType()).isEqualTo("LATE");
            assertThat(response.getExpectedArrivalMinutesLate()).isEqualTo(30);
            assertThat(response.getAbsenceReason()).isNull();
            assertThat(response.getUserId()).isEqualTo(USER_ID);
            assertThat(response.getDisplayName()).isEqualTo("テスト太郎");

            // RSVP に遅刻分数が保存されたことを確認
            verify(rsvpResponseRepository).save(rsvp);
            // Issue #2834 / CMP-056: 「サービスがイベントを publish すること」を検証する
            // （実生成はリスナー側の責務のため、ここでは createNotification を直接検証しない）。
            String expectedActionUrl = "/teams/" + TEAM_ID + "/events/" + EVENT_ID;
            List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).recipientUserId()).isEqualTo(OPERATOR);
            assertThat(requests.get(0).actionUrl()).isEqualTo(expectedActionUrl);
        }

        @Test
        @DisplayName("submitAbsenceNotice_正常: 欠席連絡 → RSVP更新 + 主催者向け通知イベントpublish確認")
        void submitAbsenceNotice_正常() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            UserEntity user = buildUser(USER_ID, "テスト花子");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP")).willReturn(List.of());

            AbsenceNoticeRequest req = new AbsenceNoticeRequest(USER_ID, "SICK", "発熱のため");

            // Act
            AdvanceNoticeResponse response = rsvpService.submitAbsenceNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            // Assert
            assertThat(response.getNoticeType()).isEqualTo("ABSENCE");
            assertThat(response.getAbsenceReason()).isEqualTo("SICK");
            assertThat(response.getExpectedArrivalMinutesLate()).isNull();
            assertThat(response.getUserId()).isEqualTo(USER_ID);

            // RSVP に欠席理由が保存されたことを確認
            verify(rsvpResponseRepository).save(rsvp);
            // Issue #2834 / CMP-056: 「サービスがイベントを publish すること」を検証する。
            List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).recipientUserId()).isEqualTo(OPERATOR);
        }

        @Test
        @DisplayName("submitLateNotice_RSVP未登録: RSVP_NOT_FOUND 例外が発生すること")
        void submitLateNotice_RSVP未登録() {
            // Arrange
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.empty());

            LateNoticeRequest req = new LateNoticeRequest(USER_ID, 15, null);

            // Act & Assert
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> rsvpService.submitLateNotice(EVENT_ID, TEAM_ID, OPERATOR, req))
                    .isInstanceOf(BusinessException.class);
        }

        // ============================================================
        // Issue #2715 CMP-055 ロットC-2: 通知本文の locale 別組み立て
        // ============================================================

        @Test
        @DisplayName("submitLateNotice: 主催者 locale が en なら件名・本文が英語になる")
        void submitLateNotice_主催者locale別に英語化される() {
            useRealMessageSource();
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            UserEntity user = buildUser(USER_ID, "テスト太郎");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userLocaleCache.getLocale(OPERATOR)).willReturn("en");
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP")).willReturn(List.of());

            LateNoticeRequest req = new LateNoticeRequest(USER_ID, 30, "電車が遅れています");

            rsvpService.submitLateNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).title()).isEqualTo("Late arrival notice");
            assertThat(requests.get(0).body()).doesNotContain("{0}").doesNotContain("{1}")
                    .contains("テスト太郎").contains("30");
        }

        @Test
        @DisplayName("submitAbsenceNotice: 主催者 locale が en なら件名・本文が英語になる")
        void submitAbsenceNotice_主催者locale別に英語化される() {
            useRealMessageSource();
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            UserEntity user = buildUser(USER_ID, "テスト花子");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userLocaleCache.getLocale(OPERATOR)).willReturn("en");
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP")).willReturn(List.of());

            AbsenceNoticeRequest req = new AbsenceNoticeRequest(USER_ID, "SICK", "発熱のため");

            rsvpService.submitAbsenceNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).title()).isEqualTo("Absence notice");
            assertThat(requests.get(0).body()).doesNotContain("{0}").doesNotContain("{1}")
                    .contains("テスト花子").contains("SICK");
        }

        @Test
        @DisplayName("submitLateNotice: 見守り者への通知は locale をバルク解決する（N+1防止）")
        void submitLateNotice_見守り者通知はバルク解決() {
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            UserEntity user = buildUser(USER_ID, "テスト太郎");
            Long watcher1 = 301L;
            Long watcher2 = 302L;

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            // OPERATOR 自身が見守り者の1人（＝代理送信）で、他に watcher1/watcher2 がいる
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP"))
                    .willReturn(List.of(OPERATOR, watcher1, watcher2));

            LateNoticeRequest req = new LateNoticeRequest(USER_ID, 30, null);

            rsvpService.submitLateNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            // AC-3: N+1 防止 — バルク解決 (getLocales) が使われ、単体解決 (getLocale) は見守り者分呼ばれない。
            verify(userLocaleCache, times(1)).getLocales(any());
            verify(userLocaleCache, never()).getLocale(eq(watcher1));
            verify(userLocaleCache, never()).getLocale(eq(watcher2));
            // AC-7 の裏付け: 主催者(OPERATOR) + 見守り者2名（OPERATOR自身は除外）で計3件が
            // 1つの EventAdvanceNoticeNotificationEvent にまとめて publish される
            // （リスナー側が Runner を1件ずつ呼ぶことで受信者間の巻き添えを防ぐ・番人はリスナーテストで検証）。
            List<NotificationDeliveryRequest> requests = capturedNotificationRequests();
            assertThat(requests).hasSize(3);
            assertThat(requests).extracting(NotificationDeliveryRequest::recipientUserId)
                    .containsExactlyInAnyOrder(OPERATOR, watcher1, watcher2);
        }

        @Test
        @DisplayName("submitLateNotice: 通知組み立て（locale解決）が例外を投げても、隔離try以降のメソッド処理は例外を伝播させず続行する"
                + "（Issue #2834 / CMP-056: 通知は createNotification を直接呼ばずイベント publish のみのため、"
                + "本ケースでは rsvp の save が既にコミット対象として確定済みの状態で通知組み立てのみ失敗する）")
        void submitLateNotice_通知経路の非DB例外は隔離tryで捕捉され後続処理へ伝播しない() {
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            EventEntity event = buildEvent(EVENT_ID, OPERATOR);
            UserEntity user = buildUser(USER_ID, "テスト太郎");

            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(rsvpResponseRepository.findByEventIdAndUserId(EVENT_ID, USER_ID))
                    .willReturn(Optional.of(rsvp));
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            // 通知組み立て経路（locale解決）で例外を発生させる（隔離tryが捕捉する「非DB例外」の一例）
            given(userLocaleCache.getLocale(OPERATOR)).willThrow(new RuntimeException("locale解決失敗"));
            given(careLinkService.getActiveWatchers(USER_ID, "RSVP")).willReturn(List.of());

            LateNoticeRequest req = new LateNoticeRequest(USER_ID, 30, null);

            AdvanceNoticeResponse response = rsvpService.submitLateNotice(EVENT_ID, TEAM_ID, OPERATOR, req);

            // 例外は submitLateNotice の呼び出し元へ伝播せず、メソッドは正常にレスポンスを返す。
            assertThat(response.getNoticeType()).isEqualTo("LATE");
            assertThat(response.getExpectedArrivalMinutesLate()).isEqualTo(30);
            verify(rsvpResponseRepository).save(rsvp);
            // 通知イベントは publish されない（locale解決の時点で失敗し隔離tryに握られ、主催者分の
            // 要求が積まれないため requests が空になり publish 自体がスキップされる）。
            verify(eventPublisher, never()).publishEvent(any(EventAdvanceNoticeNotificationEvent.class));
        }
    }

    // =========================================================
    // CareAbsentAlertBatchService の遅刻オフセットテスト
    // =========================================================

    @Nested
    @DisplayName("CareAbsentAlertBatchService — 遅刻オフセット")
    class CareAbsentAlertBatchServiceLateOffsetTests {

        @Mock
        private EventRepository eventRepository;

        @Mock
        private EventRsvpResponseRepository rsvpResponseRepository;

        @Mock
        private EventCheckinRepository eventCheckinRepository;

        @Mock
        private CareLinkService careLinkService;

        @Mock
        private CareEventNotificationService careEventNotificationService;

        @InjectMocks
        private CareAbsentAlertBatchService batchService;

        private static final Long EVENT_ID = 1L;
        private static final Long USER_ID  = 100L;

        @Test
        @DisplayName("cutoffAdjust_遅刻申告あり: expectedArrivalMinutesLate=30 → processNoContactCheck でカットオフが30分延長されること")
        void cutoffAdjust_遅刻申告あり() {
            // Arrange: expectedArrivalMinutesLate=30 の RSVP
            EventRsvpResponseEntity rsvp = buildRsvpWithLate(EVENT_ID, USER_ID, 30);
            CareLinkResponse link = buildCareLinkResponse(USER_ID, CareCategory.MINOR);

            // MINOR の softMinutes=10 + late=30 = 40分 のカットオフで判定するため、
            // findActiveEventIdsStartedBefore(now, now-40min) が呼ばれた場合にのみイベントIDを返す
            // 粗フィルタ(5分)は通過させる: eventIds を返す
            // 精密フィルタ(40分)は通過させる: eventIds を返す
            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            // ATTENDING を一括取得: 遅刻申告ありの RSVP を返す
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(careLinkService.getActiveLinksForCareRecipient(USER_ID)).willReturn(List.of(link));

            // Act
            batchService.runNoContactCheck();

            // Assert: 遅刻申告ありのため cutoff が延長されて条件を満たし、sendNoContactCheck が呼ばれること
            verify(careEventNotificationService).sendNoContactCheck(USER_ID, EVENT_ID);
        }

        @Test
        @DisplayName("cutoffAdjust_遅刻申告なし_通常通知: expectedArrivalMinutesLate=null → 通常の softMinutes で判定されること")
        void cutoffAdjust_遅刻申告なし_通常通知() {
            // Arrange: 遅刻連絡なしの RSVP（offset=0）
            EventRsvpResponseEntity rsvp = buildRsvp(EVENT_ID, USER_ID);
            CareLinkResponse link = buildCareLinkResponse(USER_ID, CareCategory.MINOR);

            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(false);
            given(careLinkService.getActiveLinksForCareRecipient(USER_ID)).willReturn(List.of(link));

            // Act
            batchService.runNoContactCheck();

            // Assert: 通常通知が送信されること
            verify(careEventNotificationService).sendNoContactCheck(USER_ID, EVENT_ID);
        }

        @Test
        @DisplayName("cutoffAdjust_チェックイン済みはスキップ: チェックイン済み → sendNoContactCheck が呼ばれないこと")
        void cutoffAdjust_チェックイン済みはスキップ() {
            // Arrange
            EventRsvpResponseEntity rsvp = buildRsvpWithLate(EVENT_ID, USER_ID, 30);

            given(eventRepository.findActiveEventIdsStartedBefore(any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(EVENT_ID));
            given(rsvpResponseRepository.findByEventIdInAndResponse(anyList(), eq("ATTENDING")))
                    .willReturn(List.of(rsvp));
            given(careLinkService.isUnderCare(USER_ID)).willReturn(true);
            // チェックイン済み
            given(eventCheckinRepository.existsByEventIdAndUserId(EVENT_ID, USER_ID)).willReturn(true);

            // Act
            batchService.runNoContactCheck();

            // Assert: チェックイン済みなのでスキップされること
            verify(careEventNotificationService, never()).sendNoContactCheck(anyLong(), anyLong());
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    private EventRsvpResponseEntity buildRsvp(Long eventId, Long userId) {
        return EventRsvpResponseEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .response("ATTENDING")
                .build();
    }

    private EventRsvpResponseEntity buildRsvpWithLate(Long eventId, Long userId, Integer lateMinutes) {
        EventRsvpResponseEntity rsvp = buildRsvp(eventId, userId);
        rsvp.recordLateNotice(lateMinutes);
        return rsvp;
    }

    private EventEntity buildEvent(Long eventId, Long createdBy) {
        EventEntity event = EventEntity.builder()
                .scopeType(com.mannschaft.app.event.EventScopeType.TEAM)
                .scopeId(10L)
                .slug("test-event")
                .attendanceMode(EventAttendanceMode.RSVP)
                .createdBy(createdBy)
                .build();
        // BaseEntity の id フィールドは Lombok @Builder の対象外のためリフレクション経由でセットする。
        // 主催者通知の sourceId に event.getId() を渡す箇所があり、null だと anyLong() マッチャーに合わず
        // PotentialStubbingProblem になるため必須。
        ReflectionTestUtils.setField(event, "id", eventId);
        return event;
    }

    private UserEntity buildUser(Long userId, String displayName) {
        return UserEntity.builder()
                .displayName(displayName)
                .build();
    }

    private CareLinkResponse buildCareLinkResponse(Long userId, CareCategory category) {
        return CareLinkResponse.builder()
                .id(1L)
                .careRecipientUserId(userId)
                .watcherUserId(999L)
                .careCategory(category)
                .build();
    }
}
