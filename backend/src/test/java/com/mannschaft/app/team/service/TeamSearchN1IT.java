package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
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
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * チーム検索（{@link TeamService#searchTeams}）の N+1 解消を固定する試練（CMP-261103-1225 / #4270）。
 *
 * <ul>
 *   <li>AC-1: 検索ヒット数（1件／10件。ページサイズ20固定）を増やしても SQL 数が一定</li>
 *   <li>AC-2: 応答（DTO・件数・順序・ページ meta）が是正前と等価</li>
 *   <li>AC-3: 検索0件でも空 IN のエラーを出さない</li>
 *   <li>AC-7: 非公開・アーカイブ済みのチームは是正前と同じく検索に出ない</li>
 *   <li>AC-8: count に現れないチームは 0 として扱う</li>
 * </ul>
 *
 * <p>Statistics は SessionFactory 全体で共有されるため {@link Isolated}。</p>
 */
@Isolated
@Transactional
@DisplayName("チーム検索の N+1 解消（CMP-261103-1225）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TeamSearchN1IT extends AbstractMySqlIntegrationTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("id"));

    @Autowired
    private TeamService teamService;
    @Autowired
    private UserRepository userRepository;
    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("AC-1: 検索ヒット 1件と10件で SQL 数が同じ")
    void SQL数は検索ヒット数に依存しない() {
        String kwSmall = keyword();
        String kwLarge = keyword();
        Long friendPartner = insertTeam("相手", "PUBLIC", false);
        seedHits(kwSmall, 1, friendPartner);
        seedHits(kwLarge, 10, friendPartner);
        flushAndClear();

        long small = countSql(kwSmall, 1);
        long large = countSql(kwLarge, 10);

        System.out.println("CMP-261103-1225 searchTeams SQL small(1)=" + small + " large(10)=" + large);
        assertThat(large).as("ヒット 10 件でも 1 件と同じ SQL 数").isEqualTo(small);
    }

    @Test
    @DisplayName("AC-2/AC-7/AC-8: 件数・順序・各 count・ページ meta が是正前と同じ（非公開・アーカイブは出ない）")
    void 応答が是正前と同じ() {
        String kw = keyword();
        Long t1 = insertTeam(kw + "-1", "PUBLIC", false);
        Long t2 = insertTeam(kw + "-2", "PUBLIC", false);
        Long t3 = insertTeam(kw + "-3", "PUBLIC", false);           // 何も無い → すべて 0（AC-8）
        Long privateTeam = insertTeam(kw + "-p", "MEMBERS_AND_ABOVE", false);  // 検索に出ない
        Long archived = insertTeam(kw + "-a", "PUBLIC", true);       // 検索に出ない
        Long outsider = insertTeam("無関係" + kw.substring(3, 7), "PUBLIC", false);

        // t1: user_roles 3 行・フレンド 2（t2 と outsider）・SUPPORTER 2（離脱済み・MEMBER は数えない）
        for (int i = 0; i < 3; i++) {
            insertTeamRole(insertUser("t1m" + i), t1, i == 0 ? "ADMIN" : "MEMBER");
        }
        befriend(t1, t2);
        befriend(t1, outsider);
        insertMembership(insertUser("s1"), t1, "SUPPORTER", false);
        insertMembership(insertUser("s2"), t1, "SUPPORTER", false);
        insertMembership(insertUser("s3"), t1, "SUPPORTER", true);
        insertMembership(insertUser("mem"), t1, "MEMBER", false);
        // t2: user_roles 1・フレンド 2（t1 と privateTeam）・SUPPORTER 0
        insertTeamRole(insertUser("t2m"), t2, "MEMBER");
        befriend(t2, privateTeam);
        // 非公開・アーカイブ側にも数を持たせ、検索結果に漏れないことを見る
        insertTeamRole(insertUser("pm"), privateTeam, "MEMBER");
        insertMembership(insertUser("as"), archived, "SUPPORTER", false);
        flushAndClear();

        PagedResponse<TeamSummaryResponse> res = teamService.searchTeams(kw, PAGE);

        assertThat(res.getData()).extracting(r -> r.getSlug() + "|" + r.getName() + "|" + r.getVisibility()
                        + "|" + r.getMemberCount() + "|" + r.getTeamFriendCount() + "|" + r.getSupporterCount())
                .containsExactly(
                        slug(t1) + "|" + kw + "-1|PUBLIC|3|2|2",
                        slug(t2) + "|" + kw + "-2|PUBLIC|1|2|0",
                        slug(t3) + "|" + kw + "-3|PUBLIC|0|0|0");
        assertThat(res.getData()).allSatisfy(r -> assertThat(r.getId()).isEqualTo(r.getSlug()));
        assertThat(res.getMeta().getTotal()).isEqualTo(3);
        assertThat(res.getMeta().getPage()).isZero();
        assertThat(res.getMeta().getSize()).isEqualTo(20);
        assertThat(res.getMeta().getTotalPages()).isEqualTo(1);

        // 2 ページ目（範囲外）も是正前と同じく空・meta は同じ
        PagedResponse<TeamSummaryResponse> page2 = teamService.searchTeams(kw, PageRequest.of(1, 2, Sort.by("id")));
        assertThat(page2.getData()).extracting(TeamSummaryResponse::getSlug).containsExactly(slug(t3));
        assertThat(page2.getMeta().getTotal()).isEqualTo(3);
        assertThat(page2.getMeta().getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-3: 検索0件でも例外を出さない")
    void 検索0件() {
        PagedResponse<TeamSummaryResponse> res = teamService.searchTeams(keyword(), PAGE);
        assertThat(res.getData()).isEmpty();
        assertThat(res.getMeta().getTotal()).isZero();
    }

    // ------------------------------------------------------------------

    private long countSql(String kw, int expectedHits) {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = s.isStatisticsEnabled();
        try {
            s.setStatisticsEnabled(true);
            s.clear();
            PagedResponse<TeamSummaryResponse> res = teamService.searchTeams(kw, PAGE);
            assertThat(res.getData()).hasSize(expectedHits);
            return s.getPrepareStatementCount();
        } finally {
            s.setStatisticsEnabled(originallyEnabled);
            em.clear();
        }
    }

    /** 検索にヒットするチームを n 件作り、それぞれにメンバー・フレンド・応援者を 1 件ずつ持たせる。 */
    private void seedHits(String kw, int n, Long friendPartner) {
        for (int i = 0; i < n; i++) {
            Long team = insertTeam(kw + "-" + i, "PUBLIC", false);
            insertTeamRole(insertUser("h" + i), team, "MEMBER");
            insertMembership(insertUser("hs" + i), team, "SUPPORTER", false);
            befriend(team, friendPartner);
        }
    }

    private String keyword() {
        return "kw" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private Long insertUser(String label) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(UserEntity.builder()
                .email("ts-" + label + "-" + token + "@example.com")
                .lastName("姓")
                .firstName("名")
                .displayName("表示" + label + token)
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build()).getId();
    }

    private Long insertTeam(String name, String visibility, boolean archived) {
        String slug = "ts-" + UUID.randomUUID().toString().substring(0, 12);
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "archived_at, created_at, updated_at) "
                        + "VALUES (:name, :vis, 1, 0, 0, :slug, " + (archived ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("vis", visibility)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
    }

    private String slug(Long teamId) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", teamId).getSingleResult();
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

    private void insertMembership(Long userId, Long teamId, String roleKind, boolean left) {
        em.createNativeQuery("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                        + "left_at, leave_reason, created_at, updated_at) "
                        + "VALUES (:uid, 'TEAM', :sid, :rk, NOW() - INTERVAL 1 DAY, "
                        + (left ? "NOW(), 'SELF'" : "NULL, NULL") + ", NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("sid", teamId)
                .setParameter("rk", roleKind)
                .executeUpdate();
    }

    /** 相互フォローとフレンド関係（team_a_id &lt; team_b_id に正規化）を作る。 */
    private void befriend(Long x, Long y) {
        long a = Math.min(x, y);
        long b = Math.max(x, y);
        long ab = insertFollow(a, b);
        long ba = insertFollow(b, a);
        em.createNativeQuery("INSERT INTO team_friends (team_a_id, team_b_id, established_at, a_follow_id, "
                        + "b_follow_id, is_public, created_at, updated_at) "
                        + "VALUES (:a, :b, NOW(), :ab, :ba, 0, NOW(), NOW())")
                .setParameter("a", a)
                .setParameter("b", b)
                .setParameter("ab", ab)
                .setParameter("ba", ba)
                .executeUpdate();
    }

    private long insertFollow(long follower, long followed) {
        em.createNativeQuery("INSERT INTO follows (follower_type, follower_id, followed_type, followed_id, "
                        + "created_at) VALUES ('TEAM', :f, 'TEAM', :t, NOW())")
                .setParameter("f", follower)
                .setParameter("t", followed)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM follows WHERE follower_type = 'TEAM' "
                        + "AND follower_id = :f AND followed_type = 'TEAM' AND followed_id = :t")
                .setParameter("f", follower)
                .setParameter("t", followed)
                .getSingleResult()).longValue();
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
