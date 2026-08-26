package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.1.1 / P3b Wave2: {@link MembershipRepository} のメンバー統計用 JPQL 番人テスト。
 *
 * <p>検証:</p>
 * <ul>
 *   <li>{@code countActiveDistinctUsersByScope}: active（left_at IS NULL）な DISTINCT user_id を数え、
 *       退会者・他スコープ・他 scope_type を除外する。</li>
 *   <li>{@code findActiveDistinctUserIdsByScope}: 同条件の DISTINCT user_id 集合を返す。</li>
 *   <li>{@code countActiveDistinctUsersByScopeAndJoinedAtBetween}: joined_at が半開区間 [from, to) の
 *       DISTINCT user_id を数える（範囲外・退会者を除外）。</li>
 * </ul>
 */
@Transactional
@DisplayName("MembershipRepository メンバー統計 JPQL 番人テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipStatsRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MembershipRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long TEAM_A = 2001L;
    private static final Long TEAM_B = 2002L;

    private void persist(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind,
                         LocalDateTime joinedAt, LocalDateTime leftAt) {
        MembershipEntity e = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(joinedAt)
                .leftAt(leftAt)
                .build();
        em.persist(e);
    }

    @Test
    @DisplayName("countActiveDistinctUsersByScope: active な DISTINCT user_id のみ・退会/他スコープ/他種別を除外")
    void countActiveDistinct() {
        LocalDateTime now = LocalDateTime.now();
        // TEAM_A の active メンバー 3 人（うち 1 人は同一 user の重複行＝DISTINCT で 1 とみなす）
        persist(101L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusMonths(2), null);
        persist(102L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusMonths(2), null);
        persist(103L, ScopeType.TEAM, TEAM_A, RoleKind.SUPPORTER, now.minusMonths(2), null);
        // 退会者（left_at あり）は除外
        persist(104L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusMonths(3), now.minusMonths(1));
        // 他チーム・他 scope_type は除外
        persist(105L, ScopeType.TEAM, TEAM_B, RoleKind.MEMBER, now.minusMonths(2), null);
        persist(106L, ScopeType.ORGANIZATION, TEAM_A, RoleKind.MEMBER, now.minusMonths(2), null);
        em.flush();
        em.clear();

        long count = repository.countActiveDistinctUsersByScope(ScopeType.TEAM, TEAM_A);
        assertThat(count).isEqualTo(3L);

        assertThat(repository.findActiveDistinctUserIdsByScope(ScopeType.TEAM, TEAM_A))
                .containsExactlyInAnyOrder(101L, 102L, 103L);
    }

    @Test
    @DisplayName("countActiveDistinctUsersByScopeAndJoinedAtBetween: joined_at が [from, to) の active のみ")
    void countNewThisMonth() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(10);
        LocalDateTime to = now.plusDays(1);

        // 範囲内 active 2 人
        persist(201L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusDays(5), null);
        persist(202L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusDays(1), null);
        // 範囲外（古い入会）→ 除外
        persist(203L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusMonths(3), null);
        // 範囲内だが退会済み → 除外
        persist(204L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, now.minusDays(3), now);
        // to は排他（upper exclusive）の確認: joined_at == to は除外
        persist(205L, ScopeType.TEAM, TEAM_A, RoleKind.MEMBER, to, null);
        em.flush();
        em.clear();

        long count = repository.countActiveDistinctUsersByScopeAndJoinedAtBetween(
                ScopeType.TEAM, TEAM_A, from, to);
        assertThat(count).isEqualTo(2L);
    }
}
