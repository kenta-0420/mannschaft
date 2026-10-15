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

import java.time.LocalDateTime;
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
 * 認可番人「裏目付」第二陣・部隊C-social — social/announcement ドメイン認可契約テスト。
 *
 * <p><b>目的</b>: お知らせウィジェット（F02.6）とフレンドチーム（F01.5）の IDOR 面を持つ
 * 読取/書込 EP について、認可が<b>実 HTTP + 実 MySQL 経路で本当にゲートしているか</b>を固定する。</p>
 *
 * <p><b>既読系の規則（マスター御裁可 2026-07-28）</b>:
 * <b>「自分に見えているお知らせなら既読にしてよい」</b>。一覧に出る集合と既読にできる集合を
 * 一致させる。判定は一覧側と同一の正準経路（{@code RoleResolver#resolveViewerRole} →
 * {@link AnnouncementVisibility#allowedFor}）を流用する。</p>
 *
 * <p><b>本テストが固定している保証</b>（いずれも回帰ガードとして常時検証する）:</p>
 * <ul>
 *   <li><b>単件既読の帰属</b>（AC-S2）: 対象お知らせが URL のスコープに帰属することを要求する。
 *       帰属しない ID では既読行を作らず {@code ANNOUNCE_001} を返す
 *       （ID の存在確認だけでは不十分であり、帰属照合を必須とする）。</li>
 *   <li><b>一括既読の対象集合</b>（AC-S5）: 一括既読はスコープ内の<b>可視な</b>お知らせだけを
 *       対象とする。可視でないお知らせには既読行を 1 件も作らない。</li>
 *   <li><b>応援者の可視集合</b>（AC-S5b）: 既読可能集合は {@link AnnouncementVisibility} の判定に
 *       従う。一覧に出ない {@code MEMBERS_AND_ABOVE} は既読化できない（既読済み扱いになって
 *       後日 MEMBER 昇格後の未読バッジから漏れることも同時に防ぐ）。</li>
 *   <li><b>削除済み・期限切れ</b>（AC-S1c）: 一覧に出ないお知らせは単件既読化もできない。</li>
 * </ul>
 *
 * <p><b>期待ステータスの根拠（AC-S11・#2468 で是正済み）</b>:
 * かつて {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に {@code ANNOUNCE_} で始まる
 * コードが 1 件も登録されておらず、未登録コードは {@code resolveHttpStatus} の既定
 * （{@code Severity.WARN → HTTP 400}）にフォールバックしていた。そのため
 * {@code AnnouncementErrorCode} の Javadoc が「(404)」「(403)」と宣言していても実際に返るのは
 * 400 で、宣言と実挙動が乖離していた。#2468 で {@code ANNOUNCE_001}=404 /
 * {@code ANNOUNCE_002}=403 等を明示登録し、宣言どおりのステータスが返るようになったため、
 * 本テストの期待値も 404/403 に揃えている。以後の登録漏れは番人テスト
 * {@code ErrorCodeHttpStatusDeclarationGuardTest} が機械的に検出する。
 * 兄弟の {@code SOCIAL_105}=403 / {@code SOCIAL_110}=404 は従来から登録済み。</p>
 *
 * <p><b>実在オラクル封じ（AC-S7）</b>: 「越境した実在 ID」「そもそも存在しない ID」
 * 「自分には可視でない ID」の 3 つが同一ステータス・<b>同一エラーコード</b>で返ることを固定する。
 * どれか 1 つでも別応答だと ID の実在・可視性が漏れる。</p>
 *
 * <p>金型: {@code ReservationScopeContractIT} / {@code ChatChannelAccessScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("social/announcement ドメイン 認可契約テスト（裏目付C・見える＝既読にできる の固定）")
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
    /** orgA の配下チーム（team_org_memberships で ACTIVE 連結）。 */
    private Long childTeamAId;
    private Long orgAId;
    private Long orgBId;

    /** teamA / orgA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamA / orgA の非管理者メンバー。内輪限定も見えるし既読にもできる。 */
    private Long memberAId;
    /** teamA の応援者（SUPPORTER）。内輪限定は見えず既読にもできない（AC-S5b）。 */
    private Long supporterAId;
    /** orgA 直属ではなく配下チーム childTeamA のみに所属する者（AC-S6 の配下ケース）。 */
    private Long childMemberAId;
    /** teamB / orgB の ADMIN（越境攻撃者）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;

    /** teamA のお知らせ（MEMBERS_AND_ABOVE = 内輪限定）。 */
    private Long annAId;
    /** teamA のお知らせ（PUBLIC。非メンバーにも一覧で見える）。 */
    private Long annAPublicId;
    /** teamA の内輪限定お知らせだが元コンテンツ削除済み（一覧に出ない）。 */
    private Long annADeletedId;
    /** teamA の内輪限定お知らせだが期限切れ（一覧に出ない）。 */
    private Long annAExpiredId;
    /** teamB のお知らせ（越境 announcementId として teamA の URL に差し込む主役）。 */
    private Long annBId;
    /** orgA のお知らせ（MEMBERS_AND_ABOVE）。 */
    private Long orgAnnAId;
    /** orgA のお知らせ（PUBLIC）。 */
    private Long orgAnnAPublicId;
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
        childTeamAId = insertTeam("ANNAUTHZ 配下チームA");
        orgAId = insertOrganization("ANNAUTHZ 組織A");
        orgBId = insertOrganization("ANNAUTHZ 組織B");
        // childTeamA を orgA の配下として連結する（配下チームのみ所属者の裏取り用）。
        insertTeamOrgMembership(childTeamAId, orgAId);

        adminAId = insertUser("annauthz-admin-a@example.com");
        memberAId = insertUser("annauthz-member-a@example.com");
        supporterAId = insertUser("annauthz-supporter-a@example.com");
        childMemberAId = insertUser("annauthz-child-member-a@example.com");
        adminBId = insertUser("annauthz-admin-b@example.com");
        outsiderId = insertUser("annauthz-outsider@example.com");

        // isAdminOrAbove（user_roles）と isMember（memberships）は別系統のため、
        // ADMIN 役にも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);

        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // 応援者（role_kind = SUPPORTER）。isMember は role_kind を見ないため在籍判定なら通ってしまうが、
        // 可視性ゲートでは MEMBERS_AND_ABOVE を見られない。
        MembershipTestHelper.insertMembership(em, supporterAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);

        // 配下チームのみ所属者（orgA には直属しない）。
        MembershipTestHelper.insertMembership(em, childMemberAId, ScopeType.TEAM, childTeamAId, RoleKind.MEMBER);

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
        orgAnnAPublicId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgAId, 1006L,
                AnnouncementVisibility.PUBLIC);

        // 一覧に出ない 2 種（削除済み・期限切れ）。既読化もできないことを固定する（AC-S1c）。
        annADeletedId = saveFeed(AnnouncementScopeType.TEAM, teamAId, 1007L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE, LocalDateTime.now().minusDays(1), null);
        annAExpiredId = saveFeed(AnnouncementScopeType.TEAM, teamAId, 1008L,
                AnnouncementVisibility.MEMBERS_AND_ABOVE, null, LocalDateTime.now().minusHours(1));

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

        /** AC-S1（改訂）: 非メンバーが<b>自分に可視でない</b>内輪限定を既読化しようとすると遮断される。 */
        @Test
        @DisplayName("AC-S1 部外者が内輪限定を既読化しようとすると遮断（可視性ゲート）")
        void ac_s1_部外者の内輪限定既読は遮断() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annAId, outsiderId)).isZero();
        }

        /** AC-S1（改訂）: 別テナントの正当 ADMIN も teamA では非メンバー扱いで内輪限定は見えない。 */
        @Test
        @DisplayName("AC-S1 別テナントADMINが内輪限定を既読化しようとすると遮断")
        void ac_s1_別テナントADMINの内輪限定既読は遮断() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annAId, adminBId)).isZero();
        }

        /**
         * AC-S1b（新設）: 非メンバーでも PUBLIC のお知らせは既読にできる（200）。
         *
         * <p>一覧は非メンバーにも PUBLIC を返す（AC-S9）。既読をメンバー必須にすると
         * 「見えているのにクリックしても何も起きない」機能退行になるため、その回帰ガードである。</p>
         */
        @Test
        @DisplayName("AC-S1b 部外者でもPUBLICのお知らせは既読にできる（退行ガード）")
        void ac_s1b_部外者でもPUBLICは既読可() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAPublicId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAPublicId, outsiderId)).isEqualTo(1);
        }

        /** AC-S1c: 一覧に出ない「元コンテンツ削除済み」は正当メンバーでも既読化できない。 */
        @Test
        @DisplayName("AC-S1c 削除済みお知らせは正当メンバーでも既読化できない")
        void ac_s1c_削除済みは既読化できない() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annADeletedId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annADeletedId, memberAId)).isZero();
        }

        /** AC-S1c: 一覧に出ない「期限切れ」は正当メンバーでも既読化できない。 */
        @Test
        @DisplayName("AC-S1c 期限切れお知らせは正当メンバーでも既読化できない")
        void ac_s1c_期限切れは既読化できない() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAExpiredId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annAExpiredId, memberAId)).isZero();
        }

        /** AC-S2: 別テナントの announcementId を自チームの URL に差し込むと遮断される。 */
        @Test
        @DisplayName("AC-S2 越境announcementIdの既読は遮断（スコープ帰属検証）")
        void ac_s2_越境announcementIdの既読は遮断() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annBId))
                    .andExpect(status().isNotFound())
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

        /**
         * AC-S5b（新設）: 応援者は内輪限定（MEMBERS_AND_ABOVE）を既読化できない。
         *
         * <p>本 PR 以前からの残穴の回帰ガード。応援者には一覧に出ないお知らせであり、
         * 既読化できると (1) 応答差分から内輪お知らせ ID の実在が判別でき、
         * (2) 後日 MEMBER に昇格した際に既読済み扱いで未読バッジに出ない。</p>
         */
        @Test
        @DisplayName("AC-S5b 応援者は内輪限定を既読化できない（残穴ガード）")
        void ac_s5b_応援者は内輪限定を既読化できない() throws Exception {
            setAuth(supporterAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(annAId, supporterAId)).isZero();
        }

        /** AC-S5b: 応援者にも見える PUBLIC は既読にできる（過剰遮断していないことの裏取り）。 */
        @Test
        @DisplayName("AC-S5b 応援者でも可視なPUBLICは既読にできる")
        void ac_s5b_応援者でもPUBLICは既読可() throws Exception {
            setAuth(supporterAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamAId, annAPublicId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAPublicId, supporterAId)).isEqualTo(1);
        }

        /** AC-S7: 不在 ID・越境 ID・不可視 ID がすべて同一応答（実在オラクルを塞ぐ）。 */
        @Test
        @DisplayName("AC-S7 不在ID・越境ID・不可視IDは同一応答（実在オラクル封じ）")
        void ac_s7_不在越境不可視は同一応答() throws Exception {
            setAuth(supporterAId);
            String missing = readAndReturnBody(teamAId, MISSING_ANNOUNCEMENT_ID);
            String crossTenant = readAndReturnBody(teamAId, annBId);
            String invisible = readAndReturnBody(teamAId, annAId);

            assertThat(errorCodeOf(crossTenant)).isEqualTo(errorCodeOf(missing));
            assertThat(errorCodeOf(invisible)).isEqualTo(errorCodeOf(missing));
        }

        /** 既読 EP を叩き 404（ANNOUNCE_001・存在秘匿）であることを確認したうえで本文を返す。 */
        private String readAndReturnBody(Long teamId, Long announcementId) throws Exception {
            return mockMvc.perform(
                            post("/api/v1/teams/{teamId}/announcements/{id}/read", teamId, announcementId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/announcements/read-all（一括既読）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/announcements/read-all（一括既読）")
    class TeamMarkAllAsRead {

        /**
         * AC-S5（改訂）: 非メンバーの一括既読は、可視なもの（PUBLIC）だけが既読化され、
         * 不可視のもの（内輪限定）には既読行が 1 件も作られない。
         */
        @Test
        @DisplayName("AC-S5 部外者の一括既読はPUBLICのみ既読化され内輪限定には行が作られない")
        void ac_s5_部外者の一括既読は可視分のみ() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAPublicId, outsiderId)).isEqualTo(1);
            assertThat(countReadStatus(annAId, outsiderId)).isZero();
            // 一覧に出ない（削除済み・期限切れ）ものにも行は作られない
            assertThat(countReadStatus(annADeletedId, outsiderId)).isZero();
            assertThat(countReadStatus(annAExpiredId, outsiderId)).isZero();
            // 他テナントへは一切波及しない
            assertThat(countReadStatus(annBId, outsiderId)).isZero();
            assertThat(countReadStatusByUser(outsiderId)).isEqualTo(1);
        }

        /** AC-S5（改訂）: 別テナント ADMIN の一括既読でも内輪限定には行が作られない。 */
        @Test
        @DisplayName("AC-S5 別テナントADMINの一括既読でも内輪限定には行が作られない")
        void ac_s5_別テナントADMINの一括既読は内輪に及ばない() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAId, adminBId)).isZero();
        }

        /** AC-S5b（新設）: 応援者の一括既読でも内輪限定には行が作られない（残穴ガード）。 */
        @Test
        @DisplayName("AC-S5b 応援者の一括既読でも内輪限定には行が作られない（残穴ガード）")
        void ac_s5b_応援者の一括既読は内輪に及ばない() throws Exception {
            setAuth(supporterAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAId, supporterAId)).isZero();
            // 応援者に可視な PUBLIC は既読化される（過剰遮断していない）
            assertThat(countReadStatus(annAPublicId, supporterAId)).isEqualTo(1);
        }

        /** AC-S3: 正当メンバーの一括既読は 200 で、可視な自スコープ分だけ既読化される（非回帰）。 */
        @Test
        @DisplayName("AC-S3 正当メンバーの一括既読は200で可視な自スコープ分のみ既読化（非回帰）")
        void ac_s3_正当メンバーの一括既読は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(annAId, memberAId)).isEqualTo(1);
            assertThat(countReadStatus(annAPublicId, memberAId)).isEqualTo(1);
            // 一覧に出ないもの・他テナントには波及しない
            assertThat(countReadStatus(annADeletedId, memberAId)).isZero();
            assertThat(countReadStatus(annAExpiredId, memberAId)).isZero();
            assertThat(countReadStatus(annBId, memberAId)).isZero();
            assertThat(countReadStatusByUser(memberAId)).isEqualTo(2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 組織スコープ版（AC-S6: Team 版と同一挙動）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. /organizations/{orgId}/announcements（組織スコープ版）")
    class OrgAnnouncements {

        /** AC-S6 + AC-S1: 組織に無所属の認証済みユーザーは内輪限定を既読化できない。 */
        @Test
        @DisplayName("AC-S6 部外者の組織内輪限定既読は遮断")
        void ac_s6_部外者の組織既読は遮断() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(orgAnnAId, outsiderId)).isZero();
        }

        /** AC-S6 + AC-S1: 別テナント ADMIN による組織単件既読も遮断される（検分指摘の欠落ケース）。 */
        @Test
        @DisplayName("AC-S6 別テナントADMINの組織単件既読は遮断")
        void ac_s6_別テナントADMINの組織既読は遮断() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(orgAnnAId, adminBId)).isZero();
        }

        /** AC-S6 + AC-S1b: 組織非メンバーでも PUBLIC の組織お知らせは既読にできる。 */
        @Test
        @DisplayName("AC-S6 部外者でも組織のPUBLICお知らせは既読にできる（退行ガード）")
        void ac_s6_部外者でも組織PUBLICは既読可() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read",
                            orgAId, orgAnnAPublicId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAPublicId, outsiderId)).isEqualTo(1);
        }

        /**
         * AC-S6: 配下チームのみ所属者は、組織の可視なお知らせ（PUBLIC）を既読化できる。
         *
         * <p>組織告知は配下チームへ配信されるため、配下所属者が「見えているのに既読にできない」
         * 状態を作らないことの回帰ガード。可視でない内輪限定については下のケースで遮断を固定する。</p>
         */
        @Test
        @DisplayName("AC-S6 配下チームのみ所属者は組織の可視なお知らせを既読にできる（退行ガード）")
        void ac_s6_配下チーム所属者は可視な組織お知らせを既読可() throws Exception {
            setAuth(childMemberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read",
                            orgAId, orgAnnAPublicId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAPublicId, childMemberAId)).isEqualTo(1);
        }

        /** AC-S6: 配下チームのみ所属者に組織の内輪限定は一覧に出ないため既読化もできない。 */
        @Test
        @DisplayName("AC-S6 配下チームのみ所属者は組織の内輪限定を既読化できない")
        void ac_s6_配下チーム所属者は組織内輪限定を既読化できない() throws Exception {
            setAuth(childMemberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
            assertThat(countReadStatus(orgAnnAId, childMemberAId)).isZero();
        }

        /** AC-S6 + AC-S2: 別組織の announcementId を自組織の URL に差し込むと遮断される。 */
        @Test
        @DisplayName("AC-S6 越境announcementIdの組織既読は遮断（スコープ帰属検証）")
        void ac_s6_越境announcementIdの組織既読は遮断() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgAId, orgAnnBId))
                    .andExpect(status().isNotFound())
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

        /** AC-S6 + AC-S5: 非メンバーの組織一括既読では内輪限定に行が作られない。 */
        @Test
        @DisplayName("AC-S6 部外者の組織一括既読は内輪限定に行を作らない")
        void ac_s6_部外者の組織一括既読は可視分のみ() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/read-all", orgAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAId, outsiderId)).isZero();
            assertThat(countReadStatus(orgAnnAPublicId, outsiderId)).isEqualTo(1);
        }

        /**
         * AC-S6 + AC-S5: 別テナント ADMIN の組織一括既読でも内輪限定に行が作られない
         * （検分指摘の欠落ケース）。
         */
        @Test
        @DisplayName("AC-S6 別テナントADMINの組織一括既読は内輪限定に行を作らない")
        void ac_s6_別テナントADMINの組織一括既読は内輪に及ばない() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/read-all", orgAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAId, adminBId)).isZero();
        }

        /** AC-S6 + AC-S3: 正当メンバーの組織一括既読は 200 で DB 行数が正しい（検分指摘の欠落ケース）。 */
        @Test
        @DisplayName("AC-S6 正当メンバーの組織一括既読は200でDB行数が正しい（非回帰）")
        void ac_s6_正当メンバーの組織一括既読は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/announcements/read-all", orgAId))
                    .andExpect(status().isOk());
            assertThat(countReadStatus(orgAnnAId, memberAId)).isEqualTo(1);
            assertThat(countReadStatus(orgAnnAPublicId, memberAId)).isEqualTo(1);
            assertThat(countReadStatus(orgAnnBId, memberAId)).isZero();
            assertThat(countReadStatusByUser(memberAId)).isEqualTo(2);
        }

        /** AC-S6 + AC-S7: 組織版でも不在 ID・越境 ID・不可視 ID が同一応答。 */
        @Test
        @DisplayName("AC-S6 組織版でも不在ID・越境ID・不可視IDは同一応答（実在オラクル封じ）")
        void ac_s6_組織版の実在オラクル封じ() throws Exception {
            setAuth(childMemberAId);
            String missing = readAndReturnBody(orgAId, MISSING_ANNOUNCEMENT_ID);
            String crossTenant = readAndReturnBody(orgAId, orgAnnBId);
            String invisible = readAndReturnBody(orgAId, orgAnnAId);

            assertThat(errorCodeOf(crossTenant)).isEqualTo(errorCodeOf(missing));
            assertThat(errorCodeOf(invisible)).isEqualTo(errorCodeOf(missing));
        }

        private String readAndReturnBody(Long orgId, Long announcementId) throws Exception {
            return mockMvc.perform(
                            post("/api/v1/organizations/{orgId}/announcements/{id}/read", orgId, announcementId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
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
         * entity 自身のスコープで再認可されるため権限昇格しない（ANNOUNCE_002 → 403）。
         */
        @Test
        @DisplayName("AC-S8 越境announcementIdの削除は403（entityスコープで再認可・権限昇格なし）")
        void ac_s8_越境削除は403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/announcements/{id}", teamAId, annBId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
            assertThat(feedRepository.existsById(annBId)).isTrue();
        }

        /** AC-S8: 越境 announcementId のピン留めも entity スコープで再認可され遮断される。 */
        @Test
        @DisplayName("AC-S8 越境announcementIdのピン留めは403（entityスコープで再認可）")
        void ac_s8_越境ピン留めは403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", teamAId, annBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pinned\":true}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ANNOUNCE_002"));
        }

        /** AC-S8: 非管理者メンバーのピン留めは遮断される（ANNOUNCE_002 → 403）。 */
        @Test
        @DisplayName("AC-S8 非管理者メンバーのピン留めは403")
        void ac_s8_非管理者のピン留めは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/announcements/{id}/pin", teamAId, annAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pinned\":true}"))
                    .andExpect(status().isForbidden())
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

        /**
         * AC-S9: 応援者の一覧に内輪限定は出ない。
         *
         * <p>AC-S5b（応援者は内輪限定を既読化できない）と対になり、
         * 「一覧に出る集合＝既読にできる集合」であることを両側から固定する。</p>
         */
        @Test
        @DisplayName("AC-S9 応援者の一覧に内輪限定は出ない（既読可能集合と一致）")
        void ac_s9_応援者の一覧に内輪限定は出ない() throws Exception {
            setAuth(supporterAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(annAPublicId));
        }

        /** AC-S9: 正当メンバーは MEMBERS_AND_ABOVE も含めて見える（削除済み・期限切れは除く。非回帰）。 */
        @Test
        @DisplayName("AC-S9 正当メンバーの一覧は内輪も含む（削除済み・期限切れは除く／非回帰）")
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
        return saveFeed(scopeType, scopeId, sourceId, visibility, null, null);
    }

    /**
     * お知らせフィードを 1 件保存する。
     *
     * @param sourceDeletedAt 元コンテンツ削除日時（null = 未削除）。非 null なら一覧に出ない
     * @param expiresAt       表示終了日時（null = 期限なし）。過去日時なら一覧に出ない
     */
    private Long saveFeed(AnnouncementScopeType scopeType, Long scopeId, Long sourceId, String visibility,
                          LocalDateTime sourceDeletedAt, LocalDateTime expiresAt) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(sourceId)
                .titleCache("ANNAUTHZ お知らせ " + sourceId)
                .visibility(visibility)
                .sourceDeletedAt(sourceDeletedAt)
                .expiresAt(expiresAt)
                .build();
        return feedRepository.save(feed).getId();
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

    /** チームを組織の配下（ACTIVE）として連結する。 */
    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, "
                                + "invited_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }
}
