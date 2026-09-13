package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.common.visibility.perf.VisibilityCheckerPerformanceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-100: 実DBでアンカー取得と祖先解決のクエリ数を検証する試練。
 *
 * <p>起点数が増えても、アンカーと祖先の取得はそれぞれ一括取得で
 * 起点数に依存しない回数で完了する契約とする。</p>
 */
class OrganizationHierarchyServiceQueryCountIT extends VisibilityCheckerPerformanceTestBase {

    @Autowired
    private OrganizationHierarchyService service;

    @Test
    void anchor_and_ancestor_queries_are_bounded_independently_of_start_count() {
        List<Long> startTeamIds = LongStream.rangeClosed(900_001L, 900_016L).boxed().toList();
        List<Long> anchorIds = service.getAnchorOrgIdsByTeamIds(startTeamIds);
        service.getAncestorOrgIdsWithDepth(startTeamIds);

        assertThat(anchorIds).isEmpty();
        assertThat(SqlIntentCounter.intentCount("team_org_memberships"))
                .as("アンカー取得は起点数に比例せず、一括SELECT 1回以内であること")
                .isLessThanOrEqualTo(1);
        assertThat(SqlIntentCounter.intentCount("organizations"))
                .as("祖先取得は起点数に比例せず、一括SELECT 1回以内であること")
                .isLessThanOrEqualTo(1);
    }
}
