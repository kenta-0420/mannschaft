package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.OwnershipTransferOfferEntity;
import com.mannschaft.app.role.repository.OwnershipTransferOfferRepository;
import com.mannschaft.app.role.service.OwnershipTransferOfferService;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2 オーナー委譲 承諾型オファー — 認可・状態遷移・永続の契約テスト。
 *
 * <p>正本: 戦役台帳 {@code .claude/campaigns/2026-07-18-owner-transfer-chat-invite.md}。
 * 金型: {@code MemberScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL Testcontainers + 手動 SecurityContext）。細粒度認可は Service 層で行うため、
 * MockMvc の method security（{@code @PreAuthorize("isAuthenticated()")}）は認証済みなら通過し、
 * IDOR/権限/状態不整合は Service が返す HTTP status で検証する。</p>
 *
 * <p>設計書: docs/features/F01.2_org_team_member_role/03_business_logic.md
 * 「オーナー委譲 承諾フロー（2ステップ・承諾型）」。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2 オーナー委譲 承諾型オファー 認可・状態遷移契約テスト")
class OwnershipTransferOfferScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_DECLINED = "DECLINED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OwnershipTransferOfferRepository offerRepository;

    @Autowired
    private OwnershipTransferOfferService offerService;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private String teamASlug;
    private Long orgAId;
    private String orgASlug;

    private Long adminAId;   // teamA/orgA の ADMIN（発行者）
    private Long targetId;   // teamA/orgA の MEMBER（指名相手・2FA 設定済）
    private Long noTwoFaId;  // teamA の MEMBER（2FA 未設定）
    private Long strangerId; // teamA の MEMBER だが offer の宛先ではない（第三者）

    @BeforeEach
    void setUp() {
        teamASlug = "otoauthz-team-" + Long.toHexString(System.nanoTime());
        teamAId = insertTeam("OTOAUTHZ チームA", teamASlug);
        orgASlug = "otoauthz-org-" + Long.toHexString(System.nanoTime());
        orgAId = insertOrganization("OTOAUTHZ 組織A", orgASlug);

        adminAId = insertUser("otoauthz-admin@example.com");
        targetId = insertUser("otoauthz-target@example.com");
        noTwoFaId = insertUser("otoauthz-no2fa@example.com");
        strangerId = insertUser("otoauthz-stranger@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため双方に張る（Wave 踏襲）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, targetId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, noTwoFaId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, strangerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // 組織側（org 打診 parity 用）
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, targetId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // 委譲承諾には承諾者の 2FA 設定が必須（設計書 §承諾フロー step3・§H-3）。
        // 打診（createOffer）時にも対象の 2FA を確認するため、正常系で使う target は 2FA 済にする。
        insertTwoFactorAuth(targetId);
        // noTwoFaId は 2FA を張らない（accept で 422 を期待）。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 打診（createOffer）: PENDING 1 件作成・重複 409・非 ADMIN 403
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 打診 POST /transfer-ownership-offers")
    class CreateOffer {

        @Test
        @DisplayName("ADMIN の打診で PENDING が 1 件作られる（201）")
        void ADMIN打診でPENDING1件作成() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();
            List<OwnershipTransferOfferEntity> pending =
                    offerRepository.findByTeamIdAndStatus(teamAId, STATUS_PENDING);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getTargetUserId()).isEqualTo(targetId);
            assertThat(pending.get(0).getIssuedBy()).isEqualTo(adminAId);
        }

        @Test
        @DisplayName("同一スコープへの重複打診は 409")
        void 重複打診は409() throws Exception {
            seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("ADMIN 以外の打診は 403（public 入口＝認可）")
        void 非ADMIN打診は403() throws Exception {
            setAuth(strangerId); // teamA の MEMBER（ADMIN でない）
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織スコープでも ADMIN の打診で PENDING が作られる（org parity・201）")
        void 組織スコープでも打診できる() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/transfer-ownership-offers", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();
            List<OwnershipTransferOfferEntity> pending =
                    offerRepository.findByOrganizationIdAndStatus(orgAId, STATUS_PENDING);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getTargetUserId()).isEqualTo(targetId);
        }

        @Test
        @DisplayName("期限切れの PENDING が残っていても新しい打診を作成できる")
        void expiredPendingDoesNotBlockNewOffer() throws Exception {
            seedPendingTeamOffer(adminAId, targetId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            setAuth(adminAId);

            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isCreated());

            assertThat(offerRepository.findByTeamIdAndStatus(teamAId, STATUS_PENDING)).hasSize(2);
        }

        @Test
        @DisplayName("アーカイブ済みチームでは打診を作成できない")
        void archivedTeamRejectsNewOffer() throws Exception {
            em.createNativeQuery("UPDATE teams SET archived_at = NOW() WHERE id = :id")
                    .setParameter("id", teamAId)
                    .executeUpdate();
            em.flush();
            em.clear();
            setAuth(adminAId);

            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(targetId))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("TEAM_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 承諾（acceptOffer）: 宛先照合 IDOR・昇降格・2FA
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 承諾 POST /transfer-ownership-offers/{offerId}/accept")
    class AcceptOffer {

        @Test
        @DisplayName("指名相手だけが承諾できる — 第三者の承諾は 403（宛先照合 IDOR・ROLE_009）")
        void 第三者の承諾は403() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(strangerId); // 宛先 target ではない第三者
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept",
                            teamASlug, offerId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ROLE_009"));

            // 権限は不変（昇降格が起きていないこと）
            assertRoleName(adminAId, teamAId, "ADMIN");
            assertRoleName(targetId, teamAId, "MEMBER");
        }

        @Test
        @DisplayName("指名相手の承諾で対象 ADMIN 昇格＋発行者 MEMBER 降格（永続後に再読込で検証・200）")
        void 承諾で昇格降格が永続する() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept",
                            teamASlug, offerId))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertRoleName(targetId, teamAId, "ADMIN");   // 承諾者が ADMIN へ昇格
            assertRoleName(adminAId, teamAId, "MEMBER");  // 発行者が MEMBER へ降格
            assertThat(offerRepository.findById(offerId).orElseThrow().getStatus())
                    .isEqualTo(STATUS_ACCEPTED);
        }

        @Test
        @DisplayName("承諾実行者が 2FA 未設定なら 422（権限不変）")
        void 承諾者2FA未設定は422() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, noTwoFaId, futureExpiry());
            setAuth(noTwoFaId); // 2FA 未設定
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept",
                            teamASlug, offerId))
                    .andExpect(status().isUnprocessableEntity());

            assertRoleName(adminAId, teamAId, "ADMIN");
            assertRoleName(noTwoFaId, teamAId, "MEMBER");
        }

        @Test
        @DisplayName("期限切れオファーの承諾は権限不変（EXPIRED・4xx）")
        void 期限切れ承諾は権限不変() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept",
                            teamASlug, offerId))
                    .andExpect(status().is4xxClientError());

            em.flush();
            em.clear();
            assertRoleName(adminAId, teamAId, "ADMIN");
            assertRoleName(targetId, teamAId, "MEMBER");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 辞退（declineOffer）・取消（cancelOffer）: 権限不変
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 辞退 / 取消（権限不変）")
    class DeclineAndCancel {

        @Test
        @DisplayName("宛先本人の辞退で権限は不変・status=DECLINED（200）")
        void 辞退で権限不変() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/decline",
                            teamASlug, offerId))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertRoleName(adminAId, teamAId, "ADMIN");
            assertRoleName(targetId, teamAId, "MEMBER");
            assertThat(offerRepository.findById(offerId).orElseThrow().getStatus())
                    .isEqualTo(STATUS_DECLINED);
        }

        @Test
        @DisplayName("発行者の取消で権限は不変・status=CANCELLED（204）")
        void 取消で権限不変() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}",
                            teamASlug, offerId))
                    .andExpect(status().isNoContent());

            em.flush();
            em.clear();
            assertRoleName(adminAId, teamAId, "ADMIN");
            assertRoleName(targetId, teamAId, "MEMBER");
            assertThat(offerRepository.findById(offerId).orElseThrow().getStatus())
                    .isEqualTo(STATUS_CANCELLED);
        }

        @Test
        @DisplayName("期限切れの打診は辞退できない")
        void expiredOfferCannotBeDeclined() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            setAuth(targetId);

            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/decline",
                            teamASlug, offerId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ROLE_012"));
        }

        @Test
        @DisplayName("期限切れの打診は取消できない")
        void expiredOfferCannotBeCancelled() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            setAuth(adminAId);

            mockMvc.perform(delete("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}",
                            teamASlug, offerId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ROLE_012"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 強制委譲（forceTransferForPurge）: 承諾スキップの即時委譲（Service 直呼び）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. forceTransferForPurge（承諾スキップ即時委譲）")
    class ForceTransfer {

        @Test
        @DisplayName("承諾を介さず即時に対象 ADMIN 昇格＋承継元 MEMBER 降格（Service 直呼び）")
        void 強制委譲で即時に昇降格する() {
            // 退会 purge 経由の最後の ADMIN 承継: オファーを作らず同期即時で委譲する（設計書 H-2）。
            offerService.forceTransferForPurge(teamAId, "TEAM", adminAId, targetId);

            em.flush();
            em.clear();
            assertRoleName(targetId, teamAId, "ADMIN");   // 承継先が即時 ADMIN
            assertRoleName(adminAId, teamAId, "MEMBER");  // 承継元（退会者）が MEMBER 降格

            // オファー経由でない（PENDING/ACCEPTED オファーが作られていないこと）
            assertThat(offerRepository.findByTeamIdAndStatus(teamAId, STATUS_PENDING)).isEmpty();
            assertThat(offerRepository.findByTeamIdAndStatus(teamAId, STATUS_ACCEPTED)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 受信インボックス（GET /me/ownership-transfer-offers）: 本人限定・IDOR 防止
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 受信インボックス GET /me/ownership-transfer-offers（本人限定）")
    class MyOffersInbox {

        @Test
        @DisplayName("宛先本人は自分宛の PENDING オファーを取得できる（200・件数1・offerId一致）")
        void 本人は自分宛オファーを取得できる() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(targetId);
            mockMvc.perform(get("/api/v1/me/ownership-transfer-offers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].offerId").value(offerId.toString()))
                    .andExpect(jsonPath("$.data[0].status").value(STATUS_PENDING))
                    .andExpect(jsonPath("$.data[0].issuedBy.userId").value(adminAId));
        }

        @Test
        @DisplayName("第三者は他人宛のオファーを取得できない（IDOR 防止・件数0）")
        void 第三者は他人宛オファーを取得できない() throws Exception {
            // target 宛のオファーを seed。stranger でログインしても見えないこと。
            seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(strangerId);
            mockMvc.perform(get("/api/v1/me/ownership-transfer-offers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("期限切れの打診は受信一覧に表示されない")
        void expiredOfferIsExcludedFromInbox() throws Exception {
            seedPendingTeamOffer(adminAId, targetId, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
            setAuth(targetId);

            mockMvc.perform(get("/api/v1/me/ownership-transfer-offers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("管理者はスコープの有効な打診を取得できる")
        void adminCanListPendingScopeOffer() throws Exception {
            UUID offerId = seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(adminAId);

            mockMvc.perform(get("/api/v1/teams/{slug}/transfer-ownership-offers/pending", teamASlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].offerId").value(offerId.toString()));
        }

        @Test
        @DisplayName("一般メンバーはスコープの打診一覧を取得できない")
        void memberCannotListPendingScopeOffer() throws Exception {
            seedPendingTeamOffer(adminAId, targetId, futureExpiry());
            setAuth(strangerId);

            mockMvc.perform(get("/api/v1/teams/{slug}/transfer-ownership-offers/pending", teamASlug))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> createBody(Long targetUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", targetUserId);
        return body;
    }

    private OffsetDateTime futureExpiry() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
    }

    /** PENDING の team オファーを直接 seed し、採番された offerId を返す。 */
    private UUID seedPendingTeamOffer(Long issuedBy, Long target, OffsetDateTime expiresAt) {
        OwnershipTransferOfferEntity offer = offerRepository.save(OwnershipTransferOfferEntity.builder()
                .teamId(teamAId)
                .issuedBy(issuedBy)
                .targetUserId(target)
                .status(STATUS_PENDING)
                .expiresAt(expiresAt)
                .build());
        em.flush();
        em.clear();
        return offer.getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** user_roles×roles を JOIN してスコープ内のロール名を検証する。 */
    private void assertRoleName(Long userId, Long teamId, String expectedRole) {
        String actual;
        try {
            actual = (String) em.createNativeQuery(
                            "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                    + "WHERE ur.user_id = :uid AND ur.team_id = :tid")
                    .setParameter("uid", userId)
                    .setParameter("tid", teamId)
                    .getSingleResult();
        } catch (NoResultException e) {
            actual = null;
        }
        assertThat(actual)
                .as("user=%d の team=%d でのロール", userId, teamId)
                .isEqualTo(expectedRole);
    }

    private void insertTwoFactorAuth(Long userId) {
        em.createNativeQuery(
                        "INSERT INTO two_factor_auth ("
                                + "user_id, totp_secret, backup_codes, is_enabled, verified_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'OTOAUTHZTESTSECRET', '[]', 1, NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .executeUpdate();
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
                                + "VALUES (:email, 'OTOAUTHZ', 'テスト', 'OTOAUTHZ テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
