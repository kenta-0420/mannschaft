package com.mannschaft.app.committee;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.LeftTrigger;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260930-0234: CommitteeController#getCommittee の親組織所属を実 Security・MySQL で固定する。
 *
 * <p>親組織所属が ABSENT/LEFT なのに委員会所属または組織 ADMIN が残る12件が先行 RED 対象。
 * 通常の退会処理で不整合を作らず、過去・不整合行に対する読取り入口の防御を検査するため
 * フィクスチャで残存資格を明示する。実環境に残存行があるという主張ではない。</p>
 *
 * <p>残る20件は既存契約の対照。HIDDEN の404、SYSTEM_ADMIN単独の拒否、正規所属者の閲覧を維持する。
 * 認可・業務 Bean をモックせず、共通基底の外部依存設定だけを継承する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CommitteeDetailMembershipScopeContractIT extends AbstractMySqlIntegrationTest {

    enum ParentMembership { ABSENT, LEFT }
    enum ResidualAccess { COMMITTEE_MEMBER, ORG_ADMIN }
    enum ExistingDenial {
        OUTSIDER, OTHER_ORG_MEMBER, OTHER_ORG_ADMIN, LEFT_ONLY, SYSTEM_ADMIN,
        ORG_MEMBER_NON_COMMITTEE, LEFT_COMMITTEE_MEMBER
    }
    enum PermittedActor { CHAIR, MEMBER, ORG_ADMIN, ORG_DEPUTY_ADMIN, ORG_MEMBER, SYSTEM_AND_ORG_ADMIN }

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;
    private Long organizationId;
    private Long userId;
    private boolean systemAdminAuthority;

    @BeforeEach
    void setUp() {
        organizationId = organization();
        userId = user();
        systemAdminAuthority = false;
    }

    @ParameterizedTest(name = "{index}: 親組織{0}・残存{1}・{2}でも403")
    @MethodSource("orphanMembershipCases")
    void 詳細取得_親組織非所属は残存資格で所属確認を迂回できない(
            ParentMembership parent, ResidualAccess residual, CommitteeVisibility visibility) throws Exception {
        CommitteeEntity committee = committee(visibility);
        if (parent == ParentMembership.LEFT) {
            leftOrganizationMembership();
        }
        if (residual == ResidualAccess.COMMITTEE_MEMBER) {
            committeeMember(committee.getId(), CommitteeRole.MEMBER, false);
        } else {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
        }
        em.flush();
        assertNoActiveParentMembership();

        assertDenied(detail(userId, committee.getId()), 403, CommonErrorCode.COMMON_002.getCode());
    }

    static Stream<Arguments> orphanMembershipCases() {
        return Stream.of(ParentMembership.values()).flatMap(parent ->
                Stream.of(ResidualAccess.values()).flatMap(residual ->
                        Stream.of(CommitteeVisibility.values()).map(visibility ->
                                Arguments.of(parent, residual, visibility))));
    }

    @ParameterizedTest(name = "{index}: 既存拒否{0}・{1}は{2}")
    @MethodSource("existingDenialCases")
    void 詳細取得_既存の所属境界とHIDDEN秘匿を維持する(
            ExistingDenial actor, CommitteeVisibility visibility, int expectedStatus) throws Exception {
        CommitteeEntity committee = committee(visibility);
        switch (actor) {
            case OTHER_ORG_MEMBER -> organizationMembership(organization());
            case OTHER_ORG_ADMIN -> {
                Long otherOrg = organization();
                organizationMembership(otherOrg);
                MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, otherOrg);
            }
            case LEFT_ONLY -> leftOrganizationMembership();
            case SYSTEM_ADMIN -> platformAdmin();
            case ORG_MEMBER_NON_COMMITTEE -> organizationMembership(organizationId);
            case LEFT_COMMITTEE_MEMBER -> {
                organizationMembership(organizationId);
                committeeMember(committee.getId(), CommitteeRole.MEMBER, true);
            }
            case OUTSIDER -> { }
        }

        String expectedCode = expectedStatus == 404
                ? CommitteeErrorCode.NOT_FOUND.getCode() : CommonErrorCode.COMMON_002.getCode();
        assertDenied(detail(userId, committee.getId()), expectedStatus, expectedCode);
    }

    static Stream<Arguments> existingDenialCases() {
        return Stream.of(
                Arguments.of(ExistingDenial.OUTSIDER, CommitteeVisibility.NAME_ONLY, 403),
                Arguments.of(ExistingDenial.OTHER_ORG_MEMBER, CommitteeVisibility.NAME_AND_PURPOSE, 403),
                Arguments.of(ExistingDenial.OTHER_ORG_ADMIN, CommitteeVisibility.NAME_ONLY, 403),
                Arguments.of(ExistingDenial.LEFT_ONLY, CommitteeVisibility.NAME_ONLY, 403),
                Arguments.of(ExistingDenial.SYSTEM_ADMIN, CommitteeVisibility.NAME_ONLY, 403),
                Arguments.of(ExistingDenial.OUTSIDER, CommitteeVisibility.HIDDEN, 404),
                Arguments.of(ExistingDenial.SYSTEM_ADMIN, CommitteeVisibility.HIDDEN, 404),
                Arguments.of(ExistingDenial.ORG_MEMBER_NON_COMMITTEE, CommitteeVisibility.HIDDEN, 404),
                Arguments.of(ExistingDenial.LEFT_COMMITTEE_MEMBER, CommitteeVisibility.HIDDEN, 404));
    }

    @ParameterizedTest(name = "{index}: 正規所属{0}・{1}は200")
    @MethodSource("permittedCases")
    void 詳細取得_正規の委員と組織管理者と可視組織メンバーは閲覧できる(
            PermittedActor actor, CommitteeVisibility visibility) throws Exception {
        organizationMembership(organizationId);
        CommitteeEntity committee = committee(visibility);
        CommitteeRole expectedRole = null;
        switch (actor) {
            case CHAIR -> {
                expectedRole = CommitteeRole.CHAIR;
                committeeMember(committee.getId(), expectedRole, false);
            }
            case MEMBER -> {
                expectedRole = CommitteeRole.MEMBER;
                committeeMember(committee.getId(), expectedRole, false);
            }
            case ORG_ADMIN -> MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
            case ORG_DEPUTY_ADMIN ->
                    MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, organizationId);
            case SYSTEM_AND_ORG_ADMIN -> {
                platformAdmin();
                MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
            }
            case ORG_MEMBER -> { }
        }

        ResultActions response = detail(userId, committee.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(committee.getId()))
                .andExpect(jsonPath("$.data.organizationId").value(organizationId))
                .andExpect(jsonPath("$.data.name").value(committee.getName()))
                .andExpect(jsonPath("$.data.visibilityToOrg").value(visibility.name()))
                .andExpect(jsonPath("$.data.description").isEmpty())
                .andExpect(jsonPath("$.data.purposeTag").isEmpty());
        if (expectedRole == null) {
            response.andExpect(jsonPath("$.data.myRole").isEmpty());
        } else {
            response.andExpect(jsonPath("$.data.myRole").value(expectedRole.name()));
        }
    }

    static Stream<Arguments> permittedCases() {
        return Stream.of(
                Arguments.of(PermittedActor.CHAIR, CommitteeVisibility.HIDDEN),
                Arguments.of(PermittedActor.MEMBER, CommitteeVisibility.NAME_ONLY),
                Arguments.of(PermittedActor.ORG_ADMIN, CommitteeVisibility.HIDDEN),
                Arguments.of(PermittedActor.ORG_DEPUTY_ADMIN, CommitteeVisibility.NAME_AND_PURPOSE),
                Arguments.of(PermittedActor.ORG_MEMBER, CommitteeVisibility.NAME_ONLY),
                Arguments.of(PermittedActor.ORG_MEMBER, CommitteeVisibility.NAME_AND_PURPOSE),
                Arguments.of(PermittedActor.SYSTEM_AND_ORG_ADMIN, CommitteeVisibility.HIDDEN));
    }

    @ParameterizedTest(name = "{index}: 不在ID {0}は404")
    @ValueSource(longs = {0L, Long.MAX_VALUE})
    void 詳細取得_ゼロと不在IDは404を返す(long missingId) throws Exception {
        organizationMembership(organizationId);
        assertThat(em.find(CommitteeEntity.class, missingId)).isNull();

        assertDenied(detail(userId, missingId), 404, CommitteeErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void 詳細取得_論理削除済みは正規委員長でも404を返す() throws Exception {
        organizationMembership(organizationId);
        CommitteeEntity committee = committee(CommitteeVisibility.NAME_ONLY);
        committeeMember(committee.getId(), CommitteeRole.CHAIR, false);
        Long committeeId = committee.getId();
        committee.softDelete();
        assertThat(committee.getDeletedAt()).isNotNull();
        em.flush();
        em.clear();

        assertDenied(detail(userId, committeeId), 404, CommitteeErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void 詳細取得_未認証は実Securityで401を返す() throws Exception {
        CommitteeEntity committee = committee(CommitteeVisibility.NAME_ONLY);

        detail(null, committee.getId())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private ResultActions detail(Long actor, long committeeId) throws Exception {
        em.flush();
        em.clear();
        var request = get("/api/v1/committees/{committeeId}", committeeId).header("Accept-Language", "ja");
        if (actor != null) {
            List<GrantedAuthority> authorities = systemAdminAuthority
                    ? List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")) : List.of();
            request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null, authorities)));
        }
        return mockMvc.perform(request);
    }

    private void assertDenied(ResultActions response, int expectedStatus, String expectedCode) throws Exception {
        response.andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(expectedCode))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private MembershipEntity organizationMembership(Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, scopeId, RoleKind.MEMBER);
        return em.createQuery("""
                        SELECT m FROM MembershipEntity m
                        WHERE m.userId = :userId AND m.scopeType = :scopeType
                          AND m.scopeId = :scopeId AND m.leftAt IS NULL
                        """, MembershipEntity.class)
                .setParameter("userId", userId).setParameter("scopeType", ScopeType.ORGANIZATION)
                .setParameter("scopeId", scopeId).getSingleResult();
    }

    private void leftOrganizationMembership() {
        MembershipEntity membership = organizationMembership(organizationId);
        membership.setLeftAt(LocalDateTime.now().minusMinutes(1));
        membership.setLeaveReason(LeaveReason.SELF);
        membership.setLeftTrigger(LeftTrigger.SELF);
    }

    private void assertNoActiveParentMembership() {
        Long activeCount = em.createQuery("""
                        SELECT COUNT(m) FROM MembershipEntity m
                        WHERE m.userId = :userId AND m.scopeType = :scopeType
                          AND m.scopeId = :scopeId AND m.leftAt IS NULL
                        """, Long.class)
                .setParameter("userId", userId).setParameter("scopeType", ScopeType.ORGANIZATION)
                .setParameter("scopeId", organizationId).getSingleResult();
        assertThat(activeCount).as("残存資格を作っても親組織の現役所属は存在しない").isZero();
    }

    private void platformAdmin() {
        MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
        systemAdminAuthority = true;
    }

    private void committeeMember(Long committeeId, CommitteeRole role, boolean left) {
        CommitteeMemberEntity member = CommitteeMemberEntity.builder().committeeId(committeeId).userId(userId)
                .role(role).joinedAt(LocalDateTime.now()).build();
        if (left) {
            member.leave();
        }
        em.persist(member);
    }

    private CommitteeEntity committee(CommitteeVisibility visibility) {
        CommitteeEntity committee = CommitteeEntity.builder().organizationId(organizationId)
                .name("委員会詳細契約").status(CommitteeStatus.ACTIVE).visibilityToOrg(visibility)
                .createdBy(userId).build();
        em.persist(committee);
        return committee;
    }

    private Long user() {
        UserEntity user = UserEntity.builder().email(UUID.randomUUID() + "@committee-detail.example")
                .lastName("詳細").firstName("契約").displayName("詳細契約")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(user);
        return user.getId();
    }

    private Long organization() {
        OrganizationEntity organization = OrganizationEntity.builder().name("委員会詳細契約")
                .slug("cmt-" + UUID.randomUUID().toString().substring(0, 20))
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build();
        em.persist(organization);
        return organization.getId();
    }
}
