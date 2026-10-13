package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 6-C — 表示判定の SQL 本数（AC-G128。試練・red）。
 *
 * <p>グループ宛て告知が 1 件の組織と 20 件の組織で、チームダッシュボードが発行する SQL の本数が
 * 一致することを固定する（件数に比例して増える実装＝N+1 を検出する）。20 件の側はグループ数も多く、
 * 一部の告知は削除済みグループ（スナップショット判定）に当たる。計測は {@code getPrepareStatementCount}
 * （ウォームアップ後・stats.clear() 直後の絶対値）で、体感や SQL ログの目視ではない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-C 表示判定の SQL 本数")
class AnnouncementAudienceQueryCountIT extends AbstractAudienceDisplayIT {

    private static final Long ADMIN_SMALL = 940603021L;
    private static final Long ADMIN_LARGE = 940603022L;
    private static final Long VIEWER_SMALL = 940603023L;
    private static final Long VIEWER_LARGE = 940603024L;

    @Test
    @DisplayName("AC-G128: グループ宛て告知 1 件と 20 件（グループ 3 と 8）で、チームダッシュボードの SQL 本数が変わらない")
    void g128_sqlCountDoesNotGrowWithFeedsOrGroups() throws Exception {
        Long smallTeam = buildOrg(ADMIN_SMALL, VIEWER_SMALL, 3, 1);
        Long largeTeam = buildOrg(ADMIN_LARGE, VIEWER_LARGE, 8, 20);

        assertThat(teamNoticeIds(VIEWER_SMALL, smallTeam)).as("前提: 閲覧者の所属グループ宛ての告知が実際に表示される").isNotEmpty();
        assertThat(teamNoticeIds(VIEWER_LARGE, largeTeam)).as("前提: 閲覧者の所属グループ宛ての告知が実際に表示される").isNotEmpty();

        long small = countStatements(VIEWER_SMALL, smallTeam);
        long large = countStatements(VIEWER_LARGE, largeTeam);

        assertThat(large).as("1 件の組織: %d 本 / 20 件の組織: %d 本", small, large).isEqualTo(small);
    }

    /** グループ groupCount 件・告知 feedCount 件の組織を作り、閲覧者の所属チームの ID を返す。2 番目のグループは最後に削除する。 */
    private Long buildOrg(Long adminId, Long viewerId, int groupCount, int feedCount) throws Exception {
        OrganizationEntity org = newOrg(true);
        List<OrgTeamGroupEntity> groups = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            groups.add(newGroup(org.getId(), "G" + i, i));
        }
        Long viewerTeam = null;
        for (int i = 0; i < groupCount; i++) {
            Long teamId = activeTeam(org.getId(), groups.get(i).getId(), "計測チーム" + i).getId();
            if (i == 0) {
                viewerTeam = teamId;
            }
        }
        activeTeam(org.getId(), null, "計測未分類");
        seedOrgPerson(adminId, org.getId(), "ADMIN");
        seedTeamViewer(viewerId, org.getId(), viewerTeam);
        flushAndClear();

        for (int k = 0; k < feedCount; k++) {
            Map<String, Object> audience = switch (k % 3) {
                case 0 -> Map.of("targetGroupIds", ids(groups.get(0).getId(), groups.get(1).getId()));
                case 1 -> Map.of("targetGroupRange", range(groups.get(0).getId(), groups.get(1).getId()));
                default -> Map.of("targetGroupIds", ids(groups.get(0).getId()), "includeUnassigned", true);
            };
            sendAs(adminId, org.getId(), audience);
        }
        deleteGroup(groups.get(1).getId());
        return viewerTeam;
    }

    private long countStatements(Long viewer, Long teamId) {
        teamNoticeIds(viewer, teamId);
        flushAndClear();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        dashboardService.getTeamDashboard(viewer, teamId, "WEEK");
        return stats.getPrepareStatementCount();
    }
}
