package com.mannschaft.app.social.announcement;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 AC-H17 / §3.1・§10.1: 組織お知らせ一覧（送信履歴）が返す {@code targetAudience}（グループ名・チーム数）は、
 * チームグループを閲覧してよい者（組織 MEMBER 以上・SYSTEM_ADMIN。{@code GET /organizations/{slug}/team-groups} と同じ判定）にだけ返す。
 *
 * <p>グループ宛ての告知は宛先外の人（非メンバー・他組織・宛先グループに属さないチームの者）の一覧に出さない
 * （一覧と本文の可否を一致。CMP-261011-0035）ので、グループ構成も渡らない（Controller 経由で検証する）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織お知らせ一覧の targetAudience は閲覧権限のある者にだけ返す（IT）")
class AnnouncementFeedOrgTargetAudienceIT extends AbstractMySqlIntegrationTest {

    private static final String AUDIENCE_JSON = "{\"mode\":\"GROUPS\","
            + "\"groups\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"平成18年度\"}],"
            + "\"range\":null,\"includeUnassigned\":false,\"teamCount\":12,\"directMemberCount\":3}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long childTeamAId;
    private Long adminAId;
    private Long memberAId;
    private Long childMemberAId;
    private Long adminBId;
    private Long outsiderId;
    private Long systemAdminId;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("AUD 組織A");
        orgBId = insertOrganization("AUD 組織B");
        childTeamAId = insertTeam("AUD 配下チームA");
        insertTeamOrgMembership(childTeamAId, orgAId);

        adminAId = insertUser("aud-admin-a@example.com");
        memberAId = insertUser("aud-member-a@example.com");
        childMemberAId = insertUser("aud-child-member-a@example.com");
        adminBId = insertUser("aud-admin-b@example.com");
        outsiderId = insertUser("aud-outsider@example.com");
        systemAdminId = insertUser("aud-sysadmin@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // 配下チームのみ所属（組織には直属しない）
        MembershipTestHelper.insertMembership(em, childMemberAId, ScopeType.TEAM, childTeamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        // 一覧は本文プレビューが開ける行だけを返すので、元のブログ記事は実在する公開記事にする（CMP-261011-0035）
        var post = com.mannschaft.app.cms.entity.BlogPostEntity.builder().organizationId(orgAId).authorId(adminAId)
                .title("AUD 記事").slug("aud-post-" + java.util.UUID.randomUUID()).body("AUD 本文")
                .visibility(com.mannschaft.app.cms.Visibility.PUBLIC)
                .status(com.mannschaft.app.cms.PostStatus.PUBLISHED)
                .postType(com.mannschaft.app.cms.PostType.BLOG).build();
        em.persist(post);
        feedRepository.save(AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgAId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(post.getId())
                .titleCache("AUD グループ宛て")
                .visibility(AnnouncementVisibility.PUBLIC)
                .targetAudience(AUDIENCE_JSON)
                .build());
        em.flush();
        em.clear();
    }

    /**
     * グループ宛ての告知は宛先外の人（他組織・無所属・グループに属さないチームの者）の一覧に出ない
     * （一覧と本文の可否を一致。CMP-261011-0035）。出ないのでグループ構成も渡らない。
     */
    private void listHiddenFrom(Long userId) throws Exception {
        setAuth(userId);
        mockMvc.perform(get("/api/v1/organizations/{orgId}/announcements", orgAId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private void listAs(Long userId, boolean expectAudience) throws Exception {
        setAuth(userId);
        var result = mockMvc.perform(get("/api/v1/organizations/{orgId}/announcements", orgAId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].titleCache").value("AUD グループ宛て"));
        if (expectAudience) {
            result.andExpect(jsonPath("$.data[0].targetAudience.teamCount").value(12))
                    .andExpect(jsonPath("$.data[0].targetAudience.groups[0].name").value("平成18年度"));
        } else {
            result.andExpect(jsonPath("$.data[0].targetAudience").doesNotExist());
        }
    }

    @Test
    @DisplayName("組織 ADMIN には返る")
    void admin() throws Exception {
        listAs(adminAId, true);
    }

    @Test
    @DisplayName("組織 MEMBER（グループ閲覧権限あり）には返る")
    void member() throws Exception {
        listAs(memberAId, true);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN には返る")
    void systemAdmin() throws Exception {
        listAs(systemAdminId, true);
    }

    @Test
    @DisplayName("配下チームだけの所属者（組織の閲覧権限なし・宛先グループ外）の一覧にはグループ宛て告知自体が出ない")
    void childTeamOnlyMember() throws Exception {
        listHiddenFrom(childMemberAId);
    }

    @Test
    @DisplayName("非メンバーの一覧にはグループ宛て告知自体が出ない（グループ構成も渡らない）")
    void outsider() throws Exception {
        listHiddenFrom(outsiderId);
    }

    @Test
    @DisplayName("他組織の ADMIN の一覧にもグループ宛て告知自体が出ない")
    void otherOrgAdmin() throws Exception {
        listHiddenFrom(adminBId);
    }

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
                                + "VALUES (:email, 'AUD', 'テスト', 'AUD テスト', 'ACTIVE', "
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
                                + "CONCAT('aud-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('audo-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

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
