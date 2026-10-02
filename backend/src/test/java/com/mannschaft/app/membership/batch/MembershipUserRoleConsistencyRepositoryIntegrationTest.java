package com.mannschaft.app.membership.batch;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MembershipRepository#countOnlyInMemberships()} /
 * {@link UserRoleRepository#countOnlyInUserRoles()} / {@link UserRoleRepository#sampleOnlyInUserRoles} 結合テスト。
 *
 * <p>{@link MembershipConsistencyChecker} が全件ロード＋アプリ側集合演算から SQL 側の
 * {@code NOT EXISTS} 相関サブクエリへ載せ替えたことに伴い、JOIN 条件（TEAM/ORGANIZATION の
 * scope_type 分岐・DISTINCT）が実 DB 上で意図通り差分を検出できることを検証する
 * （モックでは JPQL/native SQL の正しさを検証できないため）。</p>
 */
@Transactional
@DisplayName("membership/user_roles 整合性差分クエリ 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipUserRoleConsistencyRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private MembershipEntity persistMembership(Long userId, ScopeType scopeType, Long scopeId, boolean active) {
        MembershipEntity.MembershipEntityBuilder<?, ?> builder = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .joinedAt(LocalDateTime.now().minusDays(30));
        if (!active) {
            builder.leftAt(LocalDateTime.now().minusDays(1));
        }
        return membershipRepository.save(builder.build());
    }

    private UserRoleEntity persistUserRoleTeam(Long userId, Long teamId) {
        return userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(1L)
                .teamId(teamId)
                .build());
    }

    private UserRoleEntity persistUserRoleOrg(Long userId, Long orgId) {
        return userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId)
                .roleId(1L)
                .organizationId(orgId)
                .build());
    }

    @Test
    @DisplayName("完全一致: 両方0件")
    void 完全一致_両差分0件() {
        persistMembership(1001L, ScopeType.TEAM, 9001L, true);
        persistUserRoleTeam(1001L, 9001L);

        assertThat(membershipRepository.countOnlyInMemberships()).isZero();
        assertThat(userRoleRepository.countOnlyInUserRoles()).isZero();
    }

    @Test
    @DisplayName("memberships のみに存在する行は countOnlyInMemberships に計上される")
    void memberships側のみ_countOnlyInMembershipsに計上() {
        persistMembership(1002L, ScopeType.ORGANIZATION, 9002L, true);
        // user_roles 側は何も無い

        assertThat(membershipRepository.countOnlyInMemberships()).isEqualTo(1L);
        assertThat(userRoleRepository.countOnlyInUserRoles()).isZero();
    }

    @Test
    @DisplayName("user_roles のみに存在する行は countOnlyInUserRoles に計上され、サンプルにも含まれる")
    void userRoles側のみ_countOnlyInUserRolesに計上() {
        persistUserRoleTeam(1003L, 9003L);
        // memberships 側は何も無い

        assertThat(membershipRepository.countOnlyInMemberships()).isZero();
        assertThat(userRoleRepository.countOnlyInUserRoles()).isEqualTo(1L);

        List<UserRoleRepository.OnlyInUserRolesRow> samples =
                userRoleRepository.sampleOnlyInUserRoles(PageRequest.of(0, 10));
        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).getUserId()).isEqualTo(1003L);
        assertThat(samples.get(0).getScopeType()).isEqualTo("TEAM");
        assertThat(samples.get(0).getScopeId()).isEqualTo(9003L);
    }

    @Test
    @DisplayName("left_at 設定済み（退会済み）の memberships 行はアクティブ扱いされず、user_roles 側のみ扱いになる")
    void 退会済みmembershipsは非アクティブ扱い() {
        persistMembership(1004L, ScopeType.TEAM, 9004L, false); // 退会済み
        persistUserRoleTeam(1004L, 9004L);

        assertThat(membershipRepository.countOnlyInMemberships()).isZero();
        assertThat(userRoleRepository.countOnlyInUserRoles()).isEqualTo(1L);
    }

    @Test
    @DisplayName("ORGANIZATION スコープの scope_type 分岐が正しく判定される（TEAM 行と混同しない）")
    void ORGANIZATIONスコープ判定() {
        // TEAM側で一致・ORGANIZATION側は user_roles のみ
        persistMembership(1005L, ScopeType.TEAM, 9005L, true);
        persistUserRoleTeam(1005L, 9005L);
        persistUserRoleOrg(1006L, 9006L);

        assertThat(membershipRepository.countOnlyInMemberships()).isZero();
        assertThat(userRoleRepository.countOnlyInUserRoles()).isEqualTo(1L);

        List<UserRoleRepository.OnlyInUserRolesRow> samples =
                userRoleRepository.sampleOnlyInUserRoles(PageRequest.of(0, 10));
        assertThat(samples).extracting(UserRoleRepository.OnlyInUserRolesRow::getScopeType)
                .containsExactly("ORGANIZATION");
    }

    @Test
    @DisplayName("サンプル取得は Pageable の pageSize で件数上限が掛かる")
    void サンプル取得は上限件数で打ち切られる() {
        for (long i = 0; i < 5; i++) {
            persistUserRoleTeam(2000L + i, 9100L + i);
        }

        assertThat(userRoleRepository.countOnlyInUserRoles()).isEqualTo(5L);
        List<UserRoleRepository.OnlyInUserRolesRow> samples =
                userRoleRepository.sampleOnlyInUserRoles(PageRequest.of(0, 3));
        assertThat(samples).hasSize(3);
    }
}
