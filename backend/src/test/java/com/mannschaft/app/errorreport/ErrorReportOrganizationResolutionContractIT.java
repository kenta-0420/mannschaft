package com.mannschaft.app.errorreport;

import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §9.2 #18（部隊 3-B）— エラーレポートに紐づける組織の解決順序を決定的にする契約テスト
 * （試練・先行 red）。
 *
 * <p>規則（設計書 §9.2 #18）: 直属組織（memberships の ORGANIZATION 在籍）を優先し、
 * 次にチーム経由の §9.3 代表親組織（{@code COALESCE(responded_at, created_at)} 昇順 → organization_id 昇順）。
 * 旧実装は UNION に ORDER BY が無い {@code LIMIT 1} で、複数親・複数所属があると実行のたびに結果が変わり得る。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-B ErrorReport 組織解決の決定性の契約")
class ErrorReportOrganizationResolutionContractIT extends AbstractMySqlIntegrationTest {

    private static final long TEAM_ONLY_USER = 3821L;
    private static final long DIRECT_AND_TEAM_USER = 3822L;
    private static final long TWO_DIRECT_USER = 3823L;

    @Autowired
    private ErrorReportRepository errorReportRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgX;
    private Long orgY;
    private Long orgZ;
    private Long teamT;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "3B ER組織X " + sfx, "er3b-x-" + sfx);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "3B ER組織Y " + sfx, "er3b-y-" + sfx);
        orgZ = TeamOrgFixtureHelper.insertOrganization(em, "3B ER組織Z " + sfx, "er3b-z-" + sfx);
        teamT = TeamOrgFixtureHelper.insertTeam(em, "3B ERチームT " + sfx, "er3b-t-" + sfx);

        // X を先に・Y を後に成立させる（PK は X が小さい。旧実装の任意の1件とは別の値で固定されることを検証する）。
        LocalDateTime base = LocalDateTime.of(2026, 4, 1, 9, 0);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgX, "ACTIVE", base.plusDays(1));
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgY, "ACTIVE", base);

        MembershipTestHelper.insertActiveUser(em, TEAM_ONLY_USER);
        MembershipTestHelper.insertMembership(em, TEAM_ONLY_USER, ScopeType.TEAM, teamT, RoleKind.MEMBER);

        MembershipTestHelper.insertActiveUser(em, DIRECT_AND_TEAM_USER);
        MembershipTestHelper.insertMembership(em, DIRECT_AND_TEAM_USER, ScopeType.TEAM, teamT, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, DIRECT_AND_TEAM_USER, ScopeType.ORGANIZATION, orgZ, RoleKind.MEMBER);

        MembershipTestHelper.insertActiveUser(em, TWO_DIRECT_USER);
        insertDirectMembership(TWO_DIRECT_USER, orgZ, LocalDateTime.of(2026, 3, 1, 9, 0));
        insertDirectMembership(TWO_DIRECT_USER, orgX, LocalDateTime.of(2026, 3, 1, 9, 0));
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("§9.2 #18 チーム経由のみなら §9.3 の代表親組織（responded_at が最も古い加盟）を返す（PK 順ではない）")
    void チーム経由は代表親組織() {
        // Y の responded_at が X より古い → 代表親組織は Y（PK は X が小さいので PK 順では X になってしまう）
        for (int i = 0; i < 10; i++) {
            em.clear();
            Optional<Long> resolved = errorReportRepository.findOrganizationIdByUserId(TEAM_ONLY_USER);
            assertThat(resolved).contains(orgY);
        }
    }

    @Test
    @DisplayName("§9.2 #18 直属組織があれば、チーム経由の代表親組織より優先する")
    void 直属組織が優先される() {
        for (int i = 0; i < 10; i++) {
            em.clear();
            assertThat(errorReportRepository.findOrganizationIdByUserId(DIRECT_AND_TEAM_USER)).contains(orgZ);
        }
    }

    @Test
    @DisplayName("§9.2 #18 直属組織が複数なら、在籍の最も古いもの→同時刻は organization_id 最小を返す")
    void 直属が複数なら決定的() {
        // Z・X とも joined_at が同時刻 → organization_id 最小（X。orgX < orgZ）
        assertThat(orgX).isLessThan(orgZ);
        for (int i = 0; i < 10; i++) {
            em.clear();
            assertThat(errorReportRepository.findOrganizationIdByUserId(TWO_DIRECT_USER)).contains(orgX);
        }
    }

    @Test
    @DisplayName("所属が無いユーザーは空（例外にならない）")
    void 所属なしは空() {
        assertThat(errorReportRepository.findOrganizationIdByUserId(999_999_001L)).isEmpty();
    }

    private void insertDirectMembership(Long userId, Long orgId, LocalDateTime joinedAt) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        em.createNativeQuery("UPDATE memberships SET joined_at = :j WHERE user_id = :u AND scope_type = "
                        + "'ORGANIZATION' AND scope_id = :s")
                .setParameter("j", joinedAt)
                .setParameter("u", userId)
                .setParameter("s", orgId)
                .executeUpdate();
    }
}
