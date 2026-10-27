package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 委員会一覧の可視集合とページ総件数を、実 Security・Controller・Service・MySQL で固定する。
 * CommitteeController#listCommittees の red→green と、既存認可/status/nullable scalar の回帰を検証する。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CommitteeListVisibilityIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final int PAGE_SIZE = 20;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CommitteeService committeeService;
    @PersistenceContext private EntityManager em;
    private Long organizationId;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        organizationId = organization();
        userId = user();
        otherUserId = user();
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
    }

    @Test
    void 可視二種と本人現役HIDDENだけを返しDTO内nullableは保持する() throws Exception {
        Long nameOnly = committee(organizationId, CommitteeVisibility.NAME_ONLY, CommitteeStatus.DRAFT).getId();
        Long purpose = committee(organizationId, CommitteeVisibility.NAME_AND_PURPOSE, CommitteeStatus.ACTIVE).getId();
        CommitteeEntity own = committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(own.getId(), userId, false);
        committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);

        JsonNode page = page(userId, organizationId, 0, null);
        assertPage(page, List.of(nameOnly, purpose, own.getId()), 3);
        assertThat(page.path("content").get(0).path("memberCount").isNull()).isTrue();
        assertThat(page.path("content").get(0).path("myRole").isNull()).isTrue();
        assertThat(page.path("content").get(0).path("purposeTag").isNull()).isTrue();
        assertThat(page.path("content").get(2).path("memberCount").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"0", "21"})
    void 空または全件非所属HIDDENなら空配列と総件数ゼロ(int hiddenCount) throws Exception {
        for (int i = 0; i < hiddenCount; i++) {
            committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        }
        assertPage(page(userId, organizationId, 0, null), List.of(), 0);
    }

    @ParameterizedTest
    @CsvSource({"21,false", "21,true", "40,false", "41,true"})
    void 不可視行をページング前に除外し境界でも可視行を欠落させない(int visibleCount, boolean alternating)
            throws Exception {
        List<Long> visibleIds = new ArrayList<>();
        if (!alternating) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
            }
        }
        for (int i = 0; i < visibleCount; i++) {
            if (alternating && i < PAGE_SIZE) {
                committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
            }
            visibleIds.add(committee(organizationId, CommitteeVisibility.NAME_ONLY, CommitteeStatus.ACTIVE).getId());
        }
        int pages = (visibleCount + PAGE_SIZE - 1) / PAGE_SIZE;
        for (int index = 0; index <= pages; index++) {
            int start = Math.min(index * PAGE_SIZE, visibleCount);
            int end = Math.min(start + PAGE_SIZE, visibleCount);
            assertPage(page(userId, organizationId, index, null), visibleIds.subList(start, end), visibleCount);
        }
    }

    @ParameterizedTest
    @EnumSource(CommitteeStatus.class)
    void status指定は可視集合のcontentとcount双方へ適用する(CommitteeStatus selected) throws Exception {
        List<Long> allIds = new ArrayList<>();
        Long selectedId = null;
        for (CommitteeStatus value : CommitteeStatus.values()) {
            Long id = committee(organizationId, CommitteeVisibility.NAME_ONLY, value).getId();
            allIds.add(id);
            if (value == selected) {
                selectedId = id;
            }
            committee(organizationId, CommitteeVisibility.HIDDEN, value);
        }
        assertPage(page(userId, organizationId, 0, selected), List.of(selectedId), 1);
        assertPage(page(userId, organizationId, 0, null), allIds, allIds.size());
        // 満杯の1件ページでcount経路も実行し、status述語の漏れを検出する。
        var filtered = committeeService.listCommittees(organizationId, selected, userId,
                PageRequest.of(0, 1, Sort.by("id")));
        assertThat(filtered.getContent()).extracting(CommitteeEntity::getId).containsExactly(selectedId);
        assertThat(filtered.getTotalElements()).isEqualTo(1);
        Long emptyOrg = organization();
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, emptyOrg, RoleKind.MEMBER);
        assertPage(page(userId, emptyOrg, 0, selected), List.of(), 0);
    }

    @Test
    void 本人離脱と他人現役を除き履歴と重複現役があっても委員会は一件() throws Exception {
        CommitteeEntity left = committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(left.getId(), userId, true);
        CommitteeEntity other = committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(other.getId(), otherUserId, false);
        CommitteeEntity own = committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(own.getId(), userId, true);
        member(own.getId(), userId, false);
        Long otherOrg = organization();
        CommitteeEntity foreign = committee(otherOrg, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(foreign.getId(), userId, false);
        assertPage(page(userId, organizationId, 0, null), List.of(own.getId()), 1);
        // 重複現役でもEXISTSの可視母集団は1委員会1件。DTOの単一ロール取得とは別に検査する。
        member(own.getId(), userId, false);
        em.flush();
        var result = committeeService.listCommittees(organizationId, null, userId,
                PageRequest.of(0, 1, Sort.by("id")));
        assertThat(result.getContent()).extracting(CommitteeEntity::getId).containsExactly(own.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN,false", "DEPUTY_ADMIN,false", "ADMIN,true"})
    void 組織管理者は委員会非所属でもHIDDENを含む全件を表示する(String role, boolean systemAdmin) throws Exception {
        MembershipTestHelper.insertUserRole(em, userId, role, null, organizationId);
        if (systemAdmin) {
            MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
        }
        List<Long> ids = new ArrayList<>();
        for (CommitteeVisibility visibility : CommitteeVisibility.values()) {
            ids.add(committee(organizationId, visibility, CommitteeStatus.ARCHIVED).getId());
        }
        assertPage(page(userId, organizationId, 0, null), ids, ids.size());
        assertPage(page(userId, organizationId, 0, CommitteeStatus.ARCHIVED), ids, ids.size());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void 論理削除行は非管理者と管理者のcontentとcount双方から除外する(boolean admin) throws Exception {
        if (admin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
        }
        committee(organizationId, CommitteeVisibility.NAME_ONLY, CommitteeStatus.ACTIVE).softDelete();
        CommitteeEntity deletedHidden = committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        member(deletedHidden.getId(), userId, false);
        deletedHidden.softDelete();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE + 1; i++) {
            ids.add(committee(organizationId, CommitteeVisibility.NAME_ONLY, CommitteeStatus.ACTIVE).getId());
        }
        assertPage(page(userId, organizationId, 0, null), ids.subList(0, PAGE_SIZE), ids.size());
        assertPage(page(userId, organizationId, 1, null), ids.subList(PAGE_SIZE, ids.size()), ids.size());
        Long deletedOnlyOrg = organization();
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, deletedOnlyOrg, RoleKind.MEMBER);
        committee(deletedOnlyOrg, CommitteeVisibility.NAME_ONLY, CommitteeStatus.ACTIVE).softDelete();
        assertPage(page(userId, deletedOnlyOrg, 0, null), List.of(), 0);
    }

    @ParameterizedTest
    @CsvSource({"OUTSIDER", "OTHER_ADMIN", "OTHER_MEMBER", "LEFT", "SYSTEM_ADMIN"})
    void 組織非所属は委員会現役でも先行認可で403(String kind) throws Exception {
        Long outsider = otherUserId;
        Long otherOrg = organization();
        if ("OTHER_ADMIN".equals(kind)) {
            MembershipTestHelper.insertUserRole(em, outsider, "ADMIN", null, otherOrg);
        } else if ("OTHER_MEMBER".equals(kind) || "LEFT".equals(kind)) {
            MembershipTestHelper.insertMembership(em, outsider, ScopeType.ORGANIZATION,
                    "LEFT".equals(kind) ? organizationId : otherOrg, RoleKind.MEMBER);
            if ("LEFT".equals(kind)) {
                em.createQuery("UPDATE MembershipEntity m SET m.leftAt = :left WHERE m.userId = :userId")
                        .setParameter("left", LocalDateTime.now()).setParameter("userId", outsider).executeUpdate();
            }
        } else if ("SYSTEM_ADMIN".equals(kind)) {
            MembershipTestHelper.insertUserRole(em, outsider, "SYSTEM_ADMIN", null, null);
        }
        member(committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE).getId(), outsider, false);
        em.flush();
        mockMvc.perform(get("/api/v1/organizations/{orgId}/committees", organizationId)
                        .with(authentication(new UsernamePasswordAuthenticationToken(outsider, null, List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 未認証は実Securityで401() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/committees", organizationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 実ServiceのPageもnullマーカーを含まず可視総件数を返す() {
        CommitteeEntity visible = committee(organizationId, CommitteeVisibility.NAME_ONLY, CommitteeStatus.ACTIVE);
        committee(organizationId, CommitteeVisibility.HIDDEN, CommitteeStatus.ACTIVE);
        em.flush();
        var result = committeeService.listCommittees(organizationId, null, userId,
                PageRequest.of(0, PAGE_SIZE, Sort.by("id")));
        assertThat(result.getContent()).extracting(CommitteeEntity::getId).containsExactly(visible.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void 通常queryと同値createdAt指定でも全ページの集合を欠落させない(boolean admin, boolean createdAtSort)
            throws Exception {
        if (admin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
        }
        List<Long> expectedIds = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            boolean hidden = i < 20;
            Long id = committee(organizationId, hidden ? CommitteeVisibility.HIDDEN : CommitteeVisibility.NAME_ONLY,
                    CommitteeStatus.ACTIVE).getId();
            if (admin || !hidden) {
                expectedIds.add(id);
            }
        }
        em.flush();
        LocalDateTime sameTime = LocalDateTime.of(2026, 10, 1, 12, 0);
        // @PrePersist後に専用MySQL上へ直接設定し、clear後の実読値で同値を証明する。
        assertThat(em.createNativeQuery("UPDATE committees SET created_at = :createdAt WHERE organization_id = :orgId")
                .setParameter("createdAt", sameTime).setParameter("orgId", organizationId).executeUpdate()).isEqualTo(41);
        em.clear();
        assertThat(em.createQuery("SELECT c.createdAt FROM CommitteeEntity c WHERE c.organizationId = :orgId",
                        LocalDateTime.class).setParameter("orgId", organizationId).getResultList())
                .hasSize(41).containsOnly(sameTime);

        int pages = (expectedIds.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        List<Long> actualIds = new ArrayList<>();
        String[] sorts = createdAtSort ? new String[]{"createdAt,desc"} : new String[0];
        for (int index = 0; index < pages; index++) {
            int start = index * PAGE_SIZE;
            JsonNode result = page(userId, organizationId, index, null, sorts);
            assertPage(result, expectedIds.subList(start, Math.min(start + PAGE_SIZE, expectedIds.size())), expectedIds.size());
            result.path("content").forEach(item -> actualIds.add(item.path("id").asLong()));
        }
        assertThat(actualIds).doesNotHaveDuplicates().containsExactlyElementsOf(expectedIds);
        assertPage(page(userId, organizationId, 0, null, sorts), expectedIds.subList(0, PAGE_SIZE), expectedIds.size());
        assertPage(page(userId, organizationId, pages, null, sorts), List.of(), expectedIds.size());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void 実Serviceはsort未指定の空Pageにも一意ID順序を持たせる(boolean admin) {
        if (admin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, organizationId);
        }
        em.flush();
        var result = committeeService.listCommittees(organizationId, null, userId, PageRequest.of(0, PAGE_SIZE));
        assertThat(result.getSort()).as("SQLの偶然の順序ではなくIDによる安定順序を持つ")
                .isEqualTo(Sort.by("id"));
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    private JsonNode page(Long actor, Long orgId, int index, CommitteeStatus filter) throws Exception {
        return page(actor, orgId, index, filter, "id,asc");
    }

    private JsonNode page(Long actor, Long orgId, int index, CommitteeStatus filter, String... sorts) throws Exception {
        em.flush();
        var request = get("/api/v1/organizations/{orgId}/committees", orgId)
                .with(authentication(new UsernamePasswordAuthenticationToken(actor, null, List.of())))
                .param("page", Integer.toString(index)).param("size", Integer.toString(PAGE_SIZE));
        if (sorts.length > 0) {
            request.param("sort", sorts);
        }
        if (filter != null) {
            request.param("status", filter.name());
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode page = objectMapper.readTree(body).path("data");
        assertThat(page.path("number").asInt()).isEqualTo(index);
        assertThat(page.path("size").asInt()).isEqualTo(PAGE_SIZE);
        return page;
    }

    private void assertPage(JsonNode page, List<Long> expectedIds, int total) {
        assertThat(page.path("content").isArray()).isTrue();
        List<Long> actualIds = new ArrayList<>();
        page.path("content").forEach(item -> {
            assertThat(item.isNull()).as("配列要素nullを返さない").isFalse();
            actualIds.add(item.path("id").asLong());
        });
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        assertThat(page.path("totalElements").asLong()).isEqualTo(total);
        assertThat(page.path("totalPages").asInt()).isEqualTo((total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private Long user() {
        UserEntity user = UserEntity.builder().email(UUID.randomUUID() + "@committee-contract.example")
                .lastName("一覧").firstName("契約").displayName("一覧契約")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(user);
        return user.getId();
    }

    private Long organization() {
        OrganizationEntity organization = OrganizationEntity.builder().name("委員会一覧契約")
                .slug("cmt-" + UUID.randomUUID().toString().substring(0, 20))
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build();
        em.persist(organization);
        return organization.getId();
    }

    private CommitteeEntity committee(Long orgId, CommitteeVisibility visibility, CommitteeStatus committeeStatus) {
        CommitteeEntity committee = CommitteeEntity.builder().organizationId(orgId).name("一覧契約")
                .visibilityToOrg(visibility).status(committeeStatus).build();
        em.persist(committee);
        return committee;
    }

    private void member(Long committeeId, Long actor, boolean left) {
        CommitteeMemberEntity member = CommitteeMemberEntity.builder().committeeId(committeeId).userId(actor)
                .role(CommitteeRole.MEMBER).joinedAt(LocalDateTime.now()).build();
        if (left) {
            member.leave();
        }
        em.persist(member);
    }
}
