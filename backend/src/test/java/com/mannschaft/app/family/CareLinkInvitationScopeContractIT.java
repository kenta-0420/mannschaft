package com.mannschaft.app.family;

import com.mannschaft.app.family.entity.TeamCareNotificationOverrideEntity;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.TeamCareNotificationOverrideRepository;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ケアリンク（F03.12）とプレゼンス一括送信の認可契約テスト
 * （認可根治戦役 第2波・PII 領域 ロットA）。
 *
 * <p>本 IT が固定する保証:</p>
 * <ul>
 *   <li><b>招待トークン経由の EP</b>（参照・承認・拒否）: 参照は当該ケアリンクの<b>当事者</b>
 *       （ケア対象者本人または見守り者）、承認・拒否は<b>招待を受けた側</b>に限定する。
 *       当事者以外は 403、不一致トークンは 404。<b>ケアリンクは双方の同意でのみ成立</b>し、
 *       招待を発行した側が自分で成立させることはできない。</li>
 *   <li><b>リンク ID を受け取る EP</b>（通知設定変更・解除）: entity 由来の当事者に限定し、
 *       当事者以外は 403、不存在の linkId は 404 で存在を秘匿する。</li>
 *   <li><b>チームケア通知上書き設定</b>（取得・upsert・削除）: 当事者に限定し、
 *       チーム ADMIN であっても当事者でなければ 403。</li>
 *   <li><b>自己スコープ EP</b>（見守り者／ケア対象者／保留中招待の一覧・招待の発行・
 *       プレゼンス一括送信）: 当事者の自分側 ID は認証主体から解決され、他ユーザーの
 *       ケアリンクが混入しない。</li>
 * </ul>
 *
 * <p>金型: {@code TodoPersonalScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ケアリンク・プレゼンス 認可契約テスト（第2波 ロットA）")
class CareLinkInvitationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserCareLinkRepository careLinkRepository;

    @Autowired
    private TeamCareNotificationOverrideRepository overrideRepository;

    @PersistenceContext
    private EntityManager em;

    private Long recipientId;   // ケア対象者（招待を発行した側）
    private Long watcherId;     // 見守り者（招待を受けた側）
    private Long outsiderId;    // 無関係な他ユーザー（越境元・チーム ADMIN でもある）

    private Long teamId;

    private Long pendingLinkId;        // PENDING のケアリンク（recipient が発行）
    private String pendingToken;       // 上記の招待トークン
    private Long activeLinkId;         // ACTIVE のケアリンク（設定変更・解除の検証用）
    private Long overrideCareLinkId;   // 上書き設定が紐づくケアリンク ID

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);

        recipientId = insertUser("careauthz-recipient-" + uniq + "@example.test");
        watcherId = insertUser("careauthz-watcher-" + uniq + "@example.test");
        outsiderId = insertUser("careauthz-outsider-" + uniq + "@example.test");

        teamId = insertTeam("CAREAUTHZ チーム", "cat-" + uniq);

        UserCareLinkEntity pending = careLinkRepository.save(UserCareLinkEntity.builder()
                .careRecipientUserId(recipientId)
                .watcherUserId(watcherId)
                .careCategory(CareCategory.MINOR)
                .relationship(CareRelationship.PARENT)
                .status(CareLinkStatus.PENDING)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .invitationToken("careauthz-token-" + uniq)
                .invitationSentAt(LocalDateTime.now())
                .createdBy(recipientId)
                .build());
        pendingLinkId = pending.getId();
        pendingToken = pending.getInvitationToken();

        activeLinkId = careLinkRepository.save(UserCareLinkEntity.builder()
                .careRecipientUserId(recipientId)
                .watcherUserId(watcherId)
                .careCategory(CareCategory.ELDERLY)
                .relationship(CareRelationship.CHILD)
                .status(CareLinkStatus.ACTIVE)
                .invitedBy(CareLinkInvitedBy.WATCHER)
                .invitationToken("careauthz-active-" + uniq)
                .confirmedAt(LocalDateTime.now())
                .createdBy(watcherId)
                .build()).getId();

        overrideCareLinkId = activeLinkId;
        overrideRepository.save(TeamCareNotificationOverrideEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .careLinkId(overrideCareLinkId)
                .notifyOnRsvp(false)
                .createdBy(recipientId)
                .build());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 招待の参照（当事者限定・不一致トークンは404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /care-links/invitations/{token}（招待内容の参照）")
    class GetInvitation {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/care-links/invitations/{token}", pendingToken))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("当事者でない他ユーザーは403（続柄・ケア区分を開示しない）")
        void 他ユーザーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/care-links/invitations/{token}", pendingToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("不一致トークンは404で存在を秘匿")
        void 不一致トークンは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/care-links/invitations/{token}", "careauthz-unknown-token"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 当事者（招待先）は招待内容を参照できる")
        void 当事者は200() throws Exception {
            setAuth(watcherId);
            mockMvc.perform(get("/api/v1/care-links/invitations/{token}", pendingToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(pendingLinkId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 招待の承認・拒否（招待を受けた側のみ・双方の同意で成立）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /care-links/invitations/{token}/{accept,reject}")
    class AcceptRejectInvitation {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/care-links/invitations/{token}/accept", pendingToken))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("当事者でない他ユーザーの承認は403（PENDING のまま成立しない）")
        void 他ユーザーの承認は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/care-links/invitations/{token}/accept", pendingToken))
                    .andExpect(status().isForbidden());

            UserCareLinkEntity intact = careLinkRepository.findById(pendingLinkId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo(CareLinkStatus.PENDING);
        }

        @Test
        @DisplayName("招待を発行した側は自分で承認できない（403・PENDING のまま）")
        void 招待発行者の承認は403() throws Exception {
            setAuth(recipientId);
            mockMvc.perform(post("/api/v1/care-links/invitations/{token}/accept", pendingToken))
                    .andExpect(status().isForbidden());

            UserCareLinkEntity intact = careLinkRepository.findById(pendingLinkId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo(CareLinkStatus.PENDING);
        }

        @Test
        @DisplayName("当事者でない他ユーザーの拒否は403（PENDING のまま）")
        void 他ユーザーの拒否は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/care-links/invitations/{token}/reject", pendingToken))
                    .andExpect(status().isForbidden());

            UserCareLinkEntity intact = careLinkRepository.findById(pendingLinkId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo(CareLinkStatus.PENDING);
        }

        @Test
        @DisplayName("正常系: 招待を受けた側は承認できる（ACTIVE になる）")
        void 招待先の承認は200() throws Exception {
            setAuth(watcherId);
            mockMvc.perform(post("/api/v1/care-links/invitations/{token}/accept", pendingToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));

            UserCareLinkEntity activated = careLinkRepository.findById(pendingLinkId).orElseThrow();
            assertThat(activated.getStatus()).isEqualTo(CareLinkStatus.ACTIVE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. ケアリンクの通知設定変更・解除（当事者限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH / DELETE /me/care-links/{linkId}")
    class UpdateAndRevokeLink {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/me/care-links/{linkId}", activeLinkId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("当事者でない他ユーザーの通知設定変更は403（設定は変わらない）")
        void 他ユーザーの設定変更は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/me/care-links/{linkId}", activeLinkId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"notifyOnRsvp\":false}"))
                    .andExpect(status().isForbidden());

            UserCareLinkEntity intact = careLinkRepository.findById(activeLinkId).orElseThrow();
            assertThat(intact.getNotifyOnRsvp()).isTrue();
        }

        @Test
        @DisplayName("当事者でない他ユーザーの解除は403（ACTIVE のまま）")
        void 他ユーザーの解除は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/me/care-links/{linkId}", activeLinkId))
                    .andExpect(status().isForbidden());

            UserCareLinkEntity intact = careLinkRepository.findById(activeLinkId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo(CareLinkStatus.ACTIVE);
            assertThat(intact.getRevokedAt()).isNull();
        }

        @Test
        @DisplayName("不存在の linkId は404で存在を秘匿")
        void 不存在は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/me/care-links/{linkId}", 99999999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 当事者は通知設定を変更でき、解除もできる")
        void 当事者は成功() throws Exception {
            setAuth(watcherId);
            mockMvc.perform(patch("/api/v1/me/care-links/{linkId}", activeLinkId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"notifyOnRsvp\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.notifyOnRsvp").value(false));

            mockMvc.perform(delete("/api/v1/me/care-links/{linkId}", activeLinkId))
                    .andExpect(status().isNoContent());

            UserCareLinkEntity revoked = careLinkRepository.findById(activeLinkId).orElseThrow();
            assertThat(revoked.getStatus()).isEqualTo(CareLinkStatus.REVOKED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. チームケア通知上書き設定（当事者限定・ADMIN でも不可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET / PUT / DELETE /teams/{teamId}/care-overrides/{careLinkId}")
    class TeamCareOverride {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("当事者でない他ユーザーの参照は403")
        void 他ユーザーの参照は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("当事者でない他ユーザーの upsert は403（設定は変わらない）")
        void 他ユーザーのupsertは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\":true}"))
                    .andExpect(status().isForbidden());

            TeamCareNotificationOverrideEntity intact = overrideRepository
                    .findByScopeTypeAndScopeIdAndCareLinkId("TEAM", teamId, overrideCareLinkId)
                    .orElseThrow();
            assertThat(intact.getDisabled()).isFalse();
        }

        @Test
        @DisplayName("当事者でない他ユーザーの削除は403（設定は残る）")
        void 他ユーザーの削除は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId))
                    .andExpect(status().isForbidden());

            assertThat(overrideRepository
                    .findByScopeTypeAndScopeIdAndCareLinkId("TEAM", teamId, overrideCareLinkId))
                    .isPresent();
        }

        @Test
        @DisplayName("不存在の careLinkId は404で存在を秘匿")
        void 不存在は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, 99999999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 当事者は参照・upsert・削除ができる")
        void 当事者は成功() throws Exception {
            setAuth(recipientId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.careLinkId").value(overrideCareLinkId.intValue()));

            mockMvc.perform(put("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"disabled\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.disabled").value(true));

            mockMvc.perform(delete("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamId, overrideCareLinkId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 自己スコープ EP（一覧・招待の発行・プレゼンス一括送信）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 本 Nested クラスが自己スコープ性を固定する対象エンドポイント（認可根治戦役 Wave6 ロットG）:
     * {@code CareLinkController#getActiveWatchers} / {@code CareLinkController#getActiveRecipients} /
     * {@code CareLinkController#getPendingInvitations} / {@code CareLinkController#inviteWatcher} /
     * {@code CareLinkController#inviteRecipient} / {@code PresenceController#sendHomeBulk} /
     * {@code PresenceController#sendGoingOutBulk}。
     */
    @Nested
    @DisplayName("5. 自己スコープ EP（一覧・招待発行・プレゼンス一括）")
    class SelfScopedEndpoints {

        @Test
        @DisplayName("未認証は401（一覧3種: CareLinkController#getActiveWatchers/getActiveRecipients/getPendingInvitations）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/care-links/watchers"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/me/care-links/recipients"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/me/care-links/invitations"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのケアリンクは一覧に混入しない（CareLinkController#getActiveWatchers/getActiveRecipients/getPendingInvitations）")
        void 一覧に混入しない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/me/care-links/watchers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(activeLinkId.intValue()))));
            mockMvc.perform(get("/api/v1/me/care-links/recipients"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(activeLinkId.intValue()))));
            mockMvc.perform(get("/api/v1/me/care-links/invitations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(pendingLinkId.intValue()))));
        }

        @Test
        @DisplayName("正常系: 当事者の一覧には自分のケアリンクが出る（CareLinkController#getActiveWatchers/getActiveRecipients）")
        void 当事者の一覧は200() throws Exception {
            setAuth(recipientId);
            mockMvc.perform(get("/api/v1/me/care-links/watchers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(activeLinkId.intValue())));

            setAuth(watcherId);
            mockMvc.perform(get("/api/v1/me/care-links/recipients"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(activeLinkId.intValue())));
        }

        @Test
        @DisplayName("招待の発行は自分側の当事者 ID が認証主体に固定される（PENDING で作成・"
                + "CareLinkController#inviteWatcher/inviteRecipient の自己スコープ性を固定）")
        void 招待発行は自己スコープ() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/me/care-links/invite-watcher")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"watcherUserId\":" + watcherId
                                    + ",\"careCategory\":\"MINOR\",\"relationship\":\"PARENT\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.careRecipientUserId").value(outsiderId.intValue()))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));

            mockMvc.perform(post("/api/v1/me/care-links/invite-recipient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"careRecipientUserId\":" + watcherId
                                    + ",\"careCategory\":\"ELDERLY\",\"relationship\":\"CHILD\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.watcherUserId").value(outsiderId.intValue()))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("プレゼンス一括送信は認証主体のみを送信元とする（PresenceController#sendHomeBulk/sendGoingOutBulk の自己スコープ性を固定）")
        void プレゼンス一括は自己スコープ() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/users/me/presence/home"))
                    .andExpect(status().isUnauthorized());

            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/users/me/presence/home"))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/users/me/presence/going-out")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"destination\":\"CAREAUTHZ 検証用の行き先\"}"))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoPersonalScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'CAREAUTHZ', 'テスト', 'CAREAUTHZ テスト', 'ACTIVE', "
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
}
