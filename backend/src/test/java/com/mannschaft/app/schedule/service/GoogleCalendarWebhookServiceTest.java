package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleSource;
import com.mannschaft.app.schedule.entity.SyncDirection;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google Calendar Phase 4 双方向同期 — Webhook サービス受け入れテスト（red 先行）。
 *
 * <p>テスト対象の AC:</p>
 * <ul>
 *   <li>AC-1: Google 新規イベント取り込み → スケジュール作成（source=GOOGLE_IMPORT）</li>
 *   <li>AC-2: Google 更新イベント取り込み → 既存スケジュール更新（タイトル/日時/場所のみ）</li>
 *   <li>AC-3: Google 削除イベント（status=cancelled）→ 論理削除（deleted_at セット）</li>
 *   <li>AC-4: 繰り返しイベント（recurringEventId 非 null）→ スキップ</li>
 *   <li>AC-8: チャンネル期限 3 日以内 → チャンネル更新メソッドが呼ばれる</li>
 *   <li>AC-10: 連携解除 → Webhook チャンネルが停止・DB から削除</li>
 *   <li>AC-11: 全日予定（start.dateTime null）→ all_day=true で登録</li>
 *   <li>AC-14: personal_sync が OFF → チャンネルが停止・削除</li>
 *   <li>AC-15: Google アカウント変更 → 旧チャンネル停止・新チャンネル登録</li>
 * </ul>
 *
 * <p><b>red の理由</b>: Phase 4 の受け入れテストが検証する Service メソッド群
 * （{@code GoogleCalendarWebhookService}、および既存 {@code GoogleCalendarService} への
 * Phase 4 拡張メソッド）は出陣（実装フェーズ）で追加される予定のため、
 * 現時点では {@code NoSuchBeanDefinitionException} またはアサート失敗で red になる。</p>
 *
 * <p>外部境界（{@link GoogleApiClient}）は各テストメソッド内でコメントに記載する形で
 * モック対象を明示する。実装フェーズでは {@code @MockitoBean} で差し替える。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Google Calendar Phase 4 Webhook サービス受け入れテスト（red）")
class GoogleCalendarWebhookServiceTest extends AbstractMySqlIntegrationTest {

    // ========================================
    // 注意: GoogleCalendarWebhookService は Phase 4 出陣で作成される予定。
    // 現時点では Bean が存在しないため、このテストクラスをロードすると
    // ApplicationContext の起動に失敗する可能性がある。
    //
    // 対処: AbstractMySqlIntegrationTest ベースの SpringBootTest が起動し、
    // Bean が存在しなければ @Autowired が required=false で null になるため、
    // 各テストメソッドで null チェックを assertThat(service).isNotNull() として
    // red を表現する。
    //
    // 将来（Phase 4 出陣後）は required=true に戻し、実際のサービスメソッドを呼ぶ。
    // ========================================

    /**
     * Phase 4 出陣で実装予定の Webhook 受信処理サービス。
     * 未実装のため null。各テストで assertThat(webhookService).isNotNull() にて red を確認。
     */
    @Autowired(required = false)
    private GoogleCalendarWebhookService webhookService;

    @Autowired
    private GoogleCalendarService googleCalendarService;

    @Autowired
    private GoogleCalendarWebhookChannelRepository webhookChannelRepository;

    @Autowired
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @Autowired
    private UserScheduleGoogleEventRepository googleEventRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long USER_ID = 9_800_001L;

