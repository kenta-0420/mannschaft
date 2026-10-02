package com.mannschaft.app.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationSettingsEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.NotificationSettingsRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知・通知設定・プッシュ購読の自己スコープエンドポイント 契約テスト（認可根治戦役 Wave4 ロットD）。
 *
 * <p>本テストは {@link com.mannschaft.app.notification.controller.NotificationController} /
 * {@link com.mannschaft.app.notification.controller.NotificationPreferenceController} /
 * {@link com.mannschaft.app.notification.controller.PushSubscriptionController} に付与した
 * {@code @SelfScopedEndpoint} の宣言（＝「検索・更新の対象が認証主体に束縛され、他人のデータへ
 * 構造的に到達できない」）を固定する。他ユーザーのデータを併存させたうえで、呼び出しユーザー自身の
 * 結果のみが返る／自身の行のみが変更されることを確認する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("通知・通知設定・プッシュ購読 自己スコープ契約テスト（認可根治 Wave4 ロットD）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationTypePreferenceRepository typePreferenceRepository;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Autowired
    private CommitteeRepository committeeRepository;

    @Autowired
    private CommitteeMemberRepository committeeMemberRepository;

    @PersistenceContext
    private EntityManager em;

    /** 本テスト専用の固有ユーザーID（他 IT のフィクスチャと衝突しないレンジを使う）。 */
    private static final Long ME = 916401L;
    private static final Long OTHER = 916402L;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        for (Long userId : new Long[] {ME, OTHER}) {
            notificationRepository.deleteAll(
                    notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).getContent());
            preferenceRepository.deleteByUserId(userId);
            typePreferenceRepository.deleteByUserId(userId);
            settingsRepository.deleteByUserId(userId);
            pushSubscriptionRepository.deleteByUserId(userId);
        }
    }

    private NotificationEntity saveNotification(Long userId, String title) {
        // scope_type は NOT NULL（NotificationEntity.java:64-65）。特定チーム/組織に紐付かない
        // 個人宛通知のため NotificationScopeType.PERSONAL を用いる
        // （本番実装でも個人宛通知は同様に PERSONAL を使う。例: ContactRequestService.java:265）。
        return notificationRepository.saveAndFlush(NotificationEntity.builder()
                .userId(userId)
                .notificationType("SYSTEM_ANNOUNCEMENT")
                .title(title)
                .body("本文")
                .sourceType("SYSTEM")
                .sourceId(1L)
                .scopeType(NotificationScopeType.PERSONAL)
                .build());
    }

    // ═════════════════════════════════════════════════════════════════════
    // NotificationController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NotificationController#getUnreadCount / #markAllAsRead")
    class NotificationSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("getUnreadCount は自分宛の未読件数のみを数える")
        void getUnreadCount_は自分の未読のみ数える() throws Exception {
            saveNotification(ME, "自分宛1");
            saveNotification(ME, "自分宛2");
            saveNotification(OTHER, "他人宛");

            // UnreadCountResponse のフィールド名は unreadCount（count ではない。
            // UnreadCountResponse.java: private final long unreadCount;）。
            mockMvc.perform(get("/api/v1/notifications/unread-count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unreadCount").value(2));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("markAllAsRead は自分宛の通知のみ既読化し、他ユーザーの通知は変化しない")
        void markAllAsRead_は自分の通知のみ既読化する() throws Exception {
            NotificationEntity mine = saveNotification(ME, "自分宛");
            NotificationEntity others = saveNotification(OTHER, "他人宛");

            mockMvc.perform(post("/api/v1/notifications/read-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1));

            // markAllAsReadByUserId は @Modifying の JPQL 一括 UPDATE
            // （NotificationRepository.java:49-51）であり、永続化コンテキスト上で既に管理されている
            // mine/others のインスタンスへは自動反映されない。flush で未確定の変更を確定させたうえで
            // clear して 1 次キャッシュを捨て、DB の実体を素通しで引き直す（flush 無しの clear は
            // 未確定の変更ごと捨てるため対で書く）。
            em.flush();
            em.clear();
            assertThat(notificationRepository.findById(mine.getId()).orElseThrow().getIsRead()).isTrue();
            assertThat(notificationRepository.findById(others.getId()).orElseThrow().getIsRead()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // NotificationPreferenceController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NotificationPreferenceController の自己スコープ EP 群")
    class PreferenceSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("listPreferences は自分の設定のみを返す")
        void listPreferences_は自分の設定のみ返す() throws Exception {
            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(ME).scopeType("TEAM").scopeId(1L).isEnabled(false).build());
            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(OTHER).scopeType("TEAM").scopeId(1L).isEnabled(false).build());

            mockMvc.perform(get("/api/v1/notification-preferences"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("updatePreference は自分の行のみを作成・更新し、他ユーザーの行には触れない")
        void updatePreference_は自分の行のみ更新する() throws Exception {
            // 本EPは非メンバーのチーム書き込みを403で拒否するようになったため、行の分離
            // （自分の行のみ作成・更新し他人の行には触れない）を検証するには ME が当該チームの
            // 正規メンバーである前提が要る（CMP-260917 認可根治・ScopeAuthorizationRed 導入に伴う
            // 前提追加。行の分離という本テストの趣旨・アサーションは変更していない）。
            MembershipTestHelper.insertMembership(em, ME, ScopeType.TEAM, 9L, RoleKind.MEMBER);
            em.flush();

            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(OTHER).scopeType("TEAM").scopeId(9L).isEnabled(true).build());

            mockMvc.perform(put("/api/v1/notification-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"scopeType\":\"TEAM\",\"scopeId\":9,\"isEnabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isEnabled").value(false));

            assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", 9L))
                    .isPresent();
            assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(OTHER, "TEAM", 9L)
                            .orElseThrow().getIsEnabled())
                    .isTrue();
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("listTypePreferences は自分の上書き設定のみを反映する")
        void listTypePreferences_は自分の上書きのみ反映する() throws Exception {
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(ME).notificationType("DAILY_DIGEST").isEnabled(true).build());
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(OTHER).notificationType("DAILY_DIGEST").isEnabled(false).build());

            mockMvc.perform(get("/api/v1/notification-type-preferences"))
                    .andExpect(status().isOk());

            assertThat(typePreferenceRepository.findByUserId(ME)).hasSize(1);
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("bulkUpdateTypePreferences は 1 件ずつ自分の行のみを対象にする")
        void bulkUpdateTypePreferences_は自分の行のみ更新する() throws Exception {
            // notificationType は NotificationType enum の name() と一致する必要がある
            // （NotificationPreferenceService#bulkUpdateTypePreferences が
            // NotificationType.fromValue で解決できない値を BusinessException として弾く）。
            // "MENTION" は存在せず "CHAT_MENTION" / "TIMELINE_MENTION" が正しい値
            // （NotificationType.java）。URGENT（isLocked）ではない CHAT_MENTION を使う。
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(OTHER).notificationType("CHAT_MENTION").isEnabled(true).build());

            mockMvc.perform(put("/api/v1/notification-type-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"preferences\":[{\"notificationType\":\"CHAT_MENTION\","
                                    + "\"isEnabled\":false,\"channelOverride\":false}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.updatedCount").value(1));

            assertThat(typePreferenceRepository.findByUserIdAndNotificationType(ME, "CHAT_MENTION")
                            .orElseThrow().getIsEnabled())
                    .isFalse();
            assertThat(typePreferenceRepository.findByUserIdAndNotificationType(OTHER, "CHAT_MENTION")
                            .orElseThrow().getIsEnabled())
                    .isTrue();
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("getSettings は自分のグローバル設定のみを返す")
        void getSettings_は自分の設定を返す() throws Exception {
            settingsRepository.save(NotificationSettingsEntity.builder()
                    .userId(OTHER).priorityAutoDelivery(false).build());

            mockMvc.perform(get("/api/v1/notification-settings"))
                    .andExpect(status().isOk())
                    // 自分の行が無いため既定値 true が返り、他ユーザーの false に影響されない。
                    .andExpect(jsonPath("$.data.priorityAutoDelivery").value(true));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("updateSettings は自分の行のみを更新し、他ユーザーの行には触れない")
        void updateSettings_は自分の行のみ更新する() throws Exception {
            settingsRepository.save(NotificationSettingsEntity.builder()
                    .userId(OTHER).priorityAutoDelivery(true).build());

            mockMvc.perform(put("/api/v1/notification-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"priorityAutoDelivery\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.priorityAutoDelivery").value(false));

            assertThat(settingsRepository.findByUserId(ME).orElseThrow().getPriorityAutoDelivery()).isFalse();
            assertThat(settingsRepository.findByUserId(OTHER).orElseThrow().getPriorityAutoDelivery()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PushSubscriptionController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PushSubscriptionController#subscribe")
    class PushSubscriptionSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("subscribe は呼び出しユーザーを購読の所有者として登録する")
        void subscribe_は本人所有で登録される() throws Exception {
            String endpoint = "https://push.example.com/w4c-lot-d-" + System.nanoTime();

            mockMvc.perform(post("/api/v1/push-subscriptions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "endpoint", endpoint,
                                    "p256dhKey", "p256dh-dummy",
                                    "authKey", "auth-dummy"))))
                    .andExpect(status().isCreated());

            PushSubscriptionEntity saved = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
            assertThat(saved.getUserId()).isEqualTo(ME);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // NotificationPreferenceController#updatePreference / #listPreferences
    // スコープ検証の欠落（試練・red）
    //
    // 現状 updatePreference はスコープ所属を一切検証しない（scopeId に任意の値を渡せば
    // 他テナントのチーム/組織/委員会の行を作成・更新できる）。listPreferences もスコープ
    // 所属を検証せずに実名を返す（存在すれば実名・非実在なら「不明なチーム」の名前列挙オラクル）。
    // 本ブロックは軍議で定めた認可式（isSystemAdmin || isAdminOrAbove || isMember）と
    // スコープ別の資格（TEAM/ORGANIZATION/PERSONAL/SYSTEM/FRIEND_TEAM/FRIEND_FOLDER/COMMITTEE）を
    // 固定する。実装（NotificationPreferenceService/Controller）はまだ検証を持たないため、
    // 以下は全て現状 red（403/400 を期待するが実際は 200 が返る）である。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("updatePreference/listPreferences のスコープ検証（試練・red）")
    class ScopeAuthorizationRed {

        private static final String PUT_PATH = "/api/v1/notification-preferences";
        private static final String GET_PATH = "/api/v1/notification-preferences";

        // ── TEAM ──────────────────────────────────────────────────────
        private static final long TEAM_MEMBER = 918101L;
        private static final long TEAM_NONMEMBER = 918102L;
        private static final long TEAM_ADMIN_VIA_USERROLE_ONLY = 918103L;

        // ── ORGANIZATION ──────────────────────────────────────────────
        private static final long ORG_DIRECT_MEMBER = 918104L;
        private static final long ORG_NONMEMBER = 918105L;
        private static final long ORG_ADMIN_VIA_USERROLE_ONLY = 918106L;
        private static final long ORG_DESCENDANT_TEAM_ONLY = 918107L;

        // ── COMMITTEE ─────────────────────────────────────────────────
        private static final long COMMITTEE_MEMBER = 918108L;
        private static final long COMMITTEE_NONMEMBER = 918109L;

        // ── PERSONAL / SYSTEM ─────────────────────────────────────────
        private static final long PERSONAL_SELF = 918110L;
        private static final long PERSONAL_OTHER = 918111L;

        // ── 入力検証・既存行バイパス・読み取り側漏えい・N+1 ──────────
        private static final long EXISTING_ROW_USER = 918114L;
        private static final long LEAK_USER = 918117L;
        private static final long N1_SMALL_USER = 918115L;
        private static final long N1_LARGE_USER = 918116L;

        // ── FRIEND_TEAM ───────────────────────────────────────────────
        private static final long FRIEND_TEAM_MEMBER = 918118L;
        private static final long FRIEND_TEAM_NONMEMBER = 918119L;

        // ── FRIEND_FOLDER ─────────────────────────────────────────────
        private static final long FRIEND_FOLDER_USER = 918120L;

        private String putBody(String scopeType, Long scopeId, boolean isEnabled) {
            // scopeId は null 許容（PERSONAL/SYSTEM）のため手組み JSON にする。
            String scopeTypeJson = scopeType == null ? "null" : "\"" + scopeType + "\"";
            String scopeIdJson = scopeId == null ? "null" : String.valueOf(scopeId);
            return "{\"scopeType\":" + scopeTypeJson + ",\"scopeId\":" + scopeIdJson
                    + ",\"isEnabled\":" + isEnabled + "}";
        }

        private void assertNoRowCreated(Long userId, String scopeType, Long scopeId) {
            assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId))
                    .as("拒否された更新でも行が作成されてはならない")
                    .isEmpty();
        }

        // ═════════════════════════════════════════════════════════════
        // TEAM
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("scopeType=TEAM")
        class TeamScope {

            @Test
            @WithMockUser(username = "918102")
            @DisplayName("AC-10 非所属チームへの更新は403・行も作成されない")
            void AC10_非所属チームは403() throws Exception {
                Long teamId = insertTeam("AC10 非所属チーム");

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("TEAM", teamId, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(TEAM_NONMEMBER, "TEAM", teamId);
            }

            @Test
            @WithMockUser(username = "918101")
            @DisplayName("AC-11 所属チームへの更新は成功する")
            void AC11_所属チームは成功() throws Exception {
                Long teamId = insertTeam("AC11 所属チーム");
                MembershipTestHelper.insertMembership(em, TEAM_MEMBER, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("TEAM", teamId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918103")
            @DisplayName("AC-11b user_rolesのみのADMIN（membershipsなし）でも成功する")
            void AC11b_userRolesのみのADMINは成功() throws Exception {
                Long teamId = insertTeam("AC11b user_rolesのみADMINチーム");
                MembershipTestHelper.insertActiveUser(em, TEAM_ADMIN_VIA_USERROLE_ONLY);
                MembershipTestHelper.insertUserRole(em, TEAM_ADMIN_VIA_USERROLE_ONLY, "ADMIN", teamId, null);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("TEAM", teamId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }
        }

        // ═════════════════════════════════════════════════════════════
        // ORGANIZATION
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("scopeType=ORGANIZATION")
        class OrganizationScope {

            @Test
            @WithMockUser(username = "918105")
            @DisplayName("AC-12 非所属組織への更新は403・行も作成されない")
            void AC12_非所属組織は403() throws Exception {
                Long orgId = insertOrganization("AC12 非所属組織");

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("ORGANIZATION", orgId, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(ORG_NONMEMBER, "ORGANIZATION", orgId);
            }

            @Test
            @WithMockUser(username = "918104")
            @DisplayName("AC-13 直接所属組織への更新は成功する")
            void AC13_直接所属組織は成功() throws Exception {
                Long orgId = insertOrganization("AC13 直接所属組織");
                MembershipTestHelper.insertMembership(em, ORG_DIRECT_MEMBER, ScopeType.ORGANIZATION, orgId,
                        RoleKind.MEMBER);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("ORGANIZATION", orgId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918106")
            @DisplayName("AC-13b user_rolesのみのADMIN/DEPUTY_ADMIN（membershipsなし）でも成功する")
            void AC13b_userRolesのみのADMINは成功() throws Exception {
                Long orgId = insertOrganization("AC13b user_rolesのみADMIN組織");
                MembershipTestHelper.insertActiveUser(em, ORG_ADMIN_VIA_USERROLE_ONLY);
                MembershipTestHelper.insertUserRole(em, ORG_ADMIN_VIA_USERROLE_ONLY, "DEPUTY_ADMIN", null, orgId);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("ORGANIZATION", orgId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918107")
            @DisplayName("AC-14 配下チームにのみ所属し組織には直接所属していない場合は403")
            void AC14_配下チームのみ所属は403() throws Exception {
                Long orgId = insertOrganization("AC14 組織（配下チームのみ）");
                Long teamId = insertTeam("AC14 配下チーム");
                insertTeamOrgMembership(teamId, orgId);
                MembershipTestHelper.insertMembership(em, ORG_DESCENDANT_TEAM_ONLY, ScopeType.TEAM, teamId,
                        RoleKind.MEMBER);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("ORGANIZATION", orgId, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(ORG_DESCENDANT_TEAM_ONLY, "ORGANIZATION", orgId);
            }
        }

        // ═════════════════════════════════════════════════════════════
        // FRIEND_TEAM
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("scopeType=FRIEND_TEAM")
        class FriendTeamScope {

            @Test
            @WithMockUser(username = "918119")
            @DisplayName("AC-15 非所属チームIDのFRIEND_TEAMは403")
            void AC15_非所属は403() throws Exception {
                Long teamId = insertTeam("AC15 FRIEND_TEAM非所属チーム");

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("FRIEND_TEAM", teamId, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(FRIEND_TEAM_NONMEMBER, "FRIEND_TEAM", teamId);
            }

            @Test
            @WithMockUser(username = "918118")
            @DisplayName("受信者自身が所属するチームIDのFRIEND_TEAMは成功する（TEAMと同じ資格）")
            void 所属チームは成功() throws Exception {
                Long teamId = insertTeam("AC FRIEND_TEAM所属チーム");
                MembershipTestHelper.insertMembership(em, FRIEND_TEAM_MEMBER, ScopeType.TEAM, teamId,
                        RoleKind.MEMBER);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("FRIEND_TEAM", teamId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }
        }

        // ═════════════════════════════════════════════════════════════
        // COMMITTEE
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("scopeType=COMMITTEE")
        class CommitteeScope {

            @Test
            @WithMockUser(username = "918109")
            @DisplayName("AC-16 非メンバーの委員会は403")
            void AC16_非メンバーは403() throws Exception {
                Long orgId = insertOrganization("AC16 委員会所属組織");
                Long committeeId = insertCommittee(orgId, "AC16 委員会");

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("COMMITTEE", committeeId, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(COMMITTEE_NONMEMBER, "COMMITTEE", committeeId);
            }

            @Test
            @WithMockUser(username = "918108")
            @DisplayName("AC-17 現役メンバーの委員会は成功する")
            void AC17_現役メンバーは成功() throws Exception {
                Long orgId = insertOrganization("AC17 委員会所属組織");
                Long committeeId = insertCommittee(orgId, "AC17 委員会");
                insertCommitteeMember(committeeId, COMMITTEE_MEMBER, CommitteeRole.MEMBER);
                em.flush();

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("COMMITTEE", committeeId, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }
        }

        // ═════════════════════════════════════════════════════════════
        // PERSONAL / SYSTEM / FRIEND_FOLDER
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("scopeType=PERSONAL / SYSTEM / FRIEND_FOLDER")
        class PersonalSystemFolderScope {

            @Test
            @WithMockUser(username = "918110")
            @DisplayName("AC-18 PERSONAL・scopeId=nullは成功する")
            void AC18_personal_scopeIdNullは成功() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("PERSONAL", null, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918110")
            @DisplayName("AC-19 PERSONAL・scopeId=自分のuserIdは成功する")
            void AC19_personal_自分のuserIdは成功() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("PERSONAL", PERSONAL_SELF, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918110")
            @DisplayName("AC-20 PERSONAL・scopeId=他人のuserIdは403")
            void AC20_personal_他人のuserIdは403() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("PERSONAL", PERSONAL_OTHER, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(PERSONAL_SELF, "PERSONAL", PERSONAL_OTHER);
            }

            @Test
            @WithMockUser(username = "918112")
            @DisplayName("AC-21 SYSTEM・scopeId=nullは成功する")
            void AC21_system_scopeIdNullは成功() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("SYSTEM", null, false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.isEnabled").value(false));
            }

            @Test
            @WithMockUser(username = "918120")
            @DisplayName("AC-22 FRIEND_FOLDERは常に403（使用箇所ゼロ・不可）")
            void AC22_friendFolderは常に403() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("FRIEND_FOLDER", 1L, true)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertNoRowCreated(FRIEND_FOLDER_USER, "FRIEND_FOLDER", 1L);
            }
        }

        // ═════════════════════════════════════════════════════════════
        // 既存行がある場合の更新でもスコープ検証が行われる
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("既存行の更新迂回")
        class ExistingRowBypass {

            @Test
            @WithMockUser(username = "918114")
            @DisplayName("AC-23 既存行があっても非所属スコープの更新は403（既存行を迂回路にしない）")
            void AC23_既存行があっても非所属は403() throws Exception {
                Long teamId = insertTeam("AC23 既存行迂回チーム");
                // 非所属スコープの行を直接 INSERT しておく（PUT を経由しない攻撃的/レガシーデータ想定）。
                preferenceRepository.save(NotificationPreferenceEntity.builder()
                        .userId(EXISTING_ROW_USER).scopeType("TEAM").scopeId(teamId).isEnabled(true).build());

                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("TEAM", teamId, false)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.error.code").value("COMMON_002"));

                assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(
                                EXISTING_ROW_USER, "TEAM", teamId)
                        .orElseThrow().getIsEnabled())
                        .as("拒否された更新で既存行の isEnabled が書き換えられてはならない")
                        .isTrue();
            }
        }

        // ═════════════════════════════════════════════════════════════
        // 入力の型とIDの組合せ（Bean Validation・400 期待）
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("入力バリデーション（400期待・500にしない）")
        class InputValidation {

            @Test
            @WithMockUser(username = "918113")
            @DisplayName("AC-24 scopeType=nullは400")
            void AC24_scopeTypeNullは400() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody(null, 1L, true)))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @WithMockUser(username = "918113")
            @DisplayName("AC-25 列挙に無いscopeType文字列は400")
            void AC25_未知のscopeTypeは400() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("BOGUS", 1L, true)))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @WithMockUser(username = "918113")
            @DisplayName("AC-26 ID必須スコープ（TEAM）でscopeId=nullは400")
            void AC26_ID必須スコープでscopeIdNullは400() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("TEAM", null, true)))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @WithMockUser(username = "918113")
            @DisplayName("AC-27 SYSTEMでscopeIdが非nullは400")
            void AC27_systemでscopeId非nullは400() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(putBody("SYSTEM", 999L, true)))
                        .andExpect(status().isBadRequest());
            }

            @Test
            @WithMockUser(username = "918113")
            @DisplayName("AC-28 isEnabled=nullは400")
            void AC28_isEnabledNullは400() throws Exception {
                mockMvc.perform(put(PUT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"scopeType\":\"SYSTEM\",\"scopeId\":null,\"isEnabled\":null}"))
                        .andExpect(status().isBadRequest());
            }
        }

        // ═════════════════════════════════════════════════════════════
        // 読み取り側: 名前列挙オラクル封じ・N+1 撲滅
        // ═════════════════════════════════════════════════════════════

        @Nested
        @DisplayName("listPreferences の読み取り側（名前列挙オラクル・N+1）")
        class ReadSideLeakageAndN1 {

            @Test
            @WithMockUser(username = "918117")
            @DisplayName("AC-29 非所属スコープの行が残っていても実名を返さない")
            void AC29_非所属の実名を返さない() throws Exception {
                String teamName = "AC29 非公開チーム " + System.nanoTime();
                Long teamId = insertTeam(teamName);
                // 非所属チームの行を直接 INSERT する（LEAK_USER は teamId に所属していない）。
                preferenceRepository.save(NotificationPreferenceEntity.builder()
                        .userId(LEAK_USER).scopeType("TEAM").scopeId(teamId).isEnabled(true).build());

                MvcResult result = mockMvc.perform(get(GET_PATH))
                        .andExpect(status().isOk())
                        .andReturn();

                JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
                boolean leaked = false;
                for (JsonNode entry : data) {
                    JsonNode scopeName = entry.path("scopeName");
                    if (!scopeName.isMissingNode() && teamName.equals(scopeName.asText())) {
                        leaked = true;
                    }
                }
                assertThat(leaked)
                        .as("非所属チームの実名 '" + teamName + "' が scopeName に含まれてはならない")
                        .isFalse();
            }

            @Test
            @DisplayName("AC-30-small 1件のときのSQL本数を測定する")
            void AC30_small_SQL本数測定() throws Exception {
                preferenceRepository.save(NotificationPreferenceEntity.builder()
                        .userId(N1_SMALL_USER).scopeType("TEAM").scopeId(900001L).isEnabled(true).build());

                Statistics stats = statisticsCleared();
                mockMvc.perform(get(GET_PATH).with(user(String.valueOf(N1_SMALL_USER))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(1));
                long smallCount = stats.getPrepareStatementCount();

                // 大量データ側（AC30_large）と比較するため、静的フィールドへ退避せず
                // 直接比較する専用テストを別途用意する（下記 AC30_一覧のSQL本数は件数に比例しない）。
                assertThat(smallCount).isGreaterThan(0L);
            }

            @Test
            @DisplayName("AC-30 一覧のSQL本数は件数に比例しない（N+1の実証）")
            void AC30_一覧のSQL本数は件数に比例しない() throws Exception {
                // 【重要】@WithMockUser は TestSecurityContextHolder 由来の固定コンテキストを
                // MockMvc の各リクエストへ再適用するため、同一テスト内で
                // SecurityContextHolder を直接書き換えても後続リクエストの認証は切り替わらない
                // （最初の @WithMockUser の値へ戻ってしまう）。1テスト内で認証ユーザーを
                // 切り替える必要がある本テストは、リクエストごとに
                // SecurityMockMvcRequestPostProcessors.user(...) を明示指定する
                // （BillingInvoiceApiAuthorizationRedIT と同じ作法）。
                preferenceRepository.save(NotificationPreferenceEntity.builder()
                        .userId(N1_SMALL_USER).scopeType("TEAM").scopeId(900001L).isEnabled(true).build());

                Statistics stats = statisticsCleared();
                mockMvc.perform(get(GET_PATH).with(user(String.valueOf(N1_SMALL_USER))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(1));
                long smallCount = stats.getPrepareStatementCount();

                for (long i = 0; i < 20; i++) {
                    preferenceRepository.save(NotificationPreferenceEntity.builder()
                            .userId(N1_LARGE_USER).scopeType("TEAM").scopeId(900100L + i).isEnabled(true).build());
                }
                em.flush();

                stats = statisticsCleared();
                mockMvc.perform(get(GET_PATH).with(user(String.valueOf(N1_LARGE_USER))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(20));
                long largeCount = stats.getPrepareStatementCount();

                assertThat(largeCount)
                        .as("1件のとき %d 本・20件のとき %d 本。差があるなら scopeName 解決が N+1 である",
                                smallCount, largeCount)
                        .isEqualTo(smallCount);
            }
        }

        // ═════════════════════════════════════════════════════════════
        // ヘルパー
        // ═════════════════════════════════════════════════════════════

        /**
         * Hibernate Statistics を有効化してクリアした状態で返す（N+1 計測用）。
         * {@code ScheduleCommentPerformanceContractIT} と同一の計測方式。
         * test プロファイルは {@code generate_statistics} が既定で無効のため、
         * テスト内で明示的に {@code setStatisticsEnabled(true)} する。
         */
        private Statistics statisticsCleared() {
            SessionFactory sessionFactory = em.getEntityManagerFactory().unwrap(SessionFactory.class);
            Statistics stats = sessionFactory.getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();
            return stats;
        }

        private Long insertTeam(String name) {
            em.createNativeQuery(
                            "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                    + "created_at, updated_at) "
                                    + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                    + "CONCAT('notif-scp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                    .setParameter("name", name)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        }

        private Long insertOrganization(String name) {
            em.createNativeQuery(
                            "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                    + "supporter_enabled, version, slug, created_at, updated_at) "
                                    + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                    + "CONCAT('notif-scp-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                    .setParameter("name", name)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        }

        private void insertTeamOrgMembership(Long teamId, Long orgId) {
            em.createNativeQuery(
                            "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, "
                                    + "created_at) VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                    .setParameter("tid", teamId)
                    .setParameter("oid", orgId)
                    .executeUpdate();
        }

        private Long insertCommittee(Long orgId, String name) {
            CommitteeEntity committee = CommitteeEntity.builder()
                    .organizationId(orgId)
                    .name(name)
                    .status(CommitteeStatus.ACTIVE)
                    .createdBy(999_999_001L)
                    .build();
            return committeeRepository.save(committee).getId();
        }

        private void insertCommitteeMember(Long committeeId, Long userId, CommitteeRole role) {
            committeeMemberRepository.save(CommitteeMemberEntity.builder()
                    .committeeId(committeeId)
                    .userId(userId)
                    .role(role)
                    .joinedAt(LocalDateTime.now())
                    .build());
        }
    }
}
