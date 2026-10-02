package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleSource;
import com.mannschaft.app.schedule.entity.SyncDirection;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.NameResolverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Google Calendar Phase 4 — {@link GoogleCalendarService} etag 更新受け入れテスト（AC-13 red 先行）。
 *
 * <p>対象 AC: AC-13 — {@code syncScheduleToGoogle()} 完了後、
 * {@link UserScheduleGoogleEventEntity#getGoogleEtag()} が
 * Google API が返した ETag で更新されていること。</p>
 *
 * <p><b>仕様（設計書 P4-4 参照）</b>:</p>
 * <ul>
 *   <li>Mannschaft → Google への push 後（{@code createEvent} / {@code updateEvent}）、
 *       Google Calendar API がレスポンスの {@code ETag} ヘッダを返す</li>
 *   <li>このETag を {@code user_schedule_google_events.google_etag} に保存する</li>
 *   <li>次回 Webhook 受信時に条件付きリクエスト（IF-NONE-MATCH）で差分判定に使う</li>
 * </ul>
 *
 * <p><b>red の理由</b>: 現在の {@link GoogleCalendarService#syncScheduleToGoogle(ScheduleEntity, Long)}
 * は {@code createEvent()} / {@code updateEvent()} の戻り値から ETag を取得せず、
 * {@link UserScheduleGoogleEventEntity#updateGoogleEtag(String)} も呼んでいない。
 * したがってこのテストの {@code assertThat(mapping.getGoogleEtag()).isEqualTo(...)}
 * は失敗（null が返る）して red になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleCalendarService Phase 4 受け入れテスト — AC-13 ETag 更新（red）")
class GoogleCalendarServicePhase4Test {

    @Mock
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @Mock
    private UserCalendarSyncSettingRepository syncSettingRepository;

    @Mock
    private UserScheduleGoogleEventRepository googleEventRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private com.mannschaft.app.common.EncryptionService encryptionService;

    @Mock
    private GoogleApiClient googleApiClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GoogleCalendarWebhookService webhookService;

    @InjectMocks
    private GoogleCalendarService googleCalendarService;

    private static final Long USER_ID = 300L;
    private static final Long SCHEDULE_ID = 3001L;
    private static final String GOOGLE_EVENT_ID = "google-event-etag-test-001";
    private static final String EXPECTED_ETAG = "\"W/abc123def456\""; // Google 形式の ETag

    @BeforeEach
    void setUp() {
        // lenient: GOOGLE_IMPORT テストでは encryptionService が呼ばれないため、
        //          strict Mockito が UnnecessaryStubbingException を出さないよう lenient で設定する。
        org.mockito.Mockito.lenient()
                .when(encryptionService.decrypt(anyString())).thenReturn("raw_access_token");
    }

    // ========================================
    // AC-13: syncScheduleToGoogle 完了後に ETag が更新される
    // ========================================

    @Nested
    @DisplayName("AC-13: syncScheduleToGoogle 後の ETag 更新")
    class AC13EtagUpdatedAfterSync {

        @Test
        @DisplayName("AC-13: 新規スケジュールの push 後、UserScheduleGoogleEventEntity の googleEtag が Google 返却値で更新される")
        void syncNewSchedule_updatesGoogleEtag() {
            // given: アクティブな Google カレンダー接続
            UserGoogleCalendarConnectionEntity conn = UserGoogleCalendarConnectionEntity.builder()
                    .userId(USER_ID)
                    .googleAccountEmail("test@gmail.com")
                    .googleCalendarId("primary")
                    .accessToken("encrypted_access")
                    .refreshToken("encrypted_refresh")
                    .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                    .isActive(true)
                    .personalSyncEnabled(false)
                    .build();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // given: 既存マッピングなし（新規 push）
            // NOTE: schedule.getId() は @GeneratedValue のためテスト時は null → anyLong() でマッチしない。
            //       any() を使って引数問わず empty を返す。
            given(googleEventRepository.findByUserIdAndScheduleId(any(Long.class), any()))
                    .willReturn(Optional.empty());

            // given: Google createEvent が eventId と ETag を返す（Phase 4 実装）
            given(googleApiClient.createEvent(
                    eq("raw_access_token"),
                    eq("primary"),
                    any(GoogleApiClient.CalendarEventRequest.class)))
                    .willReturn(new GoogleApiClient.CreateEventResponse(GOOGLE_EVENT_ID, EXPECTED_ETAG));

            // given: 保存される UserScheduleGoogleEventEntity をキャプチャするための設定
            given(googleEventRepository.save(any(UserScheduleGoogleEventEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // given: スケジュール（source=MANNSCHAFT、新規 push 対象）
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("ETag テスト スケジュール")
                    .startAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 1, 12, 0))
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .source(ScheduleSource.MANNSCHAFT)
                    .build();

            // when: Google に push（ID=SCHEDULE_ID のスケジュール）
            googleCalendarService.syncScheduleToGoogle(schedule, USER_ID);

            // then: 保存された mapping の googleEtag が Google の返却 ETag と一致する
            // red: 現在の実装は ETag を取得せず googleEtag = null のまま保存する
            verify(googleEventRepository).save(
                    org.mockito.ArgumentMatchers.argThat(mapping ->
                            EXPECTED_ETAG.equals(mapping.getGoogleEtag())
                    )
            );
            // ^ 現在の実装では googleEtag に値をセットしないため
            // argThat 内の条件が false → verify 失敗 → red
        }

        @Test
        @DisplayName("AC-13: 既存スケジュールの update push 後も、ETag が最新値に更新される")
        void syncExistingSchedule_updatesGoogleEtagToLatest() {
            // given: アクティブな接続
            UserGoogleCalendarConnectionEntity conn = UserGoogleCalendarConnectionEntity.builder()
                    .userId(USER_ID)
                    .googleAccountEmail("test@gmail.com")
                    .googleCalendarId("primary")
                    .accessToken("encrypted_access")
                    .refreshToken("encrypted_refresh")
                    .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                    .isActive(true)
                    .personalSyncEnabled(false)
                    .build();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // given: 既存マッピング（古い etag が入っている）
            UserScheduleGoogleEventEntity existingMapping = UserScheduleGoogleEventEntity.builder()
                    .userId(USER_ID)
                    .scheduleId(SCHEDULE_ID)
                    .googleEventId(GOOGLE_EVENT_ID)
                    .lastSyncedAt(LocalDateTime.now().minusHours(1))
                    .syncDirection(SyncDirection.PUSH_ONLY)
                    .googleEtag("\"W/oldEtag\"") // 古い ETag
                    .build();
            // NOTE: schedule.getId() は @GeneratedValue のためテスト時は null → any() でマッチ
            given(googleEventRepository.findByUserIdAndScheduleId(any(Long.class), any()))
                    .willReturn(Optional.of(existingMapping));

            // given: Google updateEvent が（Phase 4 実装）新しい ETag を返す
            given(googleApiClient.updateEvent(
                    eq("raw_access_token"),
                    eq("primary"),
                    eq(GOOGLE_EVENT_ID),
                    any(GoogleApiClient.CalendarEventRequest.class)))
                    .willReturn(EXPECTED_ETAG);
            given(googleEventRepository.save(any(UserScheduleGoogleEventEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // given: スケジュール
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("ETag 更新テスト")
                    .startAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 2, 12, 0))
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .source(ScheduleSource.MANNSCHAFT)
                    .build();

            // when: 既存スケジュールを update push
            googleCalendarService.syncScheduleToGoogle(schedule, USER_ID);

            // then: 保存された mapping の googleEtag が新しい ETag に更新されている
            // red: 現在の実装は updateEvent 後も ETag を更新しない
            verify(googleEventRepository).save(
                    org.mockito.ArgumentMatchers.argThat(mapping ->
                            EXPECTED_ETAG.equals(mapping.getGoogleEtag())
                    )
            );
            // ^ ETag が更新されていないため失敗 → red
        }

        @Test
        @DisplayName("AC-13: source=GOOGLE_IMPORT スケジュールは push しないため ETag 更新も発生しない（AC-12 との整合）")
        void googleImportSchedule_notPushed_etagNotUpdated() {
            // NOTE: source=GOOGLE_IMPORT の場合は connectionRepository を参照する前に早期 return するため
            //       connectionRepository の stubbing は不要（設定すると UnnecessaryStubbingException）。

            // given: source=GOOGLE_IMPORT のスケジュール
            ScheduleEntity googleImportSchedule = ScheduleEntity.builder()
                    .teamId(10L)
                    .title("Google 取込")
                    .startAt(LocalDateTime.of(2026, 9, 3, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 3, 12, 0))
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .source(ScheduleSource.GOOGLE_IMPORT)
                    .build();

            // when: source=GOOGLE_IMPORT スケジュールに対して syncScheduleToGoogle を呼ぶ
            // Phase 4 では source=GOOGLE_IMPORT の場合は早期 return するため
            // Google API が呼ばれない（AC-12 との整合）。
            // このテストは AC-12 × AC-13 の組み合わせを確認する。
            googleCalendarService.syncScheduleToGoogle(googleImportSchedule, USER_ID);

            // then: Google API が呼ばれないこと
            // red（AC-12 side): 現在の実装は source チェックをしないため API が呼ばれる
            org.mockito.Mockito.verify(googleApiClient, org.mockito.Mockito.never())
                    .createEvent(anyString(), anyString(), any());
            org.mockito.Mockito.verify(googleApiClient, org.mockito.Mockito.never())
                    .updateEvent(anyString(), anyString(), anyString(), any());
        }
    }
}
