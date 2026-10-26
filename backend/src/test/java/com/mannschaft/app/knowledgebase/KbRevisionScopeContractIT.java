package com.mannschaft.app.knowledgebase;

import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRevisionRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B10: knowledgebase revision（{@code KbRevisionController}）の
 * BOLA是正 API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B10 knowledgebase節）。{@code KbRevisionService#findPage} が
 * 従来 {@code pageId} の scope 未束縛（{@code findByIdAndDeletedAtIsNull} のみ）で取得しており、
 * 他 team の {@code pageId} を渡すと自 team の path から revision 一覧/詳細/復元操作ができてしまう
 * BOLA が成立していた。findPage に scopeType/scopeId 束縛を追加し、不一致は KB_001（404）で
 * 存在秘匿する。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: ①非メンバー→403 ②越境pageId（他teamのpageId・BOLA本丸）→404（KB_001） ③正当ADMIN→成功。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("knowledgebase revision ドメイン BOLA是正 API 契約テスト（認可根治 Wave3-B10）")
class KbRevisionScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KbPageRepository kbPageRepository;

    @Autowired
    private KbPageRevisionRepository kbPageRevisionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long outsiderId;

    private Long pageAId;
    private Long revisionAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("KBREV認可契約チームA");
        teamBId = insertTeam("KBREV認可契約チームB");

        adminAId = insertUser("kbrev-authz-admin-a@example.com");
        adminBId = insertUser("kbrev-authz-admin-b@example.com");
        outsiderId = insertUser("kbrev-authz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);

        KbPageEntity pageA = kbPageRepository.save(KbPageEntity.builder()
                .scopeType("TEAM").scopeId(teamAId)
                .path("/0").depth(0)
                .title("KBREVテストページA").slug("kbrev-page-a")
                .body("本文A")
                .accessLevel(PageAccessLevel.ALL_MEMBERS)
                .status(PageStatus.PUBLISHED)
                .createdBy(adminAId)
                .build());
        pageAId = pageA.getId();
        pageA.updatePath("/" + pageAId);
        kbPageRepository.save(pageA);

        KbPageRevisionEntity revisionA = kbPageRevisionRepository.save(KbPageRevisionEntity.builder()
                .kbPageId(pageAId)
                .revisionNumber(1)
                .title("旧タイトルA")
                .body("旧本文A")
                .editorId(adminAId)
                .build());
        revisionAId = revisionA.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // リビジョン一覧(getRevisions)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リビジョン一覧(getRevisions)")
    class GetRevisions {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions",
                            teamAId, pageAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境pageId（他teamのADMINが自teamのURLで他teamのpageIdを叩く）は404（BOLA・KB_001）")
        void 越境pageIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions",
                            teamBId, pageAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_001"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions",
                            teamAId, pageAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(revisionAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リビジョン詳細(getRevision)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リビジョン詳細(getRevision)")
    class GetRevision {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}",
                            teamAId, pageAId, revisionAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境pageIdは404（BOLA・KB_001）")
        void 越境pageIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}",
                            teamBId, pageAId, revisionAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_001"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}",
                            teamAId, pageAId, revisionAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("旧タイトルA"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リビジョン復元(restoreRevision)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リビジョン復元(restoreRevision)")
    class RestoreRevision {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}/restore",
                            teamAId, pageAId, revisionAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境pageId（他teamのADMINが自teamのURLで他teamのpageId/revisionIdを渡し復元)は404（BOLA・KB_001）")
        void 越境pageIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}/restore",
                            teamBId, pageAId, revisionAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_001"));
        }

        @Test
        @DisplayName("正当ADMINは200・pageが復元される")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/revisions/{revisionId}/restore",
                            teamAId, pageAId, revisionAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("旧タイトルA"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
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
                                + "VALUES (:email, 'KBREV認可契約', 'テスト', 'KBREV認可契約テスト', 'ACTIVE', "
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
                                + "CONCAT('kbrev-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
