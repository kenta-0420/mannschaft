package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2785（乙層）: 組織配信の<b>母集団算出6本</b>が {@code memberships} 専属メンバーを
 * 取りこぼさないことを検証する結合テスト（実 MySQL / Testcontainers）。
 *
 * <p>{@code V60.010} で {@code MEMBER} / {@code SUPPORTER} の在籍行は {@code user_roles} から
 * {@code memberships} へ完全移行済みだが、配信母集団の 6 本は候補集合を {@code user_roles} からしか
 * 取っておらず、{@code memberships} にしか在籍行を持たない一般メンバーが母集団に入らない。
 * 本テストはその欠落を実データで固定する（テスト先行・red）。</p>
 *
 * <p>検証対象（いずれも {@link UserRoleRepository}）:</p>
 * <ul>
 *   <li>{@code findDistributionUserIdsForOrganizationRecursive}（一括）</li>
 *   <li>{@code findDistributionUserIdsForOrganizationRecursiveKeyset}（キーセット・fan-out 本番経路）</li>
 *   <li>{@code findDistributionUserIdsForOrganizationRecursiveKeysetSharded}（シャード）</li>
 *   <li>{@code countDistributionUserIdsForOrganizationRecursive}（母集団件数）</li>
 *   <li>{@code findDistributionMemberTeamPairsForOrganizationRecursive}（チーム別内訳）</li>
 *   <li>{@code findDistributionUserIdsForOrganization}（1 段版）</li>
 * </ul>
 *
 * <p>6 本は不可分である。1 本だけ直すと COUNT と実配信の母集団が食い違い、配信漏れ・重複に直結する。
 * そのため本テストの中核は「6 本が互いに完全同一の母集団を返す」ことの照合にある。</p>
 *
 * <p>スタブしたユニットテストでは 2 系統（{@code user_roles} / {@code memberships}）の非対称を
 * 再現できず green のまま本番だけ壊れるため、必ず実 DB で検証する。</p>
 */