    @BeforeEach
    void setUp() {
        // テスト用ユーザーと Google カレンダー連携情報をシードする
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES ('gcal.webhook.test@example.com', 'テスト', 'ウェブフック', "
                        + "'テスト ウェブフック', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "UPDATE users SET id = :uid WHERE email = 'gcal.webhook.test@example.com'")
                .setParameter("uid", USER_ID)
                .executeUpdate();
        em.flush();
    }

    // ========================================
    // AC-1: Google 新規イベント → Mannschaft スケジュール作成
    // ========================================

    @Nested
    @DisplayName("AC-1: Google 新規イベント取り込み")
    class AC1NewEventImport {

        @Test
        @DisplayName("AC-1: Google から新規イベントを受信した場合、source=GOOGLE_IMPORT でスケジュールが作成される")
        void importNewEvent_createsSchedule_withGoogleImportSource() {
            // red 確認: GoogleCalendarWebhookService が未実装のため null
            // 実装後はこの assertThat を削除し、サービスのメソッドを呼ぶ
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull(); // → null のため FAIL = red

            // TODO（Phase 4 出陣後に実装）:
            // given: Google Events List API から返ってくるイベント DTO を組み立てる
            //   GoogleApiClient をモック化（外部境界）し、以下を返すよう設定:
            //   - id: "google-event-001"
            //   - summary: "Google 経由イベント"
            //   - start.dateTime: "2026-08-01T10:00:00+09:00"
            //   - end.dateTime: "2026-08-01T12:00:00+09:00"
            //   - location: "渋谷スタジアム"
            //   - recurringEventId: null（繰り返しでない）
            //   - status: "confirmed"
            //
            // when: webhookService.processGoogleEventUpdate(USER_ID, googleEventDto);
            //
            // then:
            //   Optional<ScheduleEntity> saved = scheduleRepository.findAll().stream()
            //       .filter(s -> "google-event-001".equals(s.getGoogleCalendarEventId()))
            //       .findFirst();
            //   assertThat(saved).isPresent();
            //   assertThat(saved.get().getSource()).isEqualTo(ScheduleSource.GOOGLE_IMPORT);
            //   assertThat(saved.get().getTitle()).isEqualTo("Google 経由イベント");
            //   assertThat(saved.get().getLocation()).isEqualTo("渋谷スタジアム");
            //   assertThat(saved.get().getAllDay()).isFalse();
        }
    }

    // ========================================
    // AC-2: Google 更新イベント → 既存スケジュール更新
    // ========================================

    @Nested
    @DisplayName("AC-2: Google 更新イベント取り込み")
    class AC2UpdateEventImport {

        @Test
        @DisplayName("AC-2: Google から更新イベントを受信した場合、タイトル/日時/場所のみ更新され他フィールドは維持される")
        void updateEvent_updatesOnlyTitleDateTimeLocation() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given: 既存スケジュール（source=GOOGLE_IMPORT）を DB に登録
            //   UserScheduleGoogleEventEntity のマッピングも作成
            //   Google API からタイトル変更イベントを返すよう GoogleApiClient モック化
            //
            // when: webhookService.processGoogleEventUpdate(USER_ID, updatedGoogleEventDto);
            //
            // then:
            //   ScheduleEntity updated = scheduleRepository.findById(scheduleId).get();
            //   assertThat(updated.getTitle()).isEqualTo("更新後タイトル");
            //   assertThat(updated.getSource()).isEqualTo(ScheduleSource.GOOGLE_IMPORT); // source 変わらない
            //   assertThat(updated.getEventType()).isEqualTo(originalEventType); // eventType 変わらない
        }
    }

    // ========================================
    // AC-3: Google 削除イベント → 論理削除
    // ========================================

    @Nested
    @DisplayName("AC-3: Google 削除イベント（status=cancelled）")
    class AC3DeletedEventImport {

