package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03.3 Google カレンダー同期「チーム/組織予定の堅牢化」受け入れテスト（<b>red 先行 / 試練</b>）。
 *
 * <p>本テストは、既存の Phase 3 push 同期に潜む 4 つの穴を塞ぐための受け入れ条件を、
 * 実装より前に失敗するテストとして固定する。設計根拠: {@code docs/features/F03.3_google_calendar.md}
 * line 119 / 676 / 684-687（メンバーシップ検証・可視性/最小閲覧ロールフィルタ・退会連動・トグルOFF削除）。</p>
 *
 * <p><b>受け入れ条件↔テスト対応</b>:</p>
 * <ul>
 *   <li>AC-1: 非メンバーの同期トグルは拒否（存在秘匿・コントローラで 404 へ写像される BusinessException）</li>
 *   <li>AC-2: メンバーの同期トグルは成功・設定 upsert・バックフィル起動</li>
 *   <li>AC-3: {@code min_view_role=ADMIN_ONLY} のチーム予定は一般メンバーへ push されない（ScheduleCreatedEvent 経路）</li>
 *   <li>AC-4: 同条件でバックフィル経路（toggleTeamSync ON）でも push されない</li>
 *   <li>AC-5: SUPPORTER には MEMBER_PLUS 予定が push されず、MEMBER にはちょうど push される（境界値）</li>
 *   <li>AC-6: トグルOFF で当該ユーザー×スコープの Google イベント削除＋マッピング削除</li>
 *   <li>AC-7: {@code handleMembershipEnded}（MembershipEndedEvent 連動）で同期無効化＋Google イベント削除</li>
 *   <li>AC-8: キャンセル時に Google イベントが cancelled 化（=deleteEvent）され、通常 update ではない</li>
 *   <li>AC-9: Google 削除の個別失敗（404/410 相当）は継続・マッピング削除／それ以外の例外は握りつぶさない</li>
 *   <li>AC-10: 予定 0 件のチームでトグル ON → backfillCount=0 で正常応答</li>
 * </ul>
 *
 * <p><b>red の理由</b>: 現行実装は
 * {@link GoogleCalendarService#toggleTeamSync}/{@link GoogleCalendarService#toggleOrgSync} に
 * メンバーシップ検証がなく、push/backfill 経路に可視性・{@code min_view_role} フィルタがなく、
 * トグルOFF・退会で Google イベントを削除しないため、本テスト群は失敗する。
 * {@link GoogleApiClient#deleteEvent} と {@link GoogleCalendarService#handleMembershipEnded} は
 * 出陣で実装するシグネチャのみ先行定義したスケルトン（未実装で例外送出）。</p>
 *
 * <p>外部境界 {@link GoogleApiClient} は {@code @MockitoBean} で差し替え、実 HTTP を呼ばない。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Google カレンダー同期 チーム/組織予定 堅牢化 受け入れテスト（red）")
class GoogleCalendarTeamOrgSyncRobustnessIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private GoogleCalendarService googleCalendarService;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @Autowired
    private UserScheduleGoogleEventRepository googleEventRepository;

    @Autowired
    private UserCalendarSyncSettingRepository syncSettingRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private EncryptionService encryptionService;

    /** push/削除の外部境界。実 HTTP を避けるためモック化する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void stubGoogleApi() {
        // 新規イベント作成は毎回ユニークな eventId を返す（マッピング一意制約の衝突回避）。
        lenient().when(googleApiClient.createEvent(any(), any(), any()))
                .thenAnswer(inv -> new GoogleApiClient.CreateEventResponse(
                        "gevt-" + UUID.randomUUID(), "etag-" + UUID.randomUUID()));
        lenient().when(googleApiClient.updateEvent(any(), any(), any(), any()))
                .thenReturn("etag-updated");
        // deleteEvent はデフォルト no-op（べき等成功を表す）。
    }

    // ============================================================
    // AC-1: 非メンバーの同期トグルは拒否（存在秘匿→404）
    // ============================================================

    @Nested
    @DisplayName("AC-1: 非メンバーの同期トグル拒否")
    class AC1NonMemberRejected {

        /**
         * AC-1: 非メンバーが PUT /api/v1/me/teams/{teamId}/calendar-sync 相当（toggleTeamSync）を
         * 叩くと拒否される。コントローラはこの {@link BusinessException} を 404（存在秘匿）へ写像する。
         */
        @Test
        @DisplayName("AC-1: 非メンバーの toggleTeamSync は BusinessException（→404）で拒否される")
        void nonMember_toggleTeamSync_isRejected() {
            Long teamId = insertTeam("AC1-team");
            Long userId = insertUser("ac1-nonmember-team@test");
            insertActiveConnection(userId);
            // メンバーシップは付与しない（非メンバー）

            assertThatThrownBy(() -> googleCalendarService.toggleTeamSync(teamId, true, userId))
                    .as("非メンバーの同期トグルは拒否されるべき（存在秘匿・404 写像）")
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * AC-1（組織版）: 非メンバーが PUT /api/v1/me/organizations/{orgId}/calendar-sync 相当を叩くと拒否される。
         */
        @Test
        @DisplayName("AC-1: 非メンバーの toggleOrgSync は BusinessException（→404）で拒否される")
        void nonMember_toggleOrgSync_isRejected() {
            Long orgId = insertOrganization("AC1-org");
            Long userId = insertUser("ac1-nonmember-org@test");
            insertActiveConnection(userId);

            assertThatThrownBy(() -> googleCalendarService.toggleOrgSync(orgId, true, userId))
                    .as("非メンバーの組織同期トグルは拒否されるべき（存在秘匿・404 写像）")
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ============================================================
    // AC-2: メンバーの同期トグルは成功・upsert・バックフィル
    // ============================================================

    @Nested
    @DisplayName("AC-2: メンバーの同期トグル成功")
    class AC2MemberSucceeds {

        /**
         * AC-2: チームメンバーが toggleTeamSync ON を叩くと 200 相当（例外なし）・設定 upsert・
         * バックフィルが起動し、可視な予定が Google に push（マッピング作成）される。
         */
        @Test
        @DisplayName("AC-2: メンバーの toggleTeamSync ON で設定 upsert＋可視予定がバックフィルされる")
        void member_toggleTeamSync_upsertsAndBackfills() {
            Long teamId = insertTeam("AC2-team");
            Long userId = insertUser("ac2-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            // メンバーが閲覧可能な予定（MEMBER_PLUS）
            ScheduleEntity schedule = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);

            CalendarSyncToggleResponse response =
                    googleCalendarService.toggleTeamSync(teamId, true, userId);

            assertThat(response.isEnabled()).isTrue();
            // 設定が upsert された
            assertThat(syncSettingRepository
                    .findByUserIdAndScopeTypeAndScopeId(userId, "TEAM", teamId))
                    .as("同期設定が upsert されて有効になっているべき")
                    .isPresent()
                    .get()
                    .extracting(s -> s.getIsEnabled())
                    .isEqualTo(true);
            // 可視な予定がバックフィルで push された（マッピング作成）
            assertThat(googleEventRepository.findByUserIdAndScheduleId(userId, schedule.getId()))
                    .as("メンバーの可視予定はバックフィルで Google に push されるべき")
                    .isPresent();
        }
    }

    // ============================================================
    // AC-3: ADMIN_ONLY 予定は一般メンバーへ push されない（作成イベント経路）
    // ============================================================

    @Nested
    @DisplayName("AC-3: ADMIN_ONLY 予定の push 抑止（ScheduleCreatedEvent 経路）")
    class AC3AdminOnlyCreatedEvent {

        /**
         * AC-3: {@code min_view_role=ADMIN_ONLY} のチーム予定は、同期 ON の一般メンバーには
         * ScheduleCreatedEvent 駆動の push 経路で送られない。
         */
        @Test
        @DisplayName("AC-3: ADMIN_ONLY 予定は同期ONの一般メンバーへ作成イベント経路で push されない")
        void adminOnlySchedule_notPushedToMember_onCreatedEvent() {
            Long teamId = insertTeam("AC3-team");
            Long memberId = insertUser("ac3-member@test");
            insertActiveConnection(memberId);
            insertMembership(memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(memberId, "TEAM", teamId);
            ScheduleEntity adminOnly = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.ADMIN_ONLY);

            // @Async / @TransactionalEventListener を回避し、同一トランザクション内で同期実行するため
            // リスナーを手組みして直接呼び出す。
            GoogleCalendarEventListener listener = new GoogleCalendarEventListener(
                    googleCalendarService, syncSettingRepository, scheduleRepository, connectionRepository);
            listener.onScheduleCreated(new ScheduleCreatedEvent(
                    adminOnly.getId(), "TEAM", teamId, /*createdBy*/ memberId, /*attendanceRequired*/ false));

            assertThat(googleEventRepository.findByUserIdAndScheduleId(memberId, adminOnly.getId()))
                    .as("ADMIN_ONLY 予定は一般メンバーの Google カレンダーへ push されてはならない")
                    .isEmpty();
        }
    }

    // ============================================================
    // AC-4: ADMIN_ONLY 予定はバックフィル経路でも push されない
    // ============================================================

    @Nested
    @DisplayName("AC-4: ADMIN_ONLY 予定の push 抑止（バックフィル経路）")
    class AC4AdminOnlyBackfill {

        /**
         * AC-4: toggleTeamSync ON によるバックフィルでも、{@code min_view_role=ADMIN_ONLY} の予定は
         * 一般メンバーへ push されない。
         */
        @Test
        @DisplayName("AC-4: toggleTeamSync ON のバックフィルで ADMIN_ONLY 予定は一般メンバーへ push されない")
        void adminOnlySchedule_notPushedToMember_onBackfill() {
            Long teamId = insertTeam("AC4-team");
            Long memberId = insertUser("ac4-member@test");
            insertActiveConnection(memberId);
            insertMembership(memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            ScheduleEntity adminOnly = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.ADMIN_ONLY);

            googleCalendarService.toggleTeamSync(teamId, true, memberId);

            assertThat(googleEventRepository.findByUserIdAndScheduleId(memberId, adminOnly.getId()))
                    .as("バックフィルでも ADMIN_ONLY 予定は一般メンバーへ push されてはならない")
                    .isEmpty();
        }
    }

    // ============================================================
    // AC-5: MEMBER_PLUS の境界値（SUPPORTER 除外 / MEMBER 包含）
    // ============================================================

    @Nested
    @DisplayName("AC-5: MEMBER_PLUS 境界値（SUPPORTER除外・MEMBER包含）")
    class AC5MemberPlusBoundary {

        /**
         * AC-5: {@code min_view_role=MEMBER_PLUS} の予定は SUPPORTER には push されず、
         * MEMBER にはちょうど push される（境界値）。
         */
        @Test
        @DisplayName("AC-5: MEMBER_PLUS 予定は SUPPORTER に push されず MEMBER には push される")
        void memberPlusSchedule_excludesSupporter_includesMember() {
            Long teamId = insertTeam("AC5-team");
            ScheduleEntity memberPlus = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);

            Long supporterId = insertUser("ac5-supporter@test");
            insertActiveConnection(supporterId);
            insertMembership(supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);

            Long memberId = insertUser("ac5-member@test");
            insertActiveConnection(memberId);
            insertMembership(memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            // それぞれ自身の同期を ON（バックフィル経路）
            googleCalendarService.toggleTeamSync(teamId, true, supporterId);
            googleCalendarService.toggleTeamSync(teamId, true, memberId);

            assertThat(googleEventRepository.findByUserIdAndScheduleId(supporterId, memberPlus.getId()))
                    .as("MEMBER_PLUS 予定は SUPPORTER へ push されてはならない")
                    .isEmpty();
            assertThat(googleEventRepository.findByUserIdAndScheduleId(memberId, memberPlus.getId()))
                    .as("MEMBER_PLUS 予定は MEMBER へちょうど push されるべき（境界値の下限）")
                    .isPresent();
        }

        /**
         * CMP-017b: {@code min_view_role=ADMIN_ONLY} の境界は <b>DEPUTY_ADMIN</b> である。
         *
         * <p>設計書 {@code docs/features/F03.1_schedule_shared.md}「{@code ADMIN_ONLY}:
         * DEPUTY_ADMIN・ADMIN のみ閲覧可」に従い、閾値写像を
         * {@code com.mannschaft.app.schedule.visibility.MinViewRoleThreshold} へ一本化した際に
         * push 判定の宛先も変わった（従来は {@code "ADMIN"} 閾値へ写像しており
         * {@code RolePriority.isAtLeast("DEPUTY_ADMIN", "ADMIN")} が成立しないため
         * 副管理者を誤って弾いていた）。その変化を明示的に固定する。</p>
         *
         * <p>同 {@code ADMIN_ONLY} 予定が一般 MEMBER へ push されないことは AC-4 が固定しており、
         * 本テストは «緩めた» のではなく «境界を設計書の位置へ正した» ことの証跡である。</p>
         */
        @Test
        @DisplayName("CMP-017b: ADMIN_ONLY 予定は DEPUTY_ADMIN へ push される（境界は ADMIN ではない）")
        void adminOnlySchedule_pushedToDeputyAdmin() {
            Long teamId = insertTeam("AC5-deputy-team");
            ScheduleEntity adminOnly = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.ADMIN_ONLY);

            Long deputyId = insertUser("ac5-deputy@test");
            insertActiveConnection(deputyId);
            // memberships（所属）と user_roles（権限ロール）は別系統のため双方に行を張る。
            insertMembership(deputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, deputyId, "DEPUTY_ADMIN", teamId, null);
            em.flush();

            googleCalendarService.toggleTeamSync(teamId, true, deputyId);

            assertThat(googleEventRepository.findByUserIdAndScheduleId(deputyId, adminOnly.getId()))
                    .as("ADMIN_ONLY 予定は DEPUTY_ADMIN へ push されるべき（設計書 F03.1 の境界）")
                    .isPresent();
        }
    }

    // ============================================================
    // AC-6: トグルOFF で Google イベント削除＋マッピング削除
    // ============================================================

    @Nested
    @DisplayName("AC-6: トグルOFF での Google イベント削除")
    class AC6ToggleOffDeletes {

        /**
         * AC-6: toggleTeamSync OFF で、当該ユーザー×スコープの Google イベントが削除され
         * （{@link GoogleApiClient#deleteEvent} 呼び出し）、{@code user_schedule_google_events}
         * マッピングも削除される。
         */
        @Test
        @DisplayName("AC-6: toggleTeamSync OFF で Google イベント削除＋マッピング削除される")
        void toggleOff_deletesGoogleEvent_andMapping() {
            Long teamId = insertTeam("AC6-team");
            Long userId = insertUser("ac6-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(userId, "TEAM", teamId);
            ScheduleEntity schedule = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            insertMapping(userId, schedule.getId(), "gevt-ac6");

            googleCalendarService.toggleTeamSync(teamId, false, userId);
            em.flush();
            em.clear();

            verify(googleApiClient).deleteEvent(anyString(), anyString(), eq("gevt-ac6"));
            assertThat(googleEventRepository.findByUserIdAndScheduleId(userId, schedule.getId()))
                    .as("トグルOFF で当該ユーザー×スコープのマッピングは削除されるべき")
                    .isEmpty();
        }
    }

    // ============================================================
    // AC-7: 退会連動（handleMembershipEnded）で同期無効化＋削除
    // ============================================================

    @Nested
    @DisplayName("AC-7: 退会連動での同期無効化＋Google イベント削除")
    class AC7MembershipEnded {

        /**
         * AC-7: メンバーシップ終了（MembershipEndedEvent 連動の {@link GoogleCalendarService#handleMembershipEnded}）で、
         * 当該ユーザー×スコープの同期設定が無効化され、Google イベント削除が走り、マッピングも削除される。
         */
        @Test
        @DisplayName("AC-7: handleMembershipEnded で同期無効化＋Google イベント削除＋マッピング削除")
        void handleMembershipEnded_disablesSync_andDeletesGoogleEvents() {
            Long teamId = insertTeam("AC7-team");
            Long userId = insertUser("ac7-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(userId, "TEAM", teamId);
            ScheduleEntity schedule = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            insertMapping(userId, schedule.getId(), "gevt-ac7");

            // 退会連動リスナーが呼び出す想定のサービスメソッド（未実装スケルトン）
            googleCalendarService.handleMembershipEnded(userId, "TEAM", teamId);
            em.flush();
            em.clear();

            // 同期設定が無効化（または削除）されている
            Optional<Boolean> enabled = syncSettingRepository
                    .findByUserIdAndScopeTypeAndScopeId(userId, "TEAM", teamId)
                    .map(s -> s.getIsEnabled());
            assertThat(enabled.orElse(false))
                    .as("退会連動で当該スコープの同期設定は無効化されるべき")
                    .isFalse();
            // Google イベント削除が走り、マッピングも消える
            verify(googleApiClient).deleteEvent(anyString(), anyString(), eq("gevt-ac7"));
            assertThat(googleEventRepository.findByUserIdAndScheduleId(userId, schedule.getId()))
                    .as("退会連動で当該ユーザー×スコープのマッピングは削除されるべき")
                    .isEmpty();
        }
    }

    // ============================================================
    // AC-8: キャンセル時に cancelled 化（deleteEvent）される
    // ============================================================

    @Nested
    @DisplayName("AC-8: キャンセル時の Google イベント cancelled 化")
    class AC8CancelledEvent {

        /**
         * AC-8: ScheduleCancelledEvent 経路で、既存の Google イベントは cancelled 化（=削除）され、
         * 通常の update ではない。{@link GoogleApiClient#deleteEvent} が呼ばれ、
         * {@link GoogleApiClient#updateEvent} は呼ばれないことを検証する。
         */
        @Test
        @DisplayName("AC-8: キャンセルで deleteEvent が呼ばれ、updateEvent は呼ばれない")
        void cancelledSchedule_deletesGoogleEvent_notUpdate() {
            Long teamId = insertTeam("AC8-team");
            Long userId = insertUser("ac8-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(userId, "TEAM", teamId);
            ScheduleEntity schedule = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            insertMapping(userId, schedule.getId(), "gevt-ac8");
            // キャンセル状態へ
            schedule.cancel();
            scheduleRepository.saveAndFlush(schedule);

            GoogleCalendarEventListener listener = new GoogleCalendarEventListener(
                    googleCalendarService, syncSettingRepository, scheduleRepository, connectionRepository);
            listener.onScheduleCancelled(new com.mannschaft.app.schedule.event.ScheduleCancelledEvent(
                    schedule.getId(), userId));

            verify(googleApiClient).deleteEvent(anyString(), anyString(), eq("gevt-ac8"));
            verify(googleApiClient, times(0)).updateEvent(any(), any(), any(), any());
        }
    }

    // ============================================================
    // AC-9: 削除失敗のハンドリング（個別失敗は継続／それ以外は握りつぶさない）
    // ============================================================

    @Nested
    @DisplayName("AC-9: Google 削除失敗のハンドリング")
    class AC9DeleteFailureHandling {

        /**
         * AC-9（継続）: Google 削除が個別失敗（404/410 相当＝べき等成功として扱う）でも処理は継続し、
         * 全マッピングが削除される。複数マッピングを跨いで途中終了しないことを検証する。
         */
        @Test
        @DisplayName("AC-9: 削除がべき等成功でも複数マッピングを跨いで全て削除・継続する")
        void deleteContinues_removesAllMappings() {
            Long teamId = insertTeam("AC9a-team");
            Long userId = insertUser("ac9a-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(userId, "TEAM", teamId);
            ScheduleEntity s1 = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            ScheduleEntity s2 = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            insertMapping(userId, s1.getId(), "gevt-ac9a-1");
            insertMapping(userId, s2.getId(), "gevt-ac9a-2");

            googleCalendarService.toggleTeamSync(teamId, false, userId);
            em.flush();
            em.clear();

            assertThat(googleEventRepository.findByUserIdAndScheduleId(userId, s1.getId())).isEmpty();
            assertThat(googleEventRepository.findByUserIdAndScheduleId(userId, s2.getId()))
                    .as("個別削除が継続し、全マッピングが削除されるべき")
                    .isEmpty();
        }

        /**
         * AC-9（非握りつぶし）: Google 削除が 404/410 以外の恒久的エラー
         * （{@link GoogleCalendarErrorCode#GOOGLE_API_ERROR}）を投げた場合、症状を握り潰さず
         * 例外が呼び出し元へ伝播する。
         */
        @Test
        @DisplayName("AC-9: 削除の恒久的エラーは握りつぶさず伝播する")
        void deleteHardError_isNotSwallowed() {
            Long teamId = insertTeam("AC9b-team");
            Long userId = insertUser("ac9b-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            insertSyncSettingEnabled(userId, "TEAM", teamId);
            ScheduleEntity schedule = insertTeamSchedule(teamId,
                    ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            insertMapping(userId, schedule.getId(), "gevt-ac9b");

            doThrow(new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR))
                    .when(googleApiClient).deleteEvent(anyString(), anyString(), eq("gevt-ac9b"));

            assertThatThrownBy(() -> googleCalendarService.toggleTeamSync(teamId, false, userId))
                    .as("恒久的な Google 削除エラーは握りつぶさず伝播すべき")
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ============================================================
    // AC-10: 予定0件のチームでトグルON → backfillCount=0
    // ============================================================

    @Nested
    @DisplayName("AC-10: 予定0件チームのトグルON")
    class AC10EmptyTeam {

        /**
         * AC-10: 予定 0 件のチームでメンバーがトグル ON → backfillCount=0 で正常応答（例外なし）。
         */
        @Test
        @DisplayName("AC-10: 予定0件チームのトグルON は backfillCount=0 で正常応答する")
        void emptyTeam_toggleOn_backfillZero() {
            Long teamId = insertTeam("AC10-team");
            Long userId = insertUser("ac10-member@test");
            insertActiveConnection(userId);
            insertMembership(userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            CalendarSyncToggleResponse[] holder = new CalendarSyncToggleResponse[1];
            assertThatCode(() -> holder[0] = googleCalendarService.toggleTeamSync(teamId, true, userId))
                    .as("予定0件でもトグルON は例外なく成功すべき")
                    .doesNotThrowAnyException();
            assertThat(holder[0].isEnabled()).isTrue();
            assertThat(holder[0].getBackfillCount())
                    .as("予定0件チームの backfillCount は 0 であるべき")
                    .isEqualTo(0);
        }
    }

    // ============================================================
    // seed ヘルパー
    // ============================================================

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'テスト', '太郎', 'テスト 太郎', 'ACTIVE', "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        String uniqueName = name + "-" + UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", uniqueName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        String uniqueName = name + "-" + UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", uniqueName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
    }

    private void insertMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        membershipRepository.saveAndFlush(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private void insertActiveConnection(Long userId) {
        connectionRepository.saveAndFlush(UserGoogleCalendarConnectionEntity.builder()
                .userId(userId)
                .googleAccountEmail("user" + userId + "@gmail.com")
                .googleCalendarId("primary")
                .accessToken(encryptionService.encrypt("access-token-" + userId))
                .refreshToken(encryptionService.encrypt("refresh-token-" + userId))
                .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                .isActive(true)
                .personalSyncEnabled(false)
                .encryptionKeyVersion(1)
                .build());
    }

    private void insertSyncSettingEnabled(Long userId, String scopeType, Long scopeId) {
        syncSettingRepository.upsert(userId, scopeType, scopeId, true);
        em.flush();
    }

    private ScheduleEntity insertTeamSchedule(Long teamId, ScheduleVisibility visibility, MinViewRole minViewRole) {
        return scheduleRepository.saveAndFlush(ScheduleEntity.builder()
                .teamId(teamId)
                .title("test-schedule")
                .startAt(LocalDateTime.now().plusDays(1))
                .endAt(LocalDateTime.now().plusDays(1).plusHours(1))
                .allDay(false)
                .eventType(EventType.OTHER)
                .visibility(visibility)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .isException(false)
                .createdBy(0L)
                .build());
    }

    private void insertMapping(Long userId, Long scheduleId, String googleEventId) {
        googleEventRepository.saveAndFlush(UserScheduleGoogleEventEntity.builder()
                .userId(userId)
                .scheduleId(scheduleId)
                .googleEventId(googleEventId)
                .lastSyncedAt(LocalDateTime.now())
                .build());
    }
}