@Transactional
@DisplayName("UserRoleRepository 配信母集団6本 × memberships 専属メンバー 結合テスト（#2785 乙層）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleDistributionAudienceMembershipsRepositoryTest extends AbstractMySqlIntegrationTest {

    private static final int MAX_DEPTH = 32;

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private Long memberRoleId;
    private Long adminRoleId;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("aud-org-" + n)
                .name("配信母集団テスト組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        return org.getId();
    }

    private Long persistActiveUser() {
        return persistUser(UserEntity.UserStatus.ACTIVE, false);
    }

    private Long persistUser(UserEntity.UserStatus status, boolean deleted) {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("aud-user-" + n + "@example.com")
                .lastName("配信")
                .firstName("母集団" + n)
                .displayName("配信母集団" + n)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        if (deleted) {
            user.softDelete();
        }
        return user.getId();
    }

    private Long persistRoleIfNeeded() {
        if (memberRoleId == null) {
            RoleEntity role = RoleEntity.builder()
                    .name("MEMBER")
                    .displayName("メンバー")
                    .priority(50)
                    .isSystem(true)
                    .build();
            em.persist(role);
            memberRoleId = role.getId();
        }
        return memberRoleId;
    }

    private Long persistAdminRoleIfNeeded() {
        if (adminRoleId == null) {
            RoleEntity role = RoleEntity.builder()
                    .name("ADMIN")
                    .displayName("管理者")
                    .priority(90)
                    .isSystem(true)
                    .build();
            em.persist(role);
            adminRoleId = role.getId();
        }
        return adminRoleId;
    }

    private void grantOrgRole(Long userId, Long organizationId) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded())
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void grantOrgAdminRole(Long userId, Long organizationId) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistAdminRoleIfNeeded())
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void grantTeamRole(Long userId, Long teamId) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded())
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void linkTeamToOrg(Long teamId, Long organizationId, TeamOrgMembershipEntity.Status status) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build();
        em.persist(tom);
    }

    private void addMembership(Long userId, ScopeType scopeType, Long scopeId,
                               RoleKind roleKind, LocalDateTime leftAt) {
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .leftAt(leftAt)
                .build();
        em.persist(ms);
    }

    /** {@code memberships} にしか在籍行を持たない一般メンバーを作る（本 Issue の主役）。 */
    private Long membershipOnlyMember(ScopeType scopeType, Long scopeId) {
        Long userId = persistActiveUser();
        addMembership(userId, scopeType, scopeId, RoleKind.MEMBER, null);
        return userId;
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    // ---------------------------------------------------------------------
    // 6 本の呼び出しヘルパー（母集団を Set<Long> に正規化する）
    // ---------------------------------------------------------------------

    private Set<Long> bulk(Long orgId, boolean includeSupporters, int maxDepth) {
        return new LinkedHashSet<>(
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(orgId, includeSupporters, maxDepth));
    }

    /** キーセット版を最後まで手繰り、返ってきた順序どおりの ID 列を返す（順序検証に使うため List）。 */
    private List<Long> keysetAll(Long orgId, boolean includeSupporters, int maxDepth, int chunk) {
        List<Long> acc = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<Long> page = com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper.userIdsOf(userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeyset(
                    orgId, includeSupporters, maxDepth, cursor, chunk, PageRequest.of(0, chunk)));
            if (page.isEmpty()) {
                return acc;
            }
            acc.addAll(page);
            cursor = page.get(page.size() - 1);
        }
    }

    /** 全シャードをそれぞれ最後まで手繰り、和集合を返す。 */
    private Set<Long> shardedAll(Long orgId, boolean includeSupporters, int maxDepth, int chunk, int shardCount) {
        Set<Long> acc = new LinkedHashSet<>();
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            long cursor = 0L;
            while (true) {
                List<Long> page = com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper.userIdsOf(userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
                        orgId, includeSupporters, maxDepth, cursor, chunk, shardIndex, shardCount,
                        PageRequest.of(0, chunk)));
                if (page.isEmpty()) {
                    break;
                }
                acc.addAll(page);
                cursor = page.get(page.size() - 1);
            }
        }
        return acc;
    }

    private Set<Long> teamPairUserIds(Long orgId, boolean includeSupporters, int maxDepth) {
        Set<Long> acc = new LinkedHashSet<>();
        for (Object[] row : userRoleRepository
                .findDistributionMemberTeamPairsForOrganizationRecursive(orgId, includeSupporters, maxDepth)) {
            acc.add(((Number) row[0]).longValue());
        }
        return acc;
    }

    /** {@code (user_id, team_id)} ペアを {@code Map<userId, List<teamId>>}（null 許容）へ畳み込む。 */
    private Map<Long, List<Long>> toUserTeamMap(List<Object[]> pairs) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (Object[] row : pairs) {
            Long userId = ((Number) row[0]).longValue();
            Long teamId = row[1] == null ? null : ((Number) row[1]).longValue();
            map.computeIfAbsent(userId, k -> new ArrayList<>()).add(teamId);
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // AC-11: 一括版が memberships 専属メンバーを含む
    // ---------------------------------------------------------------------

    /**
     * AC-11: {@code findDistributionUserIdsForOrganizationRecursive} が
     * 組織スコープ・配下チームスコープいずれの {@code memberships} 専属メンバーも母集団に含める。
     */
    @Test
    @DisplayName("AC-11: 一括版がmemberships専属メンバー（組織スコープ・配下チームスコープ）を母集団に含む")
    void ac11_一括版はmemberships専属メンバーを含む() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        Long leafTeam = 610_001L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // memberships にしか在籍行を持たない一般メンバー（user_roles 行なし）
        Long msOrgMember = membershipOnlyMember(ScopeType.ORGANIZATION, leafOrg);
        Long msTeamMember = membershipOnlyMember(ScopeType.TEAM, leafTeam);
        // 従来どおり user_roles に行を持つメンバー（対照）
        Long urMember = persistActiveUser();
        grantOrgRole(urMember, midOrg);
        flushClear();

        assertThat(bulk(rootOrg, false, MAX_DEPTH))
                .as("V60.010 以降、一般メンバーの在籍行は memberships にしかない")
                .contains(msOrgMember, msTeamMember, urMember);
    }

    // ---------------------------------------------------------------------
    // AC-12: 6 本の母集団が完全同一（本丸）
    // ---------------------------------------------------------------------

    /**
     * AC-12: 多段構成で keyset 版・シャード版・COUNT 版・チームペア版が
     * AC-11 の一括版と完全同一の母集団を返す（COUNT と実配信の食い違いは配信漏れ・重複に直結する）。
     */
    @Test
    @DisplayName("AC-12: keyset版・シャード版・COUNT版・チームペア版が一括版と完全同一の母集団を返す")
    void ac12_5本の母集団が一括版と完全一致する() {
        Long rootOrg = persistOrganization(null);
        Long midOrg = persistOrganization(rootOrg);
        Long leafOrg = persistOrganization(midOrg);

        Long midTeam = 610_010L;
        Long leafTeam = 610_011L;
        linkTeamToOrg(midTeam, midOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 2 系統を混在させる（memberships 専属 3 名 / user_roles 専属 3 名 / 両系統 1 名）
        Long ms1 = membershipOnlyMember(ScopeType.ORGANIZATION, rootOrg);
        Long ms2 = membershipOnlyMember(ScopeType.ORGANIZATION, leafOrg);
        Long ms3 = membershipOnlyMember(ScopeType.TEAM, leafTeam);

        Long ur1 = persistActiveUser();
        grantOrgRole(ur1, midOrg);
        Long ur2 = persistActiveUser();
        grantTeamRole(ur2, midTeam);
        Long ur3 = persistActiveUser();
        grantOrgRole(ur3, rootOrg);

        Long both = persistActiveUser();
        grantOrgRole(both, leafOrg);
        addMembership(both, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);
        flushClear();

        for (boolean includeSupporters : new boolean[]{false, true}) {
            Set<Long> expected = bulk(rootOrg, includeSupporters, MAX_DEPTH);

            // 6 本が「揃って取りこぼしている」状態で一致しても意味がないため、
            // 照合の基準そのものが memberships 専属メンバーを含むことを先に固定する。
            assertThat(expected)
                    .as("照合の基準となる母集団が memberships 専属メンバーを含むこと")
                    .contains(ms1, ms2, ms3, ur1, ur2, ur3, both);

            assertThat(keysetAll(rootOrg, includeSupporters, MAX_DEPTH, 3))
                    .as("keyset 版（fan-out 本番経路）の母集団が一括版と一致すること")
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(shardedAll(rootOrg, includeSupporters, MAX_DEPTH, 3, 3))
                    .as("シャード版の和が一括版と一致すること")
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(userRoleRepository
                    .countDistributionUserIdsForOrganizationRecursive(rootOrg, includeSupporters, MAX_DEPTH))
                    .as("COUNT 版はシャード数算出に使われるため、実配信母集団と厳密に一致する必要がある")
                    .isEqualTo(expected.size());
            assertThat(teamPairUserIds(rootOrg, includeSupporters, MAX_DEPTH))
                    .as("チームペア版の DISTINCT user_id が一括版と一致すること")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    /**
     * AC-12: 配下組織を持たない単一組織構成では 1 段版が再帰版（一括版）と完全同一の母集団を返す。
     *
     * <p>1 段版は配下組織を展開しないため、両者の差が「再帰展開の有無」だけになる構成で照合する。</p>
     */
    @Test
    @DisplayName("AC-12: 単一組織構成で1段版が一括版と完全同一の母集団を返す")
    void ac12_1段版が一括版と完全一致する() {
        Long org = persistOrganization(null);
        Long team = 610_020L;
        linkTeamToOrg(team, org, TeamOrgMembershipEntity.Status.ACTIVE);

        Long msOrg = membershipOnlyMember(ScopeType.ORGANIZATION, org);
        Long msTeam = membershipOnlyMember(ScopeType.TEAM, team);
        Long ur1 = persistActiveUser();
        grantOrgRole(ur1, org);
        Long ur2 = persistActiveUser();
        grantTeamRole(ur2, team);
        flushClear();

        for (boolean includeSupporters : new boolean[]{false, true}) {
            Set<Long> expected = bulk(org, includeSupporters, MAX_DEPTH);
            assertThat(expected)
                    .as("照合の基準となる母集団が memberships 専属メンバーを含むこと")
                    .contains(msOrg, msTeam, ur1, ur2);
            assertThat(userRoleRepository.findDistributionUserIdsForOrganization(org, includeSupporters))
                    .as("1 段版だけが memberships を見落とすと、非再帰経路の配信だけ静かに欠ける")
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // ---------------------------------------------------------------------
    // AC-13: keyset 版のページング健全性
    // ---------------------------------------------------------------------

    /**
     * AC-13: keyset 版が {@code user_id} 昇順・重複なし・欠落なしでページを返す。
     *
     * <p>{@code memberships} 専属と {@code user_roles} 専属を交互に作り、チャンクサイズより十分多い件数で
     * <b>ページ境界をまたがせる</b>。両系統を UNION した後にカーソル条件と ORDER BY が正しく効かないと、
     * 境界で行が飛ぶ・重複するという形で顕在化する。</p>
     */
    @Test
    @DisplayName("AC-13: keyset版がuser_id昇順・重複なし・欠落なしでページ境界をまたいで返す")
    void ac13_keyset版は昇順で重複も欠落もない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_030L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 2 系統を交互に 16 名（chunk=5 で 4 ページ以上に分かれる）
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 0; i < 8; i++) {
            expected.add(membershipOnlyMember(
                    i % 2 == 0 ? ScopeType.ORGANIZATION : ScopeType.TEAM,
                    i % 2 == 0 ? leafOrg : leafTeam));
            Long urOnly = persistActiveUser();
            if (i % 2 == 0) {
                grantOrgRole(urOnly, leafOrg);
            } else {
                grantTeamRole(urOnly, leafTeam);
            }
            expected.add(urOnly);
        }
        flushClear();

        List<Long> paged = keysetAll(rootOrg, false, MAX_DEPTH, 5);

        assertThat(paged).as("ページ境界で重複が出ていないこと").doesNotHaveDuplicates();
        assertThat(paged).as("ページ境界で行が飛んでいないこと").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(paged).as("keyset ページングは user_id 昇順が前提").isSorted();
        assertThat(paged).containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    /**
     * AC-13【枝内 LIMIT の番人】: 候補が<b>片方の枝に偏り</b>、その枝だけで chunk を大きく超える構成でも
     * keyset 版が欠落なく全件を返す。
     *
     * <p>各枝を {@code ORDER BY user_id ASC LIMIT :chunk} で打ち切る実装は、
     * 「和集合の先頭 k 件は必ずいずれかの枝の先頭 k 件に含まれる」ことに依拠している。
     * 偏りがあると枝の打ち切りが効く場面が増えるため、ここを踏まないと退行を見逃す。
     * {@code user_roles} 専属を連続 12 名、{@code memberships} 専属を末尾に 4 名置き、chunk=3 で手繰る。</p>
     */
    @Test
    @DisplayName("AC-13: 候補が片方の枝に偏りchunkを超えてもkeyset版は欠落なく全件返す")
    void ac13_枝に偏った候補でも欠落しない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_035L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Set<Long> expected = new LinkedHashSet<>();
        // user_roles 専属を連続 12 名（chunk=3 の 4 ページ分を 1 枝が占める）
        for (int i = 0; i < 12; i++) {
            Long urOnly = persistActiveUser();
            grantOrgRole(urOnly, leafOrg);
            expected.add(urOnly);
        }
        // memberships 専属を末尾に 4 名（user_id が後ろに来る）
        for (int i = 0; i < 4; i++) {
            expected.add(membershipOnlyMember(
                    i % 2 == 0 ? ScopeType.ORGANIZATION : ScopeType.TEAM,
                    i % 2 == 0 ? leafOrg : leafTeam));
        }
        flushClear();

        List<Long> paged = keysetAll(rootOrg, false, MAX_DEPTH, 3);

        assertThat(paged).as("偏った枝で打ち切っても重複しない").doesNotHaveDuplicates();
        assertThat(paged).as("偏った枝で打ち切っても行が飛ばない").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(paged).as("keyset ページングは user_id 昇順が前提").isSorted();
        assertThat(paged).containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    // ---------------------------------------------------------------------
    // AC-14: シャード版の全シャードの和
    // ---------------------------------------------------------------------

    /**
     * AC-14: シャード数 {@code N > 1} のとき全シャードの和が一括版の母集団と一致し、
     * シャード間で重複が生じない。
     */
    @Test
    @DisplayName("AC-14: シャード数4の全シャードの和が一括版と一致し_シャード間で重複しない")
    void ac14_全シャードの和が一括版と一致する() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_040L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Set<Long> membershipOnly = new LinkedHashSet<>();
        for (int i = 0; i < 6; i++) {
            membershipOnly.add(membershipOnlyMember(i % 2 == 0 ? ScopeType.ORGANIZATION : ScopeType.TEAM,
                    i % 2 == 0 ? leafOrg : leafTeam));
            Long urOnly = persistActiveUser();
            grantOrgRole(urOnly, leafOrg);
        }
        flushClear();

        final int shardCount = 4;
        Set<Long> expected = bulk(rootOrg, false, MAX_DEPTH);
        assertThat(expected)
                .as("シャード和の照合基準そのものが memberships 専属メンバーを含むこと")
                .containsAll(membershipOnly);

        List<Long> concatenated = new ArrayList<>();
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            long cursor = 0L;
            while (true) {
                List<Long> page = com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper.userIdsOf(userRoleRepository.findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
                        rootOrg, false, MAX_DEPTH, cursor, 3, shardIndex, shardCount, PageRequest.of(0, 3)));
                if (page.isEmpty()) {
                    break;
                }
                assertThat(page).as("各シャードも user_id 昇順で返る").isSorted();
                concatenated.addAll(page);
                cursor = page.get(page.size() - 1);
            }
        }

        assertThat(concatenated).as("シャードは互いに素な部分集合を担当する").doesNotHaveDuplicates();
        assertThat(concatenated).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(shardedAll(rootOrg, false, MAX_DEPTH, 3, shardCount))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---------------------------------------------------------------------
    // AC-15: includeSupporters トグル
    // ---------------------------------------------------------------------

    /**
     * AC-15: {@code memberships} 専属者に対しても includeSupporters トグルが正しく効く。
     *
     * <p>純 SUPPORTER は OFF で除外・ON で包含。MEMBER と SUPPORTER を兼ねる者は
     * MEMBER 優先で OFF でも母集団に含まれる。</p>
     */
    @Test
    @DisplayName("AC-15: memberships専属の純SUPPORTERはトグルOFFで除外_ONで包含_MEMBER兼務者はOFFでも包含")
    void ac15_memberships専属者にもトグルが効く() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_050L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // (a) memberships 専属の純 SUPPORTER
        Long pureSupporter = persistActiveUser();
        addMembership(pureSupporter, ScopeType.TEAM, leafTeam, RoleKind.SUPPORTER, null);

        // (b) memberships 専属で SUPPORTER と MEMBER を兼ねる（MEMBER 優先）
        Long mixed = persistActiveUser();
        addMembership(mixed, ScopeType.ORGANIZATION, leafOrg, RoleKind.SUPPORTER, null);
        addMembership(mixed, ScopeType.ORGANIZATION, rootOrg, RoleKind.MEMBER, null);

        // (c) memberships 専属の一般メンバー
        Long plain = membershipOnlyMember(ScopeType.ORGANIZATION, leafOrg);
        flushClear();

        Set<Long> off = bulk(rootOrg, false, MAX_DEPTH);
        assertThat(off).contains(mixed, plain);
        assertThat(off).as("トグル OFF では純 SUPPORTER を配信母集団から外す").doesNotContain(pureSupporter);

        Set<Long> on = bulk(rootOrg, true, MAX_DEPTH);
        assertThat(on).contains(pureSupporter, mixed, plain);

        // 6 本のうち残り 4 本（1 段版は多段構成のため対象外）でもトグル結果が一致すること
        for (boolean includeSupporters : new boolean[]{false, true}) {
            Set<Long> expected = bulk(rootOrg, includeSupporters, MAX_DEPTH);
            assertThat(keysetAll(rootOrg, includeSupporters, MAX_DEPTH, 3))
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(shardedAll(rootOrg, includeSupporters, MAX_DEPTH, 3, 2))
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(userRoleRepository
                    .countDistributionUserIdsForOrganizationRecursive(rootOrg, includeSupporters, MAX_DEPTH))
                    .isEqualTo(expected.size());
            assertThat(teamPairUserIds(rootOrg, includeSupporters, MAX_DEPTH))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    // ---------------------------------------------------------------------
    // AC-16 / AC-17: maxDepth 境界
    // ---------------------------------------------------------------------

    /**
     * AC-16 / AC-17: {@code memberships} 専属メンバーに対しても組織階層の {@code maxDepth} 打ち切りが
     * 従来どおり効く。深さちょうど {@code maxDepth} は含まれ（AC-17）、それを超える深さは含まれない（AC-16）。
     *
     * <p>根を depth=0 とし、{@code depth < maxDepth} を満たす間だけ子を展開する仕様に対応する。</p>
     */
    @Test
    @DisplayName("AC-16/AC-17: memberships専属メンバーも深さちょうどmaxDepthは含まれ_maxDepth超は含まれない")
    void ac16_ac17_maxDepth境界がmemberships専属者にも効く() {
        final int maxDepth = 2;
        Long depth0 = persistOrganization(null);
        Long depth1 = persistOrganization(depth0);
        Long depth2 = persistOrganization(depth1);
        Long depth3 = persistOrganization(depth2);

        Long atMaxDepth = membershipOnlyMember(ScopeType.ORGANIZATION, depth2);
        Long beyondMaxDepth = membershipOnlyMember(ScopeType.ORGANIZATION, depth3);

        // 配下チーム経由でも同じ境界が効くこと
        Long teamAtMaxDepth = 610_060L;
        Long teamBeyondMaxDepth = 610_061L;
        linkTeamToOrg(teamAtMaxDepth, depth2, TeamOrgMembershipEntity.Status.ACTIVE);
        linkTeamToOrg(teamBeyondMaxDepth, depth3, TeamOrgMembershipEntity.Status.ACTIVE);
        Long teamMemberAtMaxDepth = membershipOnlyMember(ScopeType.TEAM, teamAtMaxDepth);
        Long teamMemberBeyondMaxDepth = membershipOnlyMember(ScopeType.TEAM, teamBeyondMaxDepth);
        flushClear();

        Set<Long> audience = bulk(depth0, false, maxDepth);

        assertThat(audience)
                .as("AC-17: 深さちょうど maxDepth のメンバーは母集団に含まれる")
                .contains(atMaxDepth, teamMemberAtMaxDepth);
        assertThat(audience)
                .as("AC-16: maxDepth を超える深さのメンバーは母集団に含まれない")
                .doesNotContain(beyondMaxDepth, teamMemberBeyondMaxDepth);

        // 打ち切り位置が 6 本のうち残り 4 本でも一致すること（境界のドリフト防止）
        assertThat(keysetAll(depth0, false, maxDepth, 3)).containsExactlyInAnyOrderElementsOf(audience);
        assertThat(shardedAll(depth0, false, maxDepth, 3, 2)).containsExactlyInAnyOrderElementsOf(audience);
        assertThat(userRoleRepository.countDistributionUserIdsForOrganizationRecursive(depth0, false, maxDepth))
                .isEqualTo(audience.size());
        assertThat(teamPairUserIds(depth0, false, maxDepth)).containsExactlyInAnyOrderElementsOf(audience);
    }

    // ---------------------------------------------------------------------
    // 陽性対照
    // ---------------------------------------------------------------------

    /**
     * 【陽性対照】{@code user_roles} に ADMIN 行のみを持つ役職者が、6 本すべてで従来どおり母集団に入る。
     *
     * <p>候補集合を {@code memberships} へ広げる改修で {@code user_roles} 枝を痩せさせていないことの担保。</p>
     */
    @Test
    @DisplayName("陽性対照: user_rolesにADMIN行のみを持つ役職者が6本すべてで母集団に入る")
    void 陽性対照_userRoles専属のADMIN役職者は6本すべてで母集団に入る() {
        Long org = persistOrganization(null);

        Long admin = persistActiveUser();
        grantOrgAdminRole(admin, org);
        flushClear();

        assertThat(bulk(org, false, MAX_DEPTH)).contains(admin);
        assertThat(keysetAll(org, false, MAX_DEPTH, 5)).contains(admin);
        assertThat(shardedAll(org, false, MAX_DEPTH, 5, 2)).contains(admin);
        assertThat(userRoleRepository.countDistributionUserIdsForOrganizationRecursive(org, false, MAX_DEPTH))
                .isGreaterThanOrEqualTo(1L);
        assertThat(teamPairUserIds(org, false, MAX_DEPTH)).contains(admin);
        assertThat(userRoleRepository.findDistributionUserIdsForOrganization(org, false)).contains(admin);
    }

    /**
     * 【陽性対照】{@code user_roles} と {@code memberships} の両方に在籍行を持つ者が
     * 母集団に重複せず 1 件だけ現れる（候補集合の UNION が重複排除であることの担保）。
     */
    @Test
    @DisplayName("陽性対照: 両系統に在籍行を持つ者は母集団に重複せず1件だけ現れる")
    void 陽性対照_両系統保有者は重複しない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_070L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // 組織スコープ・チームスコープの双方で両系統に行を持つ
        Long bothOrg = persistActiveUser();
        grantOrgRole(bothOrg, leafOrg);
        addMembership(bothOrg, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);

        Long bothTeam = persistActiveUser();
        grantTeamRole(bothTeam, leafTeam);
        addMembership(bothTeam, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);
        flushClear();

        List<Long> bulkList =
                userRoleRepository.findDistributionUserIdsForOrganizationRecursive(rootOrg, false, MAX_DEPTH);
        assertThat(bulkList).doesNotHaveDuplicates().contains(bothOrg, bothTeam);
        assertThat(keysetAll(rootOrg, false, MAX_DEPTH, 3)).doesNotHaveDuplicates();
        assertThat(shardedAll(rootOrg, false, MAX_DEPTH, 3, 2)).hasSize(bulkList.size());
        assertThat(userRoleRepository.countDistributionUserIdsForOrganizationRecursive(rootOrg, false, MAX_DEPTH))
                .as("COUNT が二重計上すると自動シャード数が過大に見積もられる")
                .isEqualTo(bulkList.size());
    }

    // ---------------------------------------------------------------------
    // 境界: 除外条件が memberships 枝にも効く
    // ---------------------------------------------------------------------

    /**
     * 【境界】{@code left_at IS NOT NULL} の退会済 membership・論理削除済ユーザー・
     * {@code status != 'ACTIVE'} のユーザーは、6 本のいずれの母集団にも含まれない。
     *
     * <p>候補集合を {@code memberships} へ広げる改修で既存の除外条件を素通りさせないことの担保。</p>
     */
    @Test
    @DisplayName("境界: 退会済membership・論理削除ユーザー・非ACTIVEユーザーは母集団に含まれない")
    void 境界_退会済と非アクティブは母集団に含まれない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 610_080L;
        linkTeamToOrg(leafTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // (a) 退会済 membership（left_at あり）しか持たない
        Long leftMember = persistActiveUser();
        addMembership(leftMember, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));

        // (b) 在籍中 membership を持つが論理削除済ユーザー
        Long deletedUser = persistUser(UserEntity.UserStatus.ACTIVE, true);
        addMembership(deletedUser, ScopeType.ORGANIZATION, leafOrg, RoleKind.MEMBER, null);

        // (c) 在籍中 membership を持つが status != ACTIVE（凍結）のユーザー
        Long frozenUser = persistUser(UserEntity.UserStatus.FROZEN, false);
        addMembership(frozenUser, ScopeType.TEAM, leafTeam, RoleKind.MEMBER, null);

        // 対照: 在籍中かつ ACTIVE の memberships 専属メンバー
        Long alive = membershipOnlyMember(ScopeType.TEAM, leafTeam);
        flushClear();

        for (boolean includeSupporters : new boolean[]{false, true}) {
            Set<Long> audience = bulk(rootOrg, includeSupporters, MAX_DEPTH);
            assertThat(audience).contains(alive);
            assertThat(audience).doesNotContain(leftMember, deletedUser, frozenUser);

            assertThat(keysetAll(rootOrg, includeSupporters, MAX_DEPTH, 3))
                    .containsExactlyInAnyOrderElementsOf(audience);
            assertThat(shardedAll(rootOrg, includeSupporters, MAX_DEPTH, 3, 2))
                    .containsExactlyInAnyOrderElementsOf(audience);
            assertThat(userRoleRepository
                    .countDistributionUserIdsForOrganizationRecursive(rootOrg, includeSupporters, MAX_DEPTH))
                    .isEqualTo(audience.size());
            assertThat(teamPairUserIds(rootOrg, includeSupporters, MAX_DEPTH))
                    .containsExactlyInAnyOrderElementsOf(audience);
        }
    }

    /**
     * 【境界】{@code status != 'ACTIVE'} の参加チーム（未承認チーム）に {@code memberships} で
     * 所属するメンバーは母集団に含まれない。
     */
    @Test
    @DisplayName("境界: 未承認チーム(status!=ACTIVE)のmemberships専属メンバーは母集団に含まれない")
    void 境界_未承認チームのmemberships専属メンバーは含まれない() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long pendingTeam = 610_090L;
        Long activeTeam = 610_091L;
        linkTeamToOrg(pendingTeam, leafOrg, TeamOrgMembershipEntity.Status.PENDING);
        linkTeamToOrg(activeTeam, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        Long pendingTeamMember = membershipOnlyMember(ScopeType.TEAM, pendingTeam);
        Long activeTeamMember = membershipOnlyMember(ScopeType.TEAM, activeTeam);
        flushClear();

        Set<Long> audience = bulk(rootOrg, false, MAX_DEPTH);
        assertThat(audience).contains(activeTeamMember);
        assertThat(audience).doesNotContain(pendingTeamMember);
    }

    // ---------------------------------------------------------------------
    // チームペア版の (user_id, team_id) 紐づけ
    // ---------------------------------------------------------------------

    /**
     * AC-11 / AC-12: チームペア版が {@code memberships} 専属メンバーを正しいチームに紐づける。
     *
     * <p>配下チームスコープの memberships 専属メンバーは当該 {@code team_id} 行を、
     * 組織スコープの memberships 専属メンバーは {@code team_id = NULL} 行を返す。
     * 複数チーム所属なら所属チームごとに 1 行ずつ計上される。</p>
     */
    @Test
    @DisplayName("AC-11/AC-12: チームペア版がmemberships専属メンバーを正しいチーム（組織直属はnull枠）に紐づける")
    void ac11_ac12_チームペア版のmemberships専属メンバーのチーム紐づけ() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long teamA = 610_100L;
        Long teamB = 610_101L;
        linkTeamToOrg(teamA, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);
        linkTeamToOrg(teamB, leafOrg, TeamOrgMembershipEntity.Status.ACTIVE);

        // memberships 専属・単一チーム
        Long singleTeam = membershipOnlyMember(ScopeType.TEAM, teamA);
        // memberships 専属・複数チーム兼任
        Long multiTeam = persistActiveUser();
        addMembership(multiTeam, ScopeType.TEAM, teamA, RoleKind.MEMBER, null);
        addMembership(multiTeam, ScopeType.TEAM, teamB, RoleKind.MEMBER, null);
        // memberships 専属・組織直属（チーム未所属）
        Long orgDirect = membershipOnlyMember(ScopeType.ORGANIZATION, leafOrg);
        flushClear();

        Map<Long, List<Long>> map = toUserTeamMap(
                userRoleRepository.findDistributionMemberTeamPairsForOrganizationRecursive(rootOrg, false, MAX_DEPTH));

        assertThat(map.get(singleTeam))
                .as("配下チーム経由の一般メンバーは当該チームに計上される")
                .containsExactly(teamA);
        assertThat(map.get(multiTeam))
                .as("複数チーム所属は所属全チームに計上される（御裁可A）")
                .containsExactlyInAnyOrder(teamA, teamB);
        assertThat(map.get(orgDirect))
                .as("組織直属メンバーは team_id=null 枠で拾われる")
                .containsExactly((Long) null);
    }
}