        @Test
        @DisplayName("AC-3: Google から status=cancelled イベントを受信した場合、対応スケジュールが論理削除される")
        void cancelledEvent_softDeletesSchedule() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given: 既存スケジュール + UserScheduleGoogleEventEntity をシード
            //   Google API: status="cancelled" を返すよう GoogleApiClient モック化
            //
            // when: webhookService.processGoogleEventUpdate(USER_ID, cancelledEventDto);
            //
            // then:
            //   ScheduleEntity schedule = em.createNativeQuery(
            //       "SELECT deleted_at FROM schedules WHERE id = :id")
            //       .setParameter("id", scheduleId).getSingleResult();
            //   assertThat(schedule.getDeletedAt()).isNotNull(); // 論理削除済み
        }
    }

    // ========================================
    // AC-4: 繰り返しイベント → スキップ
    // ========================================

    @Nested
    @DisplayName("AC-4: 繰り返しイベント（recurringEventId 非 null）")
    class AC4RecurringEventSkip {

        @Test
        @DisplayName("AC-4: recurringEventId が非 null のイベントは取り込まず、スケジュールが作成されない")
        void recurringEvent_isSkipped() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given: Google API: recurringEventId="parent-event-id" を持つイベントを返す
            //
            // when: webhookService.processGoogleEventUpdate(USER_ID, recurringEventDto);
            //
            // then: スケジュールが作成されていないことを確認
            //   assertThat(scheduleRepository.count()).isEqualTo(0L);
        }
    }

    // ========================================
    // AC-8: チャンネル期限 3日以内 → チャンネル更新
    // ========================================

    @Nested
    @DisplayName("AC-8: チャンネル有効期限 3日以内での更新トリガー")
    class AC8ChannelRenewalTrigger {

        @Test
        @DisplayName("AC-8: Webhook 受信時にチャンネル期限が 3 日以内なら、チャンネル更新処理が非同期で呼ばれる")
        void channelExpiresWithin3Days_triggersRenewal() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given: expires_at = NOW() + 2日 のチャンネルを DB に登録
            //   GoogleApiClient.watch() のモックを設定（チャンネル更新成功を返す）
            //
            // when: webhookService.receiveWebhookNotification(channelId, resourceState, token, resourceId);
            //
            // then:
            //   GoogleApiClient のモックで watch() が呼ばれたことを verify
            //   更新後の expires_at が NOW() + 6日23時間 付近であることを確認
        }

        @Test
        @DisplayName("AC-8: チャンネル期限が 3 日超の場合はチャンネル更新処理が呼ばれない")
        void channelExpiresAfter3Days_noRenewal() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO: expires_at = NOW() + 5日 → watch() が呼ばれないことを verify
        }
    }

    // ========================================
    // AC-10: 連携解除 → Webhook チャンネル停止・削除
    // ========================================

    @Nested
    @DisplayName("AC-10: 連携解除時の Webhook チャンネル停止")
    class AC10DisconnectStopsChannel {

        @Test
        @DisplayName("AC-10: disconnect() 呼び出し後、Webhook チャンネルが停止され DB から削除される")
        void disconnect_stopsWebhookChannel_and_deletesFromDb() {
            // given: 既存の Webhook チャンネルを DB に登録
            GoogleCalendarWebhookChannelEntity channel = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(USER_ID)
                    .channelId("test-channel-disconnect-001")
                    .resourceId("test-resource-disconnect-001")
                    .channelToken("test-token-disconnect-001")
                    .expiresAt(LocalDateTime.now().plusDays(5))
                    .build();
            webhookChannelRepository.save(channel);
            em.flush();

            // Google アカウント連携を設定（disconnect に必要）
            em.createNativeQuery(
                    "INSERT INTO user_google_calendar_connections "
                            + "(user_id, google_account_email, google_calendar_id, "
                            + "access_token, refresh_token, token_expires_at, "
                            + "is_active, personal_sync_enabled, encryption_key_version, "
                            + "created_at, updated_at) "
                            + "VALUES (:uid, 'test@gmail.com', 'primary', "
                            + "'enc_access', 'enc_refresh', DATE_ADD(NOW(), INTERVAL 1 HOUR), "
                            + "1, 0, 1, NOW(), NOW())")
                    .setParameter("uid", USER_ID)
                    .executeUpdate();
            em.flush();

            // NOTE: GoogleApiClient.revokeToken() と GoogleApiClient.stopChannel() の
            // モック化は Phase 4 実装後に @MockitoBean で追加する。
            // 現在は GoogleApiClient が外部 API を直接呼ぶため、
            // このテストは GoogleApiClient の HTTP 呼び出しで失敗する（= red の一形態）。

            // when & then:
            // Phase 4 実装後: googleCalendarService.disconnect(USER_ID) が
            // webhook チャンネルも停止・削除することを確認する。
            // 現在の disconnect() は Webhook チャンネルを操作しないため FAIL:
            //   googleCalendarService.disconnect(USER_ID);
            //   Optional<GoogleCalendarWebhookChannelEntity> found =
            //       webhookChannelRepository.findByUserId(USER_ID);
            //   assertThat(found).isEmpty(); // → 削除されていないため FAIL = red

            // コンパイル通過のみ目的の placeholder アサート（red を表現）:
            assertThat(webhookService)
                    .as("Phase 4 未実装: disconnect 時の Webhook チャンネル停止・削除が未実装 (red)")
                    .isNotNull();
        }
    }

    // ========================================
    // AC-11: 全日予定 → all_day=true
    // ========================================

    @Nested
    @DisplayName("AC-11: 全日予定（start.dateTime null）の取り込み")
    class AC11AllDayEvent {

        @Test
        @DisplayName("AC-11: start.dateTime が null（全日予定）の場合、all_day=true でスケジュールが作成される")
        void allDayEvent_setsAllDayTrue() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given: Google API が start.date="2026-08-01", start.dateTime=null を返す
            //
            // when: webhookService.processGoogleEventUpdate(USER_ID, allDayEventDto);
            //
            // then:
            //   ScheduleEntity schedule = scheduleRepository.findAll().stream()
            //       .filter(s -> "all-day-event-001".equals(s.getGoogleCalendarEventId()))
            //       .findFirst().get();
            //   assertThat(schedule.getAllDay()).isTrue();
        }
    }

    // ========================================
    // AC-14: personal_sync OFF → チャンネル停止・削除
    // ========================================

    @Nested
    @DisplayName("AC-14: 個人同期 OFF 時の Webhook チャンネル削除")
    class AC14PersonalSyncOffDeletesChannel {

        @Test
        @DisplayName("AC-14: personal_sync を OFF にした場合、Webhook チャンネルが停止・DB から削除される")
        void personalSyncOff_stopsAndDeletesWebhookChannel() {
            // given: DB にチャンネルを登録し、personal_sync が ON の連携情報を設定
            GoogleCalendarWebhookChannelEntity channel = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(USER_ID)
                    .channelId("personal-sync-off-channel")
                    .resourceId("personal-sync-off-resource")
                    .channelToken("personal-sync-off-token")
                    .expiresAt(LocalDateTime.now().plusDays(5))
                    .build();
            webhookChannelRepository.save(channel);
            em.flush();

            // when: personal_sync を OFF にする（Phase 4 では togglePersonalSync が
            // チャンネル停止・削除も担当する予定）

            // then: チャンネルが DB から消えていることを確認
            // 現在の togglePersonalSync() は Webhook チャンネルを操作しないため
            // 以下のアサートは FAIL = red:
            //   googleCalendarService.togglePersonalSync(false, USER_ID);
            //   assertThat(webhookChannelRepository.findByUserId(USER_ID)).isEmpty();

            assertThat(webhookService)
                    .as("Phase 4 未実装: personal_sync OFF 時の Webhook チャンネル停止が未実装 (red)")
                    .isNotNull();
        }
    }

    // ========================================
    // AC-15: Google アカウント変更 → 旧チャンネル停止・新チャンネル登録
    // ========================================

    @Nested
    @DisplayName("AC-15: Google アカウント変更時のチャンネル切り替え")
    class AC15AccountChangeRotatesChannel {

        @Test
        @DisplayName("AC-15: Google アカウント変更時に旧 Webhook チャンネルが停止され、新チャンネルが登録される")
        void accountChange_stopsOldChannel_registersNewChannel() {
            assertThat(webhookService)
                    .as("Phase 4 未実装: GoogleCalendarWebhookService が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given:
            //   旧アカウント（old@gmail.com）の Webhook チャンネルを DB に登録
            //   GoogleApiClient.stopChannel() のモックを設定
            //   GoogleApiClient.watch()（新チャンネル登録）のモックを設定
            //
            // when: Google アカウントを new@gmail.com に変更（connect() 呼び出し）
            //
            // then:
            //   旧チャンネルが停止され（GoogleApiClient.stopChannel() が呼ばれた）
            //   新チャンネルが DB に登録されている
            //   assertThat(webhookChannelRepository.findByUserId(USER_ID))
            //       .map(GoogleCalendarWebhookChannelEntity::getChannelId)
            //       .hasValue("new-channel-id"); // 新チャンネルの ID
        }
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * Google IMPORT 元のスケジュールを DB に直接挿入する（テスト用）。
     *
     * @param googleEventId Google カレンダーイベント ID
     * @return 挿入したスケジュールの ID
     */
    private Long insertGoogleImportSchedule(String googleEventId) {
        em.createNativeQuery(
                "INSERT INTO schedules (user_id, title, start_at, all_day, "
                        + "event_type, visibility, min_view_role, status, "
                        + "attendance_required, include_supporters, team_breakdown_enabled, "
                        + "is_exception, allow_proxy_attendance, is_proxy_auto_accept, "
                        + "google_calendar_event_id, source, "
                        + "created_at, updated_at) "
                        + "VALUES (:uid, 'Google 取込予定', DATE_ADD(NOW(), INTERVAL 1 DAY), 0, "
                        + "'OTHER', 'MEMBERS_ONLY', 'MEMBER_PLUS', 'SCHEDULED', "
                        + "0, 0, 0, 0, 0, 0, "
                        + ":googleEventId, 'GOOGLE_IMPORT', "
                        + "NOW(), NOW())")
                .setParameter("uid", USER_ID)
                .setParameter("googleEventId", googleEventId)
                .executeUpdate();
        em.flush();

        return ((Number) em.createNativeQuery(
                "SELECT id FROM schedules WHERE google_calendar_event_id = :gid")
                .setParameter("gid", googleEventId)
                .getSingleResult()).longValue();
    }
}
