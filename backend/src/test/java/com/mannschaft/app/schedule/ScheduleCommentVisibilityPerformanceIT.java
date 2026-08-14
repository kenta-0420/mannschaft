package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.visibility.ScheduleCommentViewerFilter;
import com.mannschaft.app.schedule.visibility.ScheduleCommentVisibilityResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.16 認可・可視性層の非機能（SQL 発行数）統合テスト。
 *
 * <p>対応 AC: <b>AC-30</b>（{@code ScheduleCommentVisibilityResolver.filterAccessible} の SQL ≦2）
 * および <b>AC-39</b>（3 段方式の段1 が候補者数に比例しない）。</p>
 *
 * <h2>{@code ScheduleCommentPerformanceContractIT} と分けている理由</h2>
 * <p>あちらは HTTP エンドポイント経由（MockMvc）の計測で、Controller / Service が
 * 揃うまで赤のままである。本クラスは<b>認可・可視性コンポーネントを直接呼ぶ</b>ため、
 * Controller の有無に依存せず今の時点で構造を固定できる。AC-30 / AC-39 が要求している
 * のは「その部品の SQL 本数」であり、部品を直接測る方が対象を取り違えない。</p>
 *
 * <h2>計測方式</h2>
 * <p>設計書は「速いから OK」「ログの目視」を明示的に不可としている。
 * {@link Statistics#getPrepareStatementCount()} の差分で実測し、
 * <b>件数を変えた 2 回の測定値の一致</b>で固定する（AC-29 と同一方式）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 認可・可視性層の SQL 発行数（AC-30 / AC-39）")
class ScheduleCommentVisibilityPerformanceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleCommentVisibilityResolver resolver;

    @Autowired
    private ScheduleCommentViewerFilter viewerFilter;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long viewerId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 可視性性能", "scv-team-" + nonce);
        viewerId = ScheduleCommentTestFixtures.insertUser(em, "scv-viewer-" + nonce + "@example.com", "閲覧 者");
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-30: filterAccessible の SQL は 2 本以下、かつ件数に比例しない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-30 filterAccessibleUuid が自ら発行する SQL は 2 本以下で、コメント 5 件でも 20 件でも同数")
    void AC30_可視性判定のSQLは2本以下で件数に比例しない() {
        Long scheduleId = saveSchedule(MinViewRole.MEMBER_PLUS);
        List<UUID> ids = seedComments(scheduleId, 20);

        List<UUID> five = ids.subList(0, 5);
        List<UUID> twenty = ids;

        // ウォームアップ: 初回はメタデータ取得等の一過性 SQL が混ざるため、計測前に一度通す。
        resolver.filterAccessibleUuid(five, viewerId);

        Statistics stats = statisticsCleared();
        Set<UUID> visibleFive = resolver.filterAccessibleUuid(five, viewerId);
        long fiveCount = stats.getPrepareStatementCount();

        stats = statisticsCleared();
        Set<UUID> visibleTwenty = resolver.filterAccessibleUuid(twenty, viewerId);
        long twentyCount = stats.getPrepareStatementCount();

        // 正常系も検証する（常に空集合を返す実装でも本数だけなら緑になってしまう）。
        assertThat(visibleFive).hasSize(5);
        assertThat(visibleTwenty).hasSize(20);

        // 計測機構そのものの自己検証: 統計が有効化されていなければ全カウントが 0 になり、
        // 「一致」も「≦2」も vacuously true で通ってしまう（何も検査していない偽緑）。
        assertThat(fiveCount)
                .as("SQL 発行数が 0 本ということはありえない。Statistics が無効なら計測は無意味である")
                .isPositive();

        assertThat(twentyCount)
                .as("コメント5件で %d 本・20件で %d 本。比例するならコメント1件ずつ canView を呼ぶ "
                        + "N+1 に落ちている（親 scheduleId は重複排除して 1 回の判定に畳めるはず）",
                        fiveCount, twentyCount)
                .isEqualTo(fiveCount);

        // 内訳: SQL 1 = schedule_comments の射影、SQL 2 段 = 親 SCHEDULE への委譲 1 回。
        // 委譲先（ScheduleVisibilityResolver）が内部で発行する SQL も同一セッションで数えられるため、
        // 「本 Resolver 自身が上乗せする本数」を見るには親を直接測った値との差分を取る。
        stats = statisticsCleared();
        long parentOnly = measureParentOnly(scheduleId);
        long overhead = twentyCount - parentOnly;
        assertThat(overhead)
                .as("filterAccessibleUuid が親予定の判定に上乗せする SQL は射影取得の 1 本のみであるべき"
                        + "（実測: 全体 %d 本・親のみ %d 本）", twentyCount, parentOnly)
                .isLessThanOrEqualTo(2L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-39: 3 段方式の段1（ロール一括解決）は候補者数に比例しない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-39 候補 5 人と 20 人で、段1（スコープのロール一括解決）の SQL 発行数が同一")
    void AC39_ロール一括解決は候補者数に比例しない() {
        // 段1 のみを分離して測るため min_view_role=ADMIN_ONLY を使う。
        // 段2（メモリ上のロール足切り）で候補が全員落ちるので段3（canView）は 0 回になり、
        // 計測値は段1 の SQL 本数そのものになる。
        // これは同時に「段2 が段3 より前に効いている」ことの実証でもある
        // （順序が逆なら候補者数ぶんの canView が走り、本数が一致しない）。
        Long adminOnlySchedule = saveSchedule(MinViewRole.ADMIN_ONLY);
        List<Long> fiveCandidates = createMembers(5);
        List<Long> twentyCandidates = createMembers(20);
        ScheduleEntity schedule = scheduleRepository.findById(adminOnlySchedule).orElseThrow();

        viewerFilter.filterViewers(schedule, fiveCandidates); // ウォームアップ

        Statistics stats = statisticsCleared();
        Set<Long> fiveResult = viewerFilter.filterViewers(schedule, fiveCandidates);
        long fiveCount = stats.getPrepareStatementCount();

        stats = statisticsCleared();
        Set<Long> twentyResult = viewerFilter.filterViewers(schedule, twentyCandidates);
        long twentyCount = stats.getPrepareStatementCount();

        assertThat(fiveResult)
                .as("MEMBER は ADMIN_ONLY(=DEPUTY_ADMIN 以上) の閾値を満たさないので段2 で全員落ちる")
                .isEmpty();
        assertThat(twentyResult).isEmpty();

        assertThat(twentyCount)
                .as("候補5人で %d 本・20人で %d 本。比例して増えるなら «ループの都度ロール解決» に落ちている。"
                        + "スコープは固定なのだから候補集合の IN 句で一括解決できる（設計書 §4.5.0 段1）",
                        fiveCount, twentyCount)
                .isEqualTo(fiveCount);

        // 計測機構の自己検証: 0 本なら Statistics が効いておらず、上の「一致」判定は無意味。
        assertThat(twentyCount)
                .as("段1 は user_roles 1 本 + memberships 1 本の計 2 本ちょうどであるべき（実測 %d 本）。"
                        + "0 本なら Statistics が無効で何も検査できていない", twentyCount)
                .isBetween(1L, 2L);
    }

    @Test
    @DisplayName("AC-39 補強 段2 を通過した候補は段3 の canView で最終判定される（足切りは判定の代用ではない）")
    void AC39_段2通過後は段3のcanViewが最終判定する() {
        Long schedule = saveSchedule(MinViewRole.MEMBER_PLUS);
        List<Long> members = createMembers(3);
        // 同じチームに属さない（＝ F00 ラダーで不可視な）ユーザーを 2 人混ぜる。
        // 段2 のロール足切りだけでは落とせず、段3 の canView でのみ除外できる。
        List<Long> outsiders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            outsiders.add(ScheduleCommentTestFixtures.insertUser(
                    em, "scv-out-" + System.nanoTime() + "-" + i + "@example.com", "部外者" + i));
        }
        em.flush();
        em.clear();

        List<Long> candidates = new ArrayList<>(members);
        candidates.addAll(outsiders);

        ScheduleEntity entity = scheduleRepository.findById(schedule).orElseThrow();
        Set<Long> visible = viewerFilter.filterViewers(entity, candidates);

        assertThat(visible)
                .as("チーム MEMBER は閲覧できる")
                .containsAll(members);
        assertThat(visible)
                .as("非所属ユーザーは段3 の canView で除外されなければならない")
                .doesNotContainAnyElementsOf(outsiders);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 親 SCHEDULE 単体の判定に要する SQL 本数（本 Resolver の上乗せ分を差分で出すため）。 */
    private long measureParentOnly(Long scheduleId) {
        Statistics stats = statisticsCleared();
        resolverParentProbe(scheduleId);
        return stats.getPrepareStatementCount();
    }

    private void resolverParentProbe(Long scheduleId) {
        // 親 SCHEDULE の filterAccessible を 1 回だけ通す（コメント射影を挟まない）。
        contentVisibilityCheckerProbe(scheduleId);
    }

    @Autowired
    private com.mannschaft.app.common.visibility.ContentVisibilityChecker contentVisibilityChecker;

    private void contentVisibilityCheckerProbe(Long scheduleId) {
        contentVisibilityChecker.filterAccessible(
                com.mannschaft.app.common.visibility.ReferenceType.SCHEDULE,
                List.of(scheduleId), viewerId);
    }

    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sessionFactory = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private List<Long> createMembers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long id = ScheduleCommentTestFixtures.insertUser(
                    em, "scv-m-" + System.nanoTime() + "-" + i + "@example.com", "候補" + i);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            ids.add(id);
        }
        em.flush();
        em.clear();
        return ids;
    }

    private List<UUID> seedComments(Long scheduleId, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                    .scheduleId(scheduleId)
                    .userId(viewerId)
                    .body("コメント" + i)
                    .depth(0)
                    .replyCount(0)
                    .build());
            ids.add(saved.getId());
        }
        em.flush();
        em.clear();
        return ids;
    }

    private Long saveSchedule(MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("F0316 可視性性能検証")
                .startAt(LocalDateTime.of(2026, 9, 25, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 25, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(viewerId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }
}
