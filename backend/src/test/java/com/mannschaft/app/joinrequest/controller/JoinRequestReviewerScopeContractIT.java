package com.mannschaft.app.joinrequest.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import com.mannschaft.app.joinrequest.repository.JoinRequestRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理一覧と新規参加可否の分離を実MySQL・実Securityフィルタで検証する。
 *
 * <p>JoinRequestController#listForTeam / #listForOrganization の非公開スコープでの取得失敗を
 * 再現する回帰試験。新規申請・審査の許可範囲は広げず、所属管理者だけが履歴を閲覧できる。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("参加申請管理一覧の公開範囲・認可契約")
class JoinRequestReviewerScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private MembershipRepository memberships;
    @Autowired private JoinRequestRepository requests;
    @PersistenceContext private EntityManager entityManager;

    private Long reviewerId;
    private Long applicantId;

    @BeforeEach
    void 利用者を用意する() {
        reviewerId = user();
        applicantId = user();
    }

    @ParameterizedTest
    @CsvSource({
            "TEAM,true,ADMIN", "TEAM,true,DEPUTY_ADMIN", "TEAM,false,ADMIN", "TEAM,false,DEPUTY_ADMIN",
            "ORGANIZATION,true,ADMIN", "ORGANIZATION,true,DEPUTY_ADMIN",
            "ORGANIZATION,false,ADMIN", "ORGANIZATION,false,DEPUTY_ADMIN"
    })
    void 所属管理者は公開範囲によらず空Pageと既存申請を取得できる(String type, boolean publicScope,
                                                              String role) throws Exception {
        Scope scope = scope(type, publicScope, "ACTIVE");
        grant(scope, role);

        mockMvc.perform(get(scope.url()).with(asReviewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        JoinRequestEntity pending = request(scope, applicantId, JoinRequestStatus.PENDING);
        mockMvc.perform(get(scope.url()).with(asReviewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(pending.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @ParameterizedTest
    @CsvSource({"TEAM,MEMBER", "TEAM,OUTSIDER", "TEAM,OTHER_ADMIN", "TEAM,SYSTEM_ADMIN",
            "ORGANIZATION,MEMBER", "ORGANIZATION,OUTSIDER", "ORGANIZATION,OTHER_ADMIN",
            "ORGANIZATION,SYSTEM_ADMIN"})
    void 非公開一覧は所属管理者以外へ不在と同一404を返す(String type, String role) throws Exception {
        Scope scope = scope(type, false, "ACTIVE");
        if ("MEMBER".equals(role)) {
            memberships.saveAndFlush(MembershipEntity.builder().userId(reviewerId)
                    .scopeType(ScopeType.valueOf(type)).scopeId(scope.id())
                    .joinedAt(LocalDateTime.now()).build());
        } else if ("OTHER_ADMIN".equals(role)) {
            grant(scope(type, false, "ACTIVE"), "ADMIN");
        } else if ("SYSTEM_ADMIN".equals(role)) {
            grant(null, role);
        }
        request(scope, applicantId, JoinRequestStatus.PENDING);

        mockMvc.perform(get(scope.url()).with(asReviewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"))
                .andExpect(jsonPath("$.error.message").value("対象が見つかりません"));
        mockMvc.perform(get(url(type, Long.MAX_VALUE)).with(asReviewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"))
                .andExpect(jsonPath("$.error.message").value("対象が見つかりません"));
    }

    @ParameterizedTest
    @CsvSource({"TEAM", "ORGANIZATION"})
    void 公開一覧の非管理者拒否と未認証拒否を維持する(String type) throws Exception {
        Scope scope = scope(type, true, "ACTIVE");
        mockMvc.perform(get(scope.url()).with(asReviewer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(scope.url())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"TEAM,PROVISIONED", "TEAM,ARCHIVED", "TEAM,DELETED",
            "ORGANIZATION,PROVISIONED", "ORGANIZATION,ARCHIVED", "ORGANIZATION,DELETED"})
    void 非稼働スコープは管理一覧も新規申請も404を維持する(String type, String state) throws Exception {
        Scope scope = scope(type, true, state);
        grant(scope, "ADMIN");
        mockMvc.perform(get(scope.url()).with(asReviewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
        mockMvc.perform(post(scope.url()).with(asReviewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOIN_REQUEST_001"));
    }

    @ParameterizedTest
    @CsvSource({"TEAM", "ORGANIZATION"})
    void 非公開一覧のstatusとページングは他スコープを混入させない(String type) throws Exception {
        Scope scope = scope(type, false, "ACTIVE");
        grant(scope, "DEPUTY_ADMIN");
        request(scope, applicantId, JoinRequestStatus.PENDING);
        request(scope, user(), JoinRequestStatus.PENDING);
        JoinRequestEntity approved = request(scope, applicantId, JoinRequestStatus.APPROVED);
        request(scope(type, false, "ACTIVE"), applicantId, JoinRequestStatus.PENDING);

        mockMvc.perform(get(scope.url()).with(asReviewer()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.number").value(1));
        mockMvc.perform(get(scope.url()).with(asReviewer()).param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(approved.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@example.com")
                .lastName("参加申請").firstName("テスト").displayName("参加申請テスト")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserStatus.ACTIVE).build())
                .getId();
    }

    private Scope scope(String type, boolean publicScope, String state) {
        String slug = "jr-" + UUID.randomUUID().toString().substring(0, 12);
        LocalDateTime archivedAt = "ARCHIVED".equals(state) ? LocalDateTime.now() : null;
        LocalDateTime deletedAt = "DELETED".equals(state) ? LocalDateTime.now() : null;
        Long id;
        if ("TEAM".equals(type)) {
            id = teams.saveAndFlush(TeamEntity.builder().name(slug).slug(slug).supporterEnabled(false)
                    .visibility(publicScope ? TeamEntity.Visibility.PUBLIC : TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                    .lifecycleStatus("PROVISIONED".equals(state) ? TeamEntity.LifecycleStatus.PROVISIONED
                            : TeamEntity.LifecycleStatus.ACTIVE)
                    .archivedAt(archivedAt).deletedAt(deletedAt).build()).getId();
        } else {
            id = organizations.saveAndFlush(OrganizationEntity.builder().name(slug).slug(slug)
                    .orgType(OrganizationEntity.OrgType.OTHER).supporterEnabled(false)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .visibility(publicScope ? OrganizationEntity.Visibility.PUBLIC : OrganizationEntity.Visibility.PRIVATE)
                    .lifecycleStatus("PROVISIONED".equals(state) ? OrganizationEntity.LifecycleStatus.PROVISIONED
                            : OrganizationEntity.LifecycleStatus.ACTIVE)
                    .archivedAt(archivedAt).deletedAt(deletedAt).build()).getId();
        }
        // 実HTTPの別リクエストと同じ再読込境界を作り、削除済み行をL1キャッシュから返させない。
        entityManager.clear();
        return new Scope(type, id, url(type, id));
    }

    private void grant(Scope scope, String name) {
        RoleEntity role = roles.findByName(name).orElseGet(() -> roles.saveAndFlush(RoleEntity.builder()
                .name(name).displayName(name).priority(RolePriority.priority(name)).isSystem(true).build()));
        userRoles.saveAndFlush(UserRoleEntity.builder().userId(reviewerId).roleId(role.getId())
                .teamId(scope != null && "TEAM".equals(scope.type()) ? scope.id() : null)
                .organizationId(scope != null && "ORGANIZATION".equals(scope.type()) ? scope.id() : null).build());
    }

    private JoinRequestEntity request(Scope scope, Long requester, JoinRequestStatus status) {
        return requests.saveAndFlush(JoinRequestEntity.builder().requesterUserId(requester).status(status)
                .teamId("TEAM".equals(scope.type()) ? scope.id() : null)
                .organizationId("ORGANIZATION".equals(scope.type()) ? scope.id() : null).build());
    }

    private RequestPostProcessor asReviewer() {
        return authentication(new UsernamePasswordAuthenticationToken(reviewerId.toString(), null, List.of()));
    }

    private String url(String type, Long id) {
        return "/api/v1/" + ("TEAM".equals(type) ? "teams/" : "organizations/") + id + "/join-requests";
    }

    private record Scope(String type, Long id, String url) { }
}
