package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.dto.PublicActivitySitemapRow;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4 sitemap 収録クエリ（{@link ActivityResultRepository#findPublicForSitemap}
 * / {@link ActivityResultService#findPublicActivitiesForSitemap}）の結合テスト。
 *
 * <h2>このテストが守っているもの</h2>
 * <p>sitemap は<b>URL をこちらから検索エンジンへ差し出す</b>という点で、他の公開 API と
 * 性質が違う。単票 API なら「非公開なら 404 を返す」で守れるが、sitemap に載せてしまうと
 * <b>載せた時点で URL の存在自体が漏れる</b>（後から 404 を返しても手遅れ）。
 * したがって「載ってはいけないものが載らない」ことを機械的に固定する。</p>
 *
 * <p>載ってよいのは<b>次の 4 条件すべて</b>を満たすものだけ:</p>
 * <ol>
 *   <li>{@code visibility = PUBLIC}</li>
 *   <li>{@code status = PUBLISHED}</li>
 *   <li>論理削除されていない</li>
 *   <li><b>親スコープ（チーム / 組織）も公開である</b></li>
 * </ol>
 *
 * <p>セットアップ方針は {@code ActivityResultVisibilityProjectionRepositoryTest} を踏襲
 * （実 MySQL / Testcontainers・{@code @Transactional} ロールバック隔離）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4 sitemap 収録クエリ — 非公開が漏れないことの結合テスト")
class ActivityResultSitemapQueryTest extends AbstractMySqlIntegrationTest {

    /** 公開チーム。配下の公開記録は sitemap に載ってよい。 */
    private static final Long PUBLIC_TEAM_ID = 100L;
    /** 非公開チーム。配下は visibility=PUBLIC でも載せてはならない。 */
    private static final Long PRIVATE_TEAM_ID = 101L;
    /** 公開組織。 */
    private static final Long PUBLIC_ORG_ID = 500L;
    /** 非公開組織。 */
    private static final Long PRIVATE_ORG_ID = 501L;
    /** 委員会スコープ（公開ページを持たない）。 */
    private static final Long COMMITTEE_ID = 700L;

    @Autowired
    private ActivityResultRepository activityResultRepository;

    @Autowired
    private ActivityResultService activityResultService;

    @PersistenceContext
    private EntityManager em;

    /** 唯一「載ってよい」チーム配下の記録。 */
    private Long visibleTeamActivityId;
    /** 唯一「載ってよい」組織配下の記録。 */
    private Long visibleOrgActivityId;

    @BeforeEach
    void setUp() {
        // ── 載ってよいもの（公開スコープ配下・PUBLIC・PUBLISHED・未削除）
        visibleTeamActivityId = persist(ActivityScopeType.TEAM, PUBLIC_TEAM_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, false);
        visibleOrgActivityId = persist(ActivityScopeType.ORGANIZATION, PUBLIC_ORG_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, false);

        // ── 載ってはいけないもの（1 条件ずつ崩す）
        // (1) 可視性が PUBLIC でない
        persist(ActivityScopeType.TEAM, PUBLIC_TEAM_ID,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, false);
        // (2) 下書き（PUBLIC のまま公開されていない）
        persist(ActivityScopeType.TEAM, PUBLIC_TEAM_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.DRAFT, false);
        // (3) 論理削除済み
        persist(ActivityScopeType.TEAM, PUBLIC_TEAM_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, true);
        // (4) 親スコープが非公開（記録自身は文句なしの PUBLIC + PUBLISHED）
        persist(ActivityScopeType.TEAM, PRIVATE_TEAM_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, false);
        persist(ActivityScopeType.ORGANIZATION, PRIVATE_ORG_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, false);
        // (5) COMMITTEE スコープ（公開ページが存在しない）
        persist(ActivityScopeType.COMMITTEE, COMMITTEE_ID,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, false);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("公開スコープ配下の PUBLIC + PUBLISHED だけが返る（非公開・DRAFT・削除済み・親非公開はすべて落ちる）")
    void findPublicForSitemap_returnsOnlyFullyPublicRecords() {
        List<ActivityResultEntity> result = activityResultRepository.findPublicForSitemap(
                Set.of(PUBLIC_TEAM_ID), Set.of(PUBLIC_ORG_ID));

        assertThat(result)
                .extracting(ActivityResultEntity::getId)
                .containsExactlyInAnyOrder(visibleTeamActivityId, visibleOrgActivityId);
    }

    @Test
    @DisplayName("親が非公開なら、記録自身が PUBLIC + PUBLISHED でも sitemap に載らない")
    void findPublicForSitemap_parentPrivate_excluded() {
        // 非公開チーム / 組織を「公開スコープ」として渡さない＝本番の呼び出し方
        List<ActivityResultEntity> result = activityResultRepository.findPublicForSitemap(
                Set.of(PUBLIC_TEAM_ID), Set.of(PUBLIC_ORG_ID));

        assertThat(result)
                .extracting(ActivityResultEntity::getScopeId)
                .doesNotContain(PRIVATE_TEAM_ID, PRIVATE_ORG_ID, COMMITTEE_ID);
    }

    @Test
    @DisplayName("親を公開扱いにすると配下の記録が現れる（＝除外は親スコープ条件が効いた結果だと示す対照実験）")
    void findPublicForSitemap_parentBecomesPublic_recordAppears() {
        // 前テストで落ちていた PRIVATE_TEAM_ID 配下が、公開集合に入れた途端に現れることを示す。
        // これが無いと「そもそもクエリが何も返していないだけ」と区別できない。
        List<ActivityResultEntity> result = activityResultRepository.findPublicForSitemap(
                Set.of(PUBLIC_TEAM_ID, PRIVATE_TEAM_ID), Set.of(PUBLIC_ORG_ID));

        assertThat(result)
                .extracting(ActivityResultEntity::getScopeId)
                .contains(PRIVATE_TEAM_ID);
    }

    @Test
    @DisplayName("COMMITTEE スコープは ID 集合に入れても載らない（TEAM / ORGANIZATION のみを列挙する述語で fail-closed）")
    void findPublicForSitemap_committeeScope_neverIncluded() {
        // COMMITTEE_ID をチーム集合・組織集合の両方に混ぜても、scopeType が一致しないため出てこない。
        List<ActivityResultEntity> result = activityResultRepository.findPublicForSitemap(
                Set.of(PUBLIC_TEAM_ID, COMMITTEE_ID), Set.of(PUBLIC_ORG_ID, COMMITTEE_ID));

        assertThat(result)
                .extracting(ActivityResultEntity::getScopeType)
                .doesNotContain(ActivityScopeType.COMMITTEE);
    }

    @Test
    @DisplayName("Service 経由でも同じ結果になり、Entity ではなく JDK 型だけの行が返る")
    void findPublicActivitiesForSitemap_viaService_returnsRows() {
        List<PublicActivitySitemapRow> rows = activityResultService
                .findPublicActivitiesForSitemap(Set.of(PUBLIC_TEAM_ID), Set.of(PUBLIC_ORG_ID));

        assertThat(rows)
                .extracting(PublicActivitySitemapRow::activityId)
                .containsExactlyInAnyOrder(visibleTeamActivityId, visibleOrgActivityId);
    }

    @Test
    @DisplayName("公開組織が 0 件でも SQL エラーにならず、公開チーム分だけが返る（IN () 回避）")
    void findPublicActivitiesForSitemap_emptyOrgIds_doesNotBreakSql() {
        List<PublicActivitySitemapRow> rows = activityResultService
                .findPublicActivitiesForSitemap(Set.of(PUBLIC_TEAM_ID), Set.of());

        assertThat(rows)
                .extracting(PublicActivitySitemapRow::activityId)
                .containsExactly(visibleTeamActivityId);
    }

    @Test
    @DisplayName("公開スコープが 1 つも無ければ空リスト（SQL を撃たない）")
    void findPublicActivitiesForSitemap_noPublicScopes_returnsEmpty() {
        List<PublicActivitySitemapRow> rows = activityResultService
                .findPublicActivitiesForSitemap(Set.of(), Set.of());

        assertThat(rows).isEmpty();
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    /** 最小限の NOT NULL を満たして ActivityResultEntity を persist する。 */
    private Long persist(ActivityScopeType scopeType, Long scopeId,
                         ActivityVisibility visibility, ActivityStatus status, boolean softDeleted) {
        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(1L)
                .title("F06.4 sitemap test activity")
                .activityDate(LocalDate.of(2026, 5, 4))
                .fieldValues("{}")
                .visibility(visibility)
                .status(status)
                .createdBy(999L)
                .build();
        em.persist(entity);
        em.flush();
        if (softDeleted) {
            entity.softDelete();
            em.flush();
        }
        return entity.getId();
    }
}
