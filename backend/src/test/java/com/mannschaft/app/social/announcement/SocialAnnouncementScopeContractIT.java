package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」第三陣・部隊C-social — social/announcement ドメイン認可契約テスト。
 *
 * <p><b>目的</b>: お知らせウィジェット（F02.6）とフレンドチーム（F01.5）の IDOR 面を持つ
 * 読取/書込 EP について、認可が<b>実 HTTP + 実 MySQL 経路で本当にゲートしているか</b>を固定する。</p>
 *
 * <p><b>本テストが炙り出した実穴（本 PR 第2コミットで根治）:</b></p>
 * <ul>
 *   <li><b>既読マーク</b>: Service が「お知らせ ID の存在確認」だけを行い、URL のスコープとの
 *       帰属照合もメンバーシップ照合も行っていなかった。Controller はパス変数のスコープ ID を
 *       Service に渡さず捨てていた。結果、認証済みでありさえすれば無関係なスコープの URL で
 *       他テナントのお知らせに既読行を作れた（書き込み副作用 + 実在オラクル）。</li>
 *   <li><b>一括既読</b>: スコープに対するメンバーシップ検証が皆無で、非メンバーが他テナントの
 *       スコープ配下の全お知らせに対し自分の既読行を一括生成できた（DB 汚染）。</li>
 * </ul>
 *
 * <p><b>期待ステータスの根拠（AC-S11）</b>:
 * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} には {@code ANNOUNCE_} で始まるコードが
 * <b>1 件も登録されていない</b>。未登録コードは {@code resolveHttpStatus} の既定
 * （{@code Severity.WARN → HTTP 400}）にフォールバックするため、{@code AnnouncementErrorCode} の
 * Javadoc が「(404)」「(403)」と書いていても<b>実際に返るのは 400</b> である。一方
 * {@code COMMON_002}（認可拒否）は 403 に、{@code SOCIAL_105} は 403 に、{@code SOCIAL_110} は
 * 404 に明示マップ済みなので、そちらは 403/404 が返る。本テストは<b>実挙動</b>に期待値を合わせる
 * （{@code ANNOUNCE_*} の 404/403 への統一は別課題 #2468）。</p>
 *
 * <p><b>実在オラクル封じ（AC-S7）</b>: 「越境した実在 ID」と「そもそも存在しない ID」が
 * 同一ステータス・<b>同一エラーコード</b>で返ることを固定する。片方だけ別応答だと ID の実在が漏れる。</p>
 *
 * <p>金型: {@code ReservationScopeContractIT} / {@code ChatChannelAccessScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("social/announcement ドメイン 認可契約テスト（裏目付C・スコープ帰属とメンバーシップの固定）")
class SocialAnnouncementScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しないお知らせ ID（実在オラクル封じの対照群）。 */
    private static final long MISSING_ANNOUNCEMENT_ID = 9_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    @Autowired
    private TeamFriendFolderRepository folderRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    /** teamA / orgA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamA / orgA の非管理者メンバー（既読は可・ピン留めは不可）。 */
    private Long memberAId;
    /** teamB / orgB の ADMIN（越境攻撃者。teamA・orgA の URL には遮断される）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;

    /** teamA のお知らせ（MEMBERS_AND_ABOVE）。 */
    private Long annAId;
    /** teamA のお知らせ（PUBLIC。非メンバーにも一覧で見える）。 */
    private Long annAPublicId;
    /** teamB のお知らせ（越境 announcementId として teamA の URL に差し込む主役）。 */
    private Long annBId;
    /** orgA のお知らせ。 */
    private Long orgAnnAId;
    /** orgB のお知らせ（越境 announcementId として orgA の URL に差し込む主役）。 */
    private Long orgAnnBId;

    /** teamA のフレンドフォルダ。 */
    private Long folderAId;
    /** teamB のフレンドフォルダ（越境 folderId として teamA の URL に差し込む主役）。 */
    private Long folderBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ANNAUTHZ チームA");
        teamBId = insertTeam("ANNAUTHZ チームB");
        orgAId = insertOrganization("ANNAUTHZ 組織A");
        orgBId = insertOrganization("ANNAUTHZ 組織B");

        adminAId = insertUser("annauthz-admin-a@example.com");
        memberAId = insertUser("annauthz-member-a@example.com");
        adminBId = insertUser("annauthz-admin-b@example.com");
        outsiderId = insertUser("annauthz-outsider@example.com");

        // isScopeAdmin / isAdminOrAbove（user_roles）と isMember（memberships）は別系統のため、
        // ADMIN 役にも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);

        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);

        // outsiderId はどこにも所属させない。

        annAId = saveFeed(AnnouncementScopeType.TEAM, teamAId, 1001L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE);
        annAPublicId = saveFeed(AnnouncementScopeType.TEAM, teamAId, 1002L,
                AnnouncementVisibility.PUBLIC);
        annBId = saveFeed(AnnouncementScopeType.TEAM, teamBId, 1003L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE);
        orgAnnAId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgAId, 1004L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE);
        orgAnnBId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgBId, 1005L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE);

        folderAId = folderRepository.save(TeamFriendFolderEntity.builder()
                .ownerTeamId(teamAId).name("ANNAUTHZ フォルダA").build()).getId();
        folderBId = folderRepository.save(TeamFriendFolderEntity.builder()
                .ownerTeamId(teamBId).name("ANNAUTHZ フォルダB").build()).getId();

        // フレンド系の「正当 200 / 越境 404」を踏むため、ADMIN ロールに MANAGE_FRIEND_TEAMS を付与する。
        // test プロファイルは Flyway 無効（schema は Entity 由来）で role_permissions が空のため、
        // 明示的に seed しないと ADMIN であっても権限判定が常に false になる。
        grantPermissionToRole("ADMIN", "MANAGE_FRIEND_TEAMS");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. POST /teams/{teamId}/announcements/{id}/read（既読マーク）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /teams/{teamId}/announcements/{id}/read（既読マーク）")
    class TeamMarkAsRead {

        /** AC-S1: 当該チームに無所属の認証済みユーザーの既読は遮断される（COMMON_002 → 403）。 */
        @Test
        @DisplayName("AC-S1 部外者の既読は403（メンバーシップ検証）")
        void ac_s1_部外者の既読は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatus(annAId, outsiderId)).isZero();
        }

        /** AC-S1: 別テナントの正当 ADMIN であっても当該チームには無所属なので遮断される。 */
        @Test
        @DisplayName("AC-S1 別テナントADMINの既読は403（メンバーシップ検証）")
        void ac_s1_別テナントADMINの既読は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatus(annAId, adminBId)).isZero();
        }

        /** AC-S2: 別テナントの announcementId を自チームの URL に差し込むと遮断される（ANNOUNCE_001 → 400）。 */
        @Test
        @DisplayName("AC-S2 越境announcementIdの既読は400（スコープ帰属検証）")
        void ac_s2_越境announcementIdの既読は400() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annBId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annBId, memberAId)).isZero();
        }

        /** AC-S3: 正当メンバーの既読は 200 のまま（非回帰）。 */
        @Test
        @DisplayName("AC-S3 正当メンバーの既読は200（非回帰）")
        void ac_s3_正当メンバーの既読は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAId, memberAId)).isEqualTo(1);
        }

        /** AC-S4: 既読は冪等（2 回叩いても 200・既読行は重複しない）。 */
        @Test
        @DisplayName("AC-S4 既読は冪等（2回叩いても200・行は重複しない）")
        void ac_s4_既読は冪等() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAId, memberAId)).isEqualTo(1);
        }

        /** AC-S7: 不在 ID と越境 ID が同一応答（実在オラクルを塞ぐ）。 */
        @Test
        @DisplayName("AC-S7 不在IDと越境IDは同一応答（実在オラクル封じ）")
        void ac_s7_不在IDと越境IDは同一応答() throws Exception {
            setAuth(memberAId);
            String missing = mockMvc.perform(
                            post("/api/v1/teams/{teamId}/announcements/{id}/read",
                                    teamAId, MISSING_ANNOUNCEMENT_ID))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            String crossTenant = mockMvc.perform(
                            post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annBId))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(errorCodeOf(crossTenant)).isEqualTo(errorCodeOf(missing));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/announcements/read-all（一括既読）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/announcements/read-all（一括既読）")
    class TeamMarkAllAsRead {

        /** AC-S5: 非メンバーの一括既読は遮断され、既読行が 1 件も作られない。 */
        @Test
        @DisplayName("AC-S5 部外者の一括既読は403かつDBに行が作られない")
        void ac_s5_部外者の一括既読は403かつ行が作られない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatusByUser(outsiderId)).isZero();
        }

        /** AC-S5: 別テナントの正当 ADMIN による一括既読も遮断され、DB が汚染されない。 */
        @Test
        @DisplayName("AC-S5 別テナントADMINの一括既読は403かつDBに行が作られない")
        void ac_s5_別テナントADMINの一括既読は403かつ行が作られない() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatusByUser(adminBId)).isZero();
        }

        /** AC-S3 / AC-S5: 正当メンバーの一括既読は 200 で、自スコープ分だけ既読行が作られる（非回帰）。 */
        @Test
        @DisplayName("AC-S3 正当メンバーの一括既読は200で自スコープ分のみ既読化（非回帰）")
        void ac_s3_正当メンバーの一括既読は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isOk());
            // teamA の 2 件だけが既読化され、teamB / orgB のお知らせには波及しない。
            assertThat(countReadStatus(annAId, memberAId)).isEqualTo(1);
            assertThat(countReadStatus(annAPublicId, memberAId)).isEqualTo(1);
            assertThat(countReadStatus(annBId, memberAId)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 組織スコープ版（AC-S6: Team 版と同一挙動）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. /organizations/{orgId}/announcements（組織スコープ版）")
    class OrgAnnouncements {

        /** AC-S6 + AC-S1: 組織に無所属の認証済みユーザーの既読は遮断される。 */
        @Test
        @DisplayName("AC-S6 部外者の組織既読は403")
        void ac_s6_部外者の組織既読は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatus(orgAnnAId, outsiderId)).isZero();
        }

        /** AC-S6 + AC-S2: 別組織の announcementId を自組織の URL に差し込むと遮断される。 */
        @Test
        @DisplayName("AC-S6 越境announcementIdの組織既読は400（スコープ帰属検証）")
        void ac_s6_越境announcementIdの組織既読は400() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnBId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(orgAnnBId, memberAId)).isZero();
        }

        /** AC-S6 + AC-S3 + AC-S4: 正当メンバーの組織既読は 200 かつ冪等（非回帰）。 */
        @Test
        @DisplayName("AC-S6 正当メンバーの組織既読は200かつ冪等（非回帰）")
        void ac_s6_正当メンバーの組織既読は200かつ冪等() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAId, memberAId)).isEqualTo(1);
        }

        /** AC-S6 + AC-S5: 非メンバーの組織一括既読は遮断され、DB に行が作られない。 */
        @Test
        @DisplayName("AC-S6 部外者の組織一括既読は403かつDBに行が作られない")
        void ac_s6_部外者の組織一括既読は403かつ行が作られない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/read-all", orgAId))
                    .andExpect(status().isForbidden());
            assertThat(countReadStatusByUser(outsiderId)).isZero();
        }

        /** AC-S6 + AC-S7: 組織版でも不在 ID と越境 ID が同一応答。 */
        @Test
        @DisplayName("AC-S6 組織版でも不在IDと越境IDは同一応答（実在オラクル封じ）")
        void ac_s6_組織版の実在オラクル封じ() throws Exception {
            setAuth(memberAId);
            String missing = mockMvc.perform(
                            post("/api/v1/organizations/{orgId}/announcements/{id}/read",
                                    orgAId, MISSING_ANNOUNCEMENT_ID))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            String crossTenant = mockMvc.perform(
                            post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnBId))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(errorCodeOf(crossTenant)).isEqualTo(errorCodeOf(missing));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 削除・ピン留め（AC-S8: entity 自身のスコープで再認可・挙動不変の回帰固定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE /{id} ・ PATCH /{id}/pin（削除・ピン留め）")
    class DeleteAndPin {

        /**
         * AC-S8: 正当 ADMIN が別チームの announcementId を自チーム URL に差し込んでも、
         * entity 自身のスコープで再認可されるため権限昇格しない（ANNOUNCE_002 → 400）。
         */
        @Test
        @DisplayName("AC-S8 越境announcementIdの削除は400（entityスコープで再認可・権限昇格なし）")
        void ac_s8_越境削除は400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/announcements/{id}", teamAId, annBId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
            assertThat(feedRepository.existsById(annBId)).isTrue();
        }

        /** AC-S8: 越境 announcementId のピン留めも entity スコープで再認可され遮断される。 */
        @Test
        @DisplayName("AC-S8 越境announcementIdのピン留めは400（entityスコープで再認可）")
        void ac_s8_越境ピン留めは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", teamAId, annBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pinned\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
        }

        /** AC-S8: 非管理者メンバーのピン留めは遮断される（ANNOUNCE_002 → 400）。 */
        @Test
        @DisplayName("AC-S8 非管理者メンバーのピン留めは400")
        void ac_s8_非管理者のピン留めは400() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", teamAId, annAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pinned\":true}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
        }

        /** AC-S8: 正当 ADMIN の自チームお知らせのピン留めは 200（非回帰）。 */
        @Test
        @DisplayName("AC-S8 正当ADMINのピン留めは200（非回帰）")
        void ac_s8_正当ADMINのピン留めは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", teamAId, annAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pinned\":true}"))
                    .andExpect(status().isOk());
        }

        /** AC-S8: 正当 ADMIN の自チームお知らせの削除は 204（非回帰）。 */
        @Test
        @DisplayName("AC-S8 正当ADMINの削除は204（非回帰）")
        void ac_s8_正当ADMINの削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/announcements/{id}", teamAId, annAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 一覧取得の可視性（AC-S9: 挙動不変・設計意図の明文化）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /teams/{teamId}/announcements（一覧の可視性）")
    class ListVisibility {

        /** AC-S9: 非メンバーの一覧取得は PUBLIC 可視のもののみ返る（内輪は露出しない）。 */
        @Test
        @DisplayName("AC-S9 部外者の一覧はPUBLIC可視のみ")
        void ac_s9_部外者の一覧はPUBLICのみ() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(annAPublicId));
        }

        /** AC-S9: 別テナント ADMIN も当該チームでは非メンバー扱いで PUBLIC のみ。 */
        @Test
        @DisplayName("AC-S9 別テナントADMINの一覧もPUBLIC可視のみ")
        void ac_s9_別テナントADMINの一覧もPUBLICのみ() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        /** AC-S9: 正当メンバーは MEMBERS_AND_ABOVE も含めて見える（非回帰）。 */
        @Test
        @DisplayName("AC-S9 正当メンバーの一覧は内輪も含む（非回帰）")
        void ac_s9_正当メンバーの一覧は内輪も含む() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        /** AC-S9: 部外者の一覧が 0 件になるケース（PUBLIC が存在しないスコープ）。 */
        @Test
        @DisplayName("AC-S9 PUBLICが無いスコープでは部外者の一覧は0件")
        void ac_s9_PUBLICなしなら部外者は0件() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamBId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. フレンド系（AC-S10: 正当200 / 越境404 / 権限不足403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. フレンドチーム・フレンドフォルダ・フレンドフィード")
    class FriendEndpoints {

        /** AC-S10: フレンド一覧は自チームメンバーのみ 200（非メンバーは COMMON_002 → 403）。 */
        @Test
        @DisplayName("AC-S10 GET /friends 正当メンバーは200")
        void ac_s10_フレンド一覧_正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/friends", teamAId))
                    .andExpect(status().isOk());
        }

        /** AC-S10: 部外者のフレンド一覧は 403。 */
        @Test
        @DisplayName("AC-S10 GET /friends 部外者は403")
        void ac_s10_フレンド一覧_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/friends", teamAId))
                    .andExpect(status().isForbidden());
        }

        /** AC-S10: 別テナント ADMIN の越境フレンド一覧は 403。 */
        @Test
        @DisplayName("AC-S10 GET /friends 別テナントADMINは403")
        void ac_s10_フレンド一覧_別テナントADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{id}/friends", teamAId))
                    .andExpect(status().isForbidden());
        }

        /** AC-S10: フレンドフォルダ一覧は自チームメンバーのみ 200。 */
        @Test
        @DisplayName("AC-S10 GET /friend-folders 正当メンバーは200・部外者は403")
        void ac_s10_フォルダ一覧() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/friend-folders", teamAId))
                    .andExpect(status().isOk());

            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/friend-folders", teamAId))
                    .andExpect(status().isForbidden());
        }

        /** AC-S10: フレンドフィードは MANAGE_FRIEND_TEAMS 権限が必要（SOCIAL_105 → 403）。 */
        @Test
        @DisplayName("AC-S10 GET /friend-feed 権限なしは403")
        void ac_s10_フレンドフィード_権限なしは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/friend-feed", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_105"));
        }

        /** AC-S10: 権限保持 ADMIN が別チームの folderId を差し込むと 404（SOCIAL_110・存在秘匿）。 */
        @Test
        @DisplayName("AC-S10 PUT /friend-folders/{folderId} 越境folderIdは404")
        void ac_s10_越境フォルダ更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{id}/friend-folders/{folderId}", teamAId, folderBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(folderBody("越境更新"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SOCIAL_110"));
        }

        /** AC-S10: 権限保持 ADMIN の自チームフォルダ更新は 200（非回帰）。 */
        @Test
        @DisplayName("AC-S10 PUT /friend-folders/{folderId} 正当ADMINは200")
        void ac_s10_正当フォルダ更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{id}/friend-folders/{folderId}", teamAId, folderAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(folderBody("正当更新"))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> folderBody(String name) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("description", "裏目付テスト");
            body.put("color", "#10B981");
            body.put("sortOrder", 0);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String errorCodeOf(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).path("error").path("code").asText();
    }

    private long countReadStatus(Long announcementId, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM announcement_read_status "
                                + "WHERE announcement_feed_id = :aid AND user_id = :uid")
                .setParameter("aid", announcementId)
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    private long countReadStatusByUser(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM announcement_read_status WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    private Long saveFeed(AnnouncementScopeType scopeType, Long scopeId, Long sourceId, String visibility) {
        return feedRepository.save(AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(sourceId)
                .titleCache("ANNAUTHZ お知らせ " + sourceId)
                .visibility(visibility)
                .build()).getId();
    }

    /**
     * roles.name のロールに permissions.name の権限を紐づける（role_permissions を 1 行 seed する）。
     *
     * <p>test プロファイルは Flyway 無効・schema は Entity 由来のため、
     * {@code V2.016__seed_role_permissions.sql} 相当の seed が存在しない。
     * 権限ベースのゲート（{@code AccessControlService#checkPermission}）を通す正当系を書くには
     * ここで明示的に seed する必要がある。</p>
     */
    private void grantPermissionToRole(String roleName, String permissionName) {
        Long roleId = resolveOrInsertRoleId(roleName);
        Long permissionId = resolveOrInsertPermissionId(permissionName);
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "VALUES (:rid, :pid, 1, NOW())")
                .setParameter("rid", roleId)
                .setParameter("pid", permissionId)
                .executeUpdate();
    }

    private Long resolveOrInsertRoleId(String roleName) {
        try {
            return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, 99, 0, NOW(), NOW())")
                    .setParameter("name", roleName)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult()).longValue();
        }
    }

    private Long resolveOrInsertPermissionId(String permissionName) {
        try {
            return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                    .setParameter("name", permissionName)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                    + "VALUES (:name, :name, 'TEAM', NOW(), NOW())")
                    .setParameter("name", permissionName)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                    .setParameter("name", permissionName)
                    .getSingleResult()).longValue();
        }
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'ANNAUTHZ', 'テスト', 'ANNAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('ann-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('anno-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
