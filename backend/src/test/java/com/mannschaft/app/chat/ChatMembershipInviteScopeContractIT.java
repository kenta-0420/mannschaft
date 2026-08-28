package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.12 チャットからチーム/組織への承諾型招待 — 認可・権限昇格封鎖・永続の契約テスト。
 *
 * <p>正本: 戦役台帳 {@code .claude/campaigns/2026-07-18-owner-transfer-chat-invite.md}。
 * 金型: {@code MemberScopeContractIT}。細粒度認可（発行者 ADMIN/DEPUTY・宛先照合・特権ロール封鎖）は
 * Service 層で行うため MockMvc の method security は認証済みなら通過し、IDOR/権限昇格は Service が返す
 * HTTP status / エラーコードで検証する。</p>
 *
 * <p>設計書: docs/features/F04.12_chat_membership_invite.md §4・§6。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F04.12 チャット承諾型招待 認可・権限昇格封鎖契約テスト")
class ChatMembershipInviteScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatChannelRepository channelRepository;

    @Autowired
    private ChatChannelMemberRepository channelMemberRepository;

    @Autowired
    private InviteTokenRepository inviteTokenRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long orgAId;
    private Long archivedOrgId;

    private Long adminAId;    // teamA の ADMIN
    private Long memberAId;    // teamA の MEMBER（既メンバー・非 ADMIN）
    private Long targetId;     // teamA 非メンバー（DM 相手・招待される側）
    private Long strangerId;   // 無関係な第三者

    private Long adminOrgId;   // orgA / archivedOrg の ADMIN
    private Long orgMemberId;  // orgA の MEMBER（org 既メンバー）

    private Long dmAdminTargetId;   // DM: adminA × target
    private Long dmMemberTargetId;  // DM: memberA × target
    private Long groupDmId;         // GROUP_DM: adminA × target × stranger
    private Long dmAdminOrgTargetId;   // DM: adminOrg × target
    private Long dmAdminOrgMemberId;   // DM: adminOrg × orgMember

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CMIAUTHZ チームA");
        orgAId = insertOrganization("CMIAUTHZ 組織A", false);
        archivedOrgId = insertOrganization("CMIAUTHZ アーカイブ組織", true);

        adminAId = insertUser("cmiauthz-admin@example.com");
        memberAId = insertUser("cmiauthz-member@example.com");
        targetId = insertUser("cmiauthz-target@example.com");
        strangerId = insertUser("cmiauthz-stranger@example.com");
        adminOrgId = insertUser("cmiauthz-orgadmin@example.com");
        orgMemberId = insertUser("cmiauthz-orgmember@example.com");

        // teamA
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // target / stranger は teamA に非所属。

        // orgA / archivedOrg
        MembershipTestHelper.insertMembership(em, adminOrgId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgId, ScopeType.ORGANIZATION, archivedOrgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgId, "ADMIN", null, archivedOrgId);
        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // ロールは上の各ヘルパーで seed 済（test profile は Flyway 無効のため）。

        dmAdminTargetId = createDm(adminAId, targetId);
        dmMemberTargetId = createDm(memberAId, targetId);
        groupDmId = createGroupDm(adminAId, targetId, strangerId);
        dmAdminOrgTargetId = createDm(adminOrgId, targetId);
        dmAdminOrgMemberId = createDm(adminOrgId, orgMemberId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 発行 API 入口の認可（発行者 ADMIN/DEPUTY_ADMIN でなければ 403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 発行入口の認可")
    class IssueAuthorization {

        @Test
        @DisplayName("発行者が対象スコープの ADMIN/DEPUTY_ADMIN でなければ 403（TEAM_048）")
        void 非ADMINの発行は403() throws Exception {
            setAuth(memberAId); // teamA の MEMBER（DM 当事者だが非 ADMIN）
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmMemberTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TEAM_048"));
        }

        @Test
        @DisplayName("ADMIN の発行は 201・宛先付きトークン＋INVITE_CARD メッセージが永続する")
        void ADMIN発行でトークンとカードが永続する() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();

            // 宛先付きトークン（target_user_id 非 NULL）が発行されている
            Number tokenId = (Number) em.createNativeQuery(
                            "SELECT id FROM invite_tokens "
                                    + "WHERE team_id = :tid AND target_user_id = :target")
                    .setParameter("tid", teamAId)
                    .setParameter("target", targetId)
                    .getSingleResult();
            assertThat(tokenId).isNotNull();

            // DM に INVITE_CARD メッセージが投稿され、当該トークンを参照している
            Number cardTokenRef = (Number) em.createNativeQuery(
                            "SELECT invite_token_id FROM chat_messages "
                                    + "WHERE channel_id = :cid AND message_type = 'INVITE_CARD'")
                    .setParameter("cid", dmAdminTargetId)
                    .getSingleResult();
            assertThat(cardTokenRef.longValue()).isEqualTo(tokenId.longValue());
        }
    }

    @Nested
    @DisplayName("1b. 取消対象のチャンネル束縛")
    class RevokeChannelBinding {

        @Test
        @DisplayName("別 DM の channelId から宛先付き招待を取消せない")
        void 別DM経由では取消不可() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isCreated());

            InviteTokenEntity token = inviteTokenRepository
                    .findByTargetUserIdAndTeamIdAndRevokedAtIsNull(targetId, teamAId).getFirst();

            mockMvc.perform(delete("/api/v1/chat/channels/{cid}/membership-invite/{tokenId}",
                            dmMemberTargetId, token.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROLE_002"));

            em.flush();
            em.clear();
            assertThat(inviteTokenRepository.findById(token.getId()).orElseThrow().getRevokedAt()).isNull();
        }

        @Test
        @DisplayName("チャット外の共有リンク型トークンを取消 API で失効できない")
        void 共有リンク型トークンは取消不可() throws Exception {
            String rawToken = seedNamedToken(
                    teamAId, null, resolveRoleId("MEMBER"), LocalDateTime.now().plusDays(7));
            InviteTokenEntity token = inviteTokenRepository.findByToken(rawToken).orElseThrow();

            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/chat/channels/{cid}/membership-invite/{tokenId}",
                            dmAdminTargetId, token.getId()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROLE_002"));

            em.flush();
            em.clear();
            assertThat(inviteTokenRepository.findById(token.getId()).orElseThrow().getRevokedAt()).isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 権限昇格封鎖（C-1）: 特権ロール指定は 422／join 保険ガードでも付与拒否
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 権限昇格封鎖（特権ロール）")
    class PrivilegeEscalationBlock {

        @Test
        @DisplayName("roleId に ADMIN を指定した発行は 422（ROLE_009・C-1）")
        void 特権ロール指定の発行は422() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("ADMIN"), 7))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_009"));
        }

        @Test
        @DisplayName("join 側の保険ガード: 宛先付きトークンでも特権ロールは付与されない（4xx・ADMIN にならない）")
        void join保険ガードで特権ロール付与を拒否する() throws Exception {
            // 何らかの経路で特権ロール付きトークンが混入しても、join で ADMIN を付与してはならない。
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("ADMIN"),
                    LocalDateTime.now().plusDays(7));
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().is4xxClientError());

            // target が teamA の ADMIN user_role を得ていないこと（権限昇格が起きていない）
            assertThat(hasUserRole(targetId, teamAId, "ADMIN")).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 宛先照合（join / decline は宛先本人だけ・第三者は 403 ROLE_009）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 宛先照合 IDOR（join / decline）")
    class RecipientMatching {

        @Test
        @DisplayName("宛先付きトークンを第三者が join すると 403（ROLE_009）")
        void 第三者のjoinは403() throws Exception {
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().plusDays(7));
            setAuth(strangerId); // 宛先 target ではない第三者
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ROLE_009"));

            // 第三者は参加していない
            assertThat(hasUserRole(strangerId, teamAId, "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("宛先本人の join は成功（200）")
        void 宛先本人のjoinは成功() throws Exception {
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().plusDays(7));
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertThat(hasUserRole(targetId, teamAId, "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("宛先付きトークンを第三者が decline すると 403（ROLE_009）")
        void 第三者のdeclineは403() throws Exception {
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().plusDays(7));
            setAuth(strangerId);
            mockMvc.perform(post("/api/v1/invite/{token}/decline", token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ROLE_009"));
        }

        @Test
        @DisplayName("宛先本人の decline は成功し REVOKED（revoked_at セット）・以後同トークンの join は弾かれる（二重accept不可）")
        void 宛先本人のdeclineは成功し辞退後にjoinできない() throws Exception {
            // 承諾型の核心: 宛先本人が辞退すると revoked_at が立ち（REVOKED）、
            // 以後同じトークンで join を試みても参加が復活しない（辞退の永続性・二重accept封鎖）。
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().plusDays(7));

            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/decline", token))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            // revoked_at がセットされている（REVOKED へ導出される）。
            Object revokedAt = em.createNativeQuery(
                            "SELECT revoked_at FROM invite_tokens WHERE token = :tk")
                    .setParameter("tk", token)
                    .getSingleResult();
            assertThat(revokedAt).isNotNull();

            // 辞退後に同じトークンで join を試みても弾かれ（4xx）、参加は復活しない。
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().is4xxClientError());

            em.flush();
            em.clear();
            assertThat(hasUserRole(targetId, teamAId, "MEMBER")).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 既メンバー・重複 PENDING・GROUP_DM
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 状態・チャンネル種別の検証")
    class StateAndChannelValidation {

        @Test
        @DisplayName("既メンバーの宛先を join すると 409（TEAM_003）")
        void 既メンバーのjoinは409() throws Exception {
            // memberA は teamA の既メンバー。宛先付きトークンを本人が消費しても二重参加は拒否。
            String token = seedNamedToken(teamAId, memberAId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().plusDays(7));
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TEAM_003"));
        }

        @Test
        @DisplayName("同一 DM × 同一スコープ宛の PENDING が既存なら発行は 409（ROLE_003・重複防止）")
        void 重複PENDINGの発行は409() throws Exception {
            // 既存の有効な宛先付き PENDING を先に置く。
            seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"), LocalDateTime.now().plusDays(7));
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ROLE_003"));
        }

        @Test
        @DisplayName("期限切れの宛先トークンの join は拒否され未参加（EXPIRED 境界・既存 isValid() 準拠）")
        void 期限切れ宛先トークンのjoinは拒否() throws Exception {
            // expires_at < now は EXPIRED（isValid() 準拠。expires_at == now は有効側）。
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    LocalDateTime.now().minusSeconds(1));
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().is4xxClientError());

            em.flush();
            em.clear();
            assertThat(hasUserRole(targetId, teamAId, "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("期限が現在以降のトークンの join は成功する（M-1 境界 inclusive・isValid() は isBefore 判定で == は有効）")
        void 期限境界の宛先トークンのjoinは成功() throws Exception {
            // M-1: EXPIRED は expires_at < NOW（strict isBefore）のみ。expires_at == NOW（境界ちょうど）は有効側。
            // 実クロック（Clock 非注入）ではナノ秒精度の「厳密な ==」を固定できないため、
            // seed から join までに時計が進んでも EXPIRED に落ちない「現在以降」の境界近傍を用いて
            // 「境界は inclusive（未失効）」を担保する（strictly-past を突く EXPIRED テストと対を成す）。
            LocalDateTime now = LocalDateTime.now();
            String token = seedNamedToken(teamAId, targetId, resolveRoleId("MEMBER"),
                    now.plusSeconds(5));
            setAuth(targetId);
            mockMvc.perform(post("/api/v1/invite/{token}/join", token))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertThat(hasUserRole(targetId, teamAId, "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("GROUP_DM チャンネルからの発行は 422（DM 限定）")
        void GROUP_DMからの発行は422() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", groupDmId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("TEAM", teamAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 組織スコープのエラーは ORG 系コードで返す（TEAM 系に寄せない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 組織スコープのエラーコード（ORG 系）")
    class OrganizationScopeErrors {

        @Test
        @DisplayName("アーカイブ済み組織への発行は 422（ORG_003・TEAM_002 に寄せない）")
        void アーカイブ組織への発行は422_ORG_003() throws Exception {
            setAuth(adminOrgId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminOrgTargetId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("ORGANIZATION", archivedOrgId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ORG_003"));
        }

        @Test
        @DisplayName("組織既メンバーへの発行は 409（ORG_007・TEAM_003 に寄せない）")
        void 組織既メンバーへの発行は409_ORG_007() throws Exception {
            setAuth(adminOrgId);
            mockMvc.perform(post("/api/v1/chat/channels/{cid}/membership-invite", dmAdminOrgMemberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    inviteBody("ORGANIZATION", orgAId, resolveRoleId("MEMBER"), 7))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ORG_007"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. invitable-scopes（管理スコープ 0 件でも 200 空配列・エラーにしない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 招待発行可能スコープ一覧（GET /api/v1/me/invitable-scopes）")
    class InvitableScopesEndpoint {

        @Test
        @DisplayName("ADMIN/DEPUTY_ADMIN スコープを 1 つも持たないユーザーは 200 ＋ 空配列（エラーにしない・B-6）")
        void 管理スコープ0件でも200空配列() throws Exception {
            // stranger は teamA / orgA いずれの ADMIN/DEPUTY_ADMIN でもない（非所属）。
            setAuth(strangerId);
            mockMvc.perform(get("/api/v1/me/invitable-scopes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.teams").isArray())
                    .andExpect(jsonPath("$.data.teams").isEmpty())
                    .andExpect(jsonPath("$.data.organizations").isArray())
                    .andExpect(jsonPath("$.data.organizations").isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> inviteBody(String scopeType, Long scopeId, Long roleId, Integer expiresInDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        body.put("roleId", roleId);
        body.put("expiresInDays", expiresInDays);
        return body;
    }

    /** 宛先付き（target_user_id 非 NULL）の招待トークンを直接 seed し、token 文字列を返す。 */
    private String seedNamedToken(Long teamId, Long target, Long roleId, LocalDateTime expiresAt) {
        String token = UUID.randomUUID().toString();
        inviteTokenRepository.save(InviteTokenEntity.builder()
                .token(token)
                .teamId(teamId)
                .roleId(roleId)
                .createdBy(adminAId)
                .expiresAt(expiresAt)
                .usedCount(0)
                .targetUserId(target)
                .build());
        em.flush();
        em.clear();
        return token;
    }

    private Long createDm(Long userA, Long userB) {
        return createChannel(ChannelType.DM, userA, userB, null);
    }

    private Long createGroupDm(Long userA, Long userB, Long userC) {
        return createChannel(ChannelType.GROUP_DM, userA, userB, userC);
    }

    private Long createChannel(ChannelType type, Long userA, Long userB, Long userC) {
        ChatChannelEntity channel = channelRepository.save(ChatChannelEntity.builder()
                .channelType(type)
                .name(null)
                .isPrivate(true)
                .createdBy(userA)
                .build());
        Long channelId = channel.getId();
        channelMemberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userA).build());
        channelMemberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userB).build());
        if (userC != null) {
            channelMemberRepository.save(ChatChannelMemberEntity.builder()
                    .channelId(channelId).userId(userC).build());
        }
        return channelId;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** roles.name から id を解決（test profile では Flyway 無効のため無ければ on-demand 投入）。 */
    private Long resolveRoleId(String roleName) {
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

    private boolean hasUserRole(Long userId, Long teamId, String roleName) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                + "WHERE ur.user_id = :uid AND ur.team_id = :tid AND r.name = :rn")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .setParameter("rn", roleName)
                .getSingleResult();
        return count.longValue() > 0;
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
                                + "VALUES (:email, 'CMIAUTHZ', 'テスト', 'CMIAUTHZ テスト', 'ACTIVE', "
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
        String slug = "cmi-" + Long.toHexString(System.nanoTime());
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

    private Long insertOrganization(String name, boolean archived) {
        String slug = "cmi-org-" + Long.toHexString(System.nanoTime());
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, archived_at, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, "
                                + (archived ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
