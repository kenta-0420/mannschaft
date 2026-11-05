package com.mannschaft.app.organization.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.organization.dto.OrgAllMembersResponse;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 組織メンバー一覧（{@link OrganizationMembershipService#getMembers}）と配下全メンバー
 * （{@link OrganizationMembershipService#getAllMembers}）の N+1 解消を固定する試練（CMP-261103-1225 / #4270）。
 *
 * <ul>
 *   <li>AC-1: 組織メンバー総数（ページサイズ固定）・傘下チーム数を増やしても SQL 数が一定</li>
 *   <li>AC-2 / AC-14: 応答（DTO・順序・ページ meta）が是正前の全件実体化経路と等価
 *       （先頭・中間・末尾・範囲外。重複排除・totalElements を含む）</li>
 *   <li>AC-3: メンバー0人でも空 IN のエラーを出さない</li>
 *   <li>AC-4: 同一ユーザーが直属と複数の傘下チームに属しても例外を出さず、是正前と同じ行構成</li>
 *   <li>AC-5 / AC-6: 退会者（deleted_at）・論理削除済みチームの扱いが是正前と同じ</li>
 *   <li>AC-7: 他組織のメンバー・非 ACTIVE 加盟チームのメンバーが混入しない</li>
 * </ul>
 *
 * <p>SQL 数は Hibernate {@link Statistics#getPrepareStatementCount()} で数える（{@code ChartMyListN1IT} の型）。
 * Statistics は SessionFactory 全体で共有されるため {@link Isolated}。</p>
 */
@Isolated
@Transactional
@DisplayName("組織メンバー一覧・配下全メンバーの N+1 解消（CMP-261103-1225）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrganizationMembersN1IT extends AbstractMySqlIntegrationTest {

    private static final int PAGE_SIZE = 20;

    @Autowired
    private OrganizationMembershipService service;
    @Autowired
    private MemberQueryDispatcher memberQueryDispatcher;
    @Autowired
    private ScopeMemberCalendarSettingService scopeMemberCalendarSettingService;
    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager em;

    // ------------------------------------------------------------------
    // getMembers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: 組織メンバー総数を 5→45 に増やしても 1 ページ（20件）取得の SQL 数は同じ")
    void getMembers_SQL数はメンバー総数に依存しない() {
        Long smallOrg = insertOrganization();
        Long largeOrg = insertOrganization();
        seedOrgMembers(smallOrg, 5);
        seedOrgMembers(largeOrg, 45);
        flushAndClear();

        long small = countSql(() -> service.getMembers(smallOrg, PageRequest.of(0, PAGE_SIZE)));
        long large = countSql(() -> service.getMembers(largeOrg, PageRequest.of(0, PAGE_SIZE)));

        System.out.println("CMP-261103-1225 getMembers SQL small(5)=" + small + " large(45)=" + large);
        assertThat(large).as("メンバー 45 人でも 5 人と同じ SQL 数").isEqualTo(small);
    }

    @Test
    @DisplayName("AC-2/AC-14: 先頭・中間・末尾・範囲外のページが是正前の全件実体化と等価（重複排除・退会者・他組織除外を含む）")
    void getMembers_各ページが是正前と等価() {
        Long org = insertOrganization();
        Long otherOrg = insertOrganization();
        List<Long> seeded = seedOrgMembers(org, 45);
        // 直属 ADMIN かつ MEMBER（重複）: 1 行に集約され ADMIN になる
        Long both = insertUser("both");
        insertOrgRole(both, org, "ADMIN");
        insertMembership(both, "ORGANIZATION", org, "MEMBER", false);
        // 退会者（users.deleted_at）: 是正前と同じく行は残り displayName は null
        Long withdrawn = insertUser("withdrawn");
        insertMembership(withdrawn, "ORGANIZATION", org, "MEMBER", false);
        // 離脱済み（left_at）・他組織のメンバーは含まれない
        Long left = insertUser("left");
        insertMembership(left, "ORGANIZATION", org, "MEMBER", true);
        Long stranger = insertUser("stranger");
        insertMembership(stranger, "ORGANIZATION", otherOrg, "MEMBER", false);
        flushAndClear();
        softDeleteUser(withdrawn);
        flushAndClear();

        List<MemberResponse> oracle = legacyAllMembers(org);
        assertThat(oracle).as("前提: 45 + 重複1 + 退会者1").hasSize(47);

        for (int page = 0; page <= 3; page++) {
            PagedResponse<MemberResponse> actual = service.getMembers(org, PageRequest.of(page, PAGE_SIZE));
            int from = Math.min(page * PAGE_SIZE, oracle.size());
            int to = Math.min(from + PAGE_SIZE, oracle.size());
            assertThat(actual.getData())
                    .as("page=%d の中身と順序", page)
                    .usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(oracle.subList(from, to));
            assertThat(actual.getMeta().getTotal()).isEqualTo(47);
            assertThat(actual.getMeta().getPage()).isEqualTo(page);
            assertThat(actual.getMeta().getSize()).isEqualTo(PAGE_SIZE);
            assertThat(actual.getMeta().getTotalPages()).isEqualTo(3);
            flushAndClear();
        }

        List<MemberResponse> all = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            all.addAll(service.getMembers(org, PageRequest.of(page, PAGE_SIZE)).getData());
        }
        assertThat(all).extracting(MemberResponse::getUserId).doesNotHaveDuplicates()
                .contains(both, withdrawn).containsAll(seeded).doesNotContain(left, stranger);
        MemberResponse bothRow = all.stream().filter(m -> m.getUserId().equals(both)).findFirst().orElseThrow();
        assertThat(bothRow.getRoleName()).isEqualTo("ADMIN");
        MemberResponse withdrawnRow = all.stream().filter(m -> m.getUserId().equals(withdrawn)).findFirst().orElseThrow();
        assertThat(withdrawnRow.getDisplayName()).isNull();
        assertThat(withdrawnRow.getAvatarUrl()).isNull();

        // 非ページング（Pageable.unpaged）も是正前と同じ
        PagedResponse<MemberResponse> unpaged = service.getMembers(org, Pageable.unpaged());
        assertThat(unpaged.getData()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(oracle);
        assertThat(unpaged.getMeta().getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-3: メンバー0人の組織でも例外を出さず空ページを返す")
    void getMembers_メンバー0人() {
        Long org = insertOrganization();
        flushAndClear();

        PagedResponse<MemberResponse> res = service.getMembers(org, PageRequest.of(0, PAGE_SIZE));
        assertThat(res.getData()).isEmpty();
        assertThat(res.getMeta().getTotal()).isZero();
        assertThat(res.getMeta().getTotalPages()).isZero();

        PagedResponse<MemberResponse> unpaged = service.getMembers(org, Pageable.unpaged());
        assertThat(unpaged.getData()).isEmpty();
        assertThat(unpaged.getMeta().getTotalPages()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // getAllMembers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: 直属メンバー数・傘下チーム数を増やしても /members/all の SQL 数は同じ")
    void getAllMembers_SQL数は人数とチーム数に依存しない() {
        Long smallOrg = insertOrganization();
        Long largeOrg = insertOrganization();
        seedAllMembersFixture(smallOrg, 1, 1, 1);
        seedAllMembersFixture(largeOrg, 8, 6, 4);
        flushAndClear();

        long small = countSql(() -> service.getAllMembers(smallOrg, "INDIVIDUAL"));
        long large = countSql(() -> service.getAllMembers(largeOrg, "INDIVIDUAL"));

        System.out.println("CMP-261103-1225 getAllMembers SQL small(1直属,1チーム)=" + small
                + " large(8直属,6チーム×4)=" + large);
        assertThat(large).as("直属 8 人・傘下 6 チームでも 1 人・1 チームと同じ SQL 数").isEqualTo(small);
    }

    @Test
    @DisplayName("AC-2/AC-4/AC-5/AC-6/AC-7: /members/all の行構成・順序が是正前と同じ")
    void getAllMembers_行構成が是正前と同じ() {
        Long org = insertOrganization();
        Long otherOrg = insertOrganization();
        String orgName = orgName(org);

        Long direct1 = insertUser("d1");
        insertOrgRole(direct1, org, "ADMIN");
        Long shared = insertUser("shared");        // 直属かつ 2 チームに所属
        insertOrgRole(shared, org, "MEMBER");
        Long withdrawnDirect = insertUser("wd");   // 退会者は是正前と同じく除外
        insertOrgRole(withdrawnDirect, org, "MEMBER");

        Long teamA = insertTeam("PUBLIC", false);
        Long teamB = insertTeam("PUBLIC", false);
        Long deletedTeam = insertTeam("PUBLIC", false);   // 論理削除済み（@SQLRestriction）は是正前と同じく除外
        Long pendingTeam = insertTeam("PUBLIC", false);   // 加盟 PENDING は含まれない
        Long foreignTeam = insertTeam("PUBLIC", false);   // 他組織の加盟チームは含まれない
        linkTeam(teamA, org, "ACTIVE");
        linkTeam(teamB, org, "ACTIVE");
        linkTeam(deletedTeam, org, "ACTIVE");
        linkTeam(pendingTeam, org, "PENDING");
        linkTeam(foreignTeam, otherOrg, "ACTIVE");

        Long a1 = insertUser("a1");
        insertTeamRole(a1, teamA, "MEMBER");
        insertTeamRole(shared, teamA, "ADMIN");
        insertTeamRole(shared, teamB, "MEMBER");
        Long b1 = insertUser("b1");
        insertTeamRole(b1, teamB, "DEPUTY_ADMIN");
        Long withdrawnTeam = insertUser("wt");
        insertTeamRole(withdrawnTeam, teamB, "MEMBER");
        Long del1 = insertUser("del1");
        insertTeamRole(del1, deletedTeam, "MEMBER");
        Long pend1 = insertUser("pend1");
        insertTeamRole(pend1, pendingTeam, "MEMBER");
        Long foreign1 = insertUser("foreign1");
        insertTeamRole(foreign1, foreignTeam, "MEMBER");
        flushAndClear();
        softDeleteUser(withdrawnDirect);
        softDeleteUser(withdrawnTeam);
        softDeleteTeam(deletedTeam);
        flushAndClear();

        List<String> expected = List.of(
                row(direct1, "ORGANIZATION", org, orgName, "ADMIN"),
                row(shared, "ORGANIZATION", org, orgName, "MEMBER"),
                row(a1, "TEAM", teamA, teamName(teamA), "MEMBER"),
                row(shared, "TEAM", teamA, teamName(teamA), "ADMIN"),
                row(shared, "TEAM", teamB, teamName(teamB), "MEMBER"),
                row(b1, "TEAM", teamB, teamName(teamB), "DEPUTY_ADMIN")); // del1（論理削除済みチーム）は是正前と同じく出ない

        List<OrgAllMembersResponse> individual = service.getAllMembers(org, "INDIVIDUAL");
        assertThat(individual).extracting(this::row).containsExactlyElementsOf(expected);
        assertThat(individual).extracting(OrgAllMembersResponse::getUserId)
                .doesNotContain(withdrawnDirect, withdrawnTeam, del1, pend1, foreign1);
        flushAndClear();
        assertThat(service.getAllMembers(org, "ORGANIZATION")).extracting(this::row)
                .containsExactlyElementsOf(expected.subList(0, 2));
        flushAndClear();
        assertThat(service.getAllMembers(org, "TEAM")).extracting(this::row)
                .containsExactlyElementsOf(expected.subList(2, expected.size()));
        flushAndClear();
        // 表示名は 姓 + " " + 名（暗号化カラムの復号結果）
        assertThat(service.getAllMembers(org, "ORGANIZATION").get(0).getDisplayName()).isEqualTo("姓d1 名");
    }

    @Test
    @DisplayName("AC-3: 直属0人・傘下チーム0件・チームのメンバー0人でも例外を出さない")
    void getAllMembers_空() {
        Long emptyOrg = insertOrganization();
        Long orgWithEmptyTeam = insertOrganization();
        Long emptyTeam = insertTeam("PUBLIC", false);
        linkTeam(emptyTeam, orgWithEmptyTeam, "ACTIVE");
        flushAndClear();

        assertThat(service.getAllMembers(emptyOrg, "INDIVIDUAL")).isEmpty();
        assertThat(service.getAllMembers(orgWithEmptyTeam, "INDIVIDUAL")).isEmpty();
    }

    // ------------------------------------------------------------------
    // オラクル（是正前の全件実体化経路を、そのまま再現する）
    // ------------------------------------------------------------------

    /** 是正前の getMembers が組み立てていた全件リスト（queryMembers で全員を実体化し、全員ぶん色を解決）。 */
    private List<MemberResponse> legacyAllMembers(Long orgId) {
        List<MemberDto> dtos = memberQueryDispatcher.queryMembers(orgId, ScopeType.ORGANIZATION, null);
        Map<Long, String> colors = scopeMemberCalendarSettingService.resolveColors(
                ScopeType.ORGANIZATION, orgId, dtos.stream().map(MemberDto::userId).toList());
        List<MemberResponse> result = dtos.stream()
                .map(d -> new MemberResponse(d.userId(), d.displayName(), d.avatarUrl(), d.roleName(),
                        d.joinedAt(), colors.get(d.userId())))
                .toList();
        flushAndClear();
        return result;
    }

    // ------------------------------------------------------------------
    // フィクスチャ
    // ------------------------------------------------------------------

    /** 組織に n 人を所属させる。ロール経路（user_roles / memberships）と重複を混ぜる。 */
    private List<Long> seedOrgMembers(Long orgId, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long u = insertUser("m" + i);
            ids.add(u);
            if (i % 3 == 0) {
                insertOrgRole(u, orgId, "DEPUTY_ADMIN");
                if (i % 2 == 0) {
                    insertMembership(u, "ORGANIZATION", orgId, "MEMBER", false);
                }
            } else {
                insertMembership(u, "ORGANIZATION", orgId, i % 5 == 0 ? "SUPPORTER" : "MEMBER", false);
            }
        }
        return ids;
    }

    private void seedAllMembersFixture(Long orgId, int directMembers, int teams, int membersPerTeam) {
        for (int i = 0; i < directMembers; i++) {
            insertOrgRole(insertUser("dir" + i), orgId, i % 2 == 0 ? "ADMIN" : "MEMBER");
        }
        for (int t = 0; t < teams; t++) {
            Long team = insertTeam("PUBLIC", false);
            linkTeam(team, orgId, "ACTIVE");
            for (int m = 0; m < membersPerTeam; m++) {
                insertTeamRole(insertUser("t" + t + "m" + m), team, m % 2 == 0 ? "MEMBER" : "DEPUTY_ADMIN");
            }
        }
    }

    private long countSql(Supplier<?> call) {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = s.isStatisticsEnabled();
        try {
            s.setStatisticsEnabled(true);
            s.clear();
            call.get();
            return s.getPrepareStatementCount();
        } finally {
            s.setStatisticsEnabled(originallyEnabled);
            em.clear();
        }
    }

    private String row(OrgAllMembersResponse r) {
        return row(r.getUserId(), r.getMemberOf().getType(), r.getMemberOf().getId(),
                r.getMemberOf().getName(), r.getRole());
    }

    private String row(Long userId, String type, Long id, String name, String role) {
        return userId + "|" + type + "|" + id + "|" + name + "|" + role;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Long insertUser(String label) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(UserEntity.builder()
                .email("n1-" + label + "-" + token + "@example.com")
                .lastName("姓" + label)
                .firstName("名")
                .displayName("表示" + label + token)
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build()).getId();
    }

    private void softDeleteUser(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId).executeUpdate();
    }

    private Long insertOrganization() {
        String slug = "n1o-" + UUID.randomUUID().toString().substring(0, 12);
        em.createNativeQuery("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", "組織" + slug)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
    }

    private String orgName(Long orgId) {
        return (String) em.createNativeQuery("SELECT name FROM organizations WHERE id = :id")
                .setParameter("id", orgId).getSingleResult();
    }

    private Long insertTeam(String visibility, boolean archived) {
        String slug = "n1t-" + UUID.randomUUID().toString().substring(0, 12);
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "archived_at, created_at, updated_at) "
                        + "VALUES (:name, :vis, 1, 0, 0, :slug, " + (archived ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", "チーム" + slug)
                .setParameter("vis", visibility)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
    }

    private String teamName(Long teamId) {
        return (String) em.createNativeQuery("SELECT name FROM teams WHERE id = :id")
                .setParameter("id", teamId).getSingleResult();
    }

    private void softDeleteTeam(Long teamId) {
        em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", teamId).executeUpdate();
    }

    private void linkTeam(Long teamId, Long orgId, String status) {
        em.createNativeQuery("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, "
                        + "responded_at, created_at) VALUES (:tid, :oid, :status, NOW(), "
                        + ("ACTIVE".equals(status) ? "NOW()" : "NULL") + ", NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("status", status)
                .executeUpdate();
    }

    private void insertOrgRole(Long userId, Long orgId, String role) {
        ensureRole(role);
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id, organization_id, created_at, updated_at) "
                        + "SELECT :uid, r.id, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("oid", orgId)
                .setParameter("role", role)
                .executeUpdate();
    }

    private void insertTeamRole(Long userId, Long teamId, String role) {
        ensureRole(role);
        em.createNativeQuery("INSERT INTO user_roles (user_id, role_id, team_id, created_at, updated_at) "
                        + "SELECT :uid, r.id, :tid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .setParameter("role", role)
                .executeUpdate();
    }

    private void insertMembership(Long userId, String scopeType, Long scopeId, String roleKind, boolean left) {
        em.createNativeQuery("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                        + "left_at, leave_reason, created_at, updated_at) "
                        + "VALUES (:uid, :st, :sid, :rk, NOW() - INTERVAL 1 DAY, "
                        + (left ? "NOW(), 'SELF'" : "NULL, NULL") + ", NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("rk", roleKind)
                .executeUpdate();
    }

    /** roles はグローバル参照表。シードが無い環境でも同じ priority で用意する（既存なら再利用・冪等）。 */
    private void ensureRole(String role) {
        int priority = List.of("SYSTEM_ADMIN", "ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST").indexOf(role) + 1;
        Number exists = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", role).getSingleResult();
        if (exists.longValue() > 0) {
            return;
        }
        em.createNativeQuery("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                .setParameter("name", role)
                .setParameter("priority", priority)
                .executeUpdate();
    }
}
