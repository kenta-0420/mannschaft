package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — 非機能（SQL 発行数）契約テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §4.5.0 / §10.1 / §9.4。
 * 対応 AC: AC-29・AC-39・AC-40。</p>
 *
 * <h2>計測方式を固定する理由</h2>
 * <p>設計書は<b>「速いから OK」「SQL ログの目視」を明示的に不可</b>としている。N+1 は件数が
 * 小さいうちは速く見えるため、体感や目視では検出できない。よって Hibernate の
 * {@link Statistics#getPrepareStatementCount()} を用い、<b>件数を変えた2回の測定値が一致すること</b>
 * を機械的に固定する（絶対値の上限だけでは「常に 5 本だが件数に比例して重い」実装を見逃す）。</p>
 *
 * <p>計測は {@code stats.clear()} 直後の絶対値で行う（既存 {@code MembershipBatchQueryServiceIntegrationTest}
 * と同じ作法）。{@code generate_statistics} は test プロファイルに無いため、テスト内で
 * {@code setStatisticsEnabled(true)} により有効化する。</p>
 *
 * <h2>AC-30 が本クラスに無い理由</h2>
 * <p>AC-30（{@code ScheduleCommentVisibilityResolver.filterAccessible} の SQL ≦2）は、
 * 試練の時点では対象クラスが存在せずテストを書けなかった。
 * <b>認可隊が {@code ScheduleCommentVisibilityPerformanceIT} として同一の計測方式で実装済み</b>。
 * 同クラスは Controller / Service を介さず可視性コンポーネントを直接呼ぶため、
 * 本クラス（HTTP 経由）とは独立に緑になる。AC-39 も同クラスに部品レベルの計測がある
 * （本クラスの AC-39 はエンドポイント経由の最終確認）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 非機能（SQL発行数）契約テスト（試練）")
class ScheduleCommentPerformanceContractIT extends AbstractMySqlIntegrationTest {

    private static final String COMMENTS = "/api/v1/schedules/{scheduleId}/comments";

    @Autowired
    private MockMvc mockMvc;

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
    private Long smallScheduleId;
    private Long largeScheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 性能", "scp-team-" + nonce);
        viewerId = ScheduleCommentTestFixtures.insertUser(em, "scp-viewer-" + nonce + "@example.com", "閲覧 者");
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();
        em.clear();

        smallScheduleId = saveSchedule("小");
        largeScheduleId = saveSchedule("大");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-29: 一覧の SQL は上限内、かつ件数に比例しない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-29 一覧の SQL は 9 本以下で、トップレベル 5 件のときと 20 件のときで発行数が一致する（N+1 でないことの実証）")
    void AC29_一覧のSQL本数は件数に比例しない() throws Exception {
        seedComments(smallScheduleId, 5, 3);
        seedComments(largeScheduleId, 20, 3);

        setAuthentication(viewerId);

        Statistics stats = statisticsCleared();
        mockMvc.perform(get(COMMENTS, smallScheduleId).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
        long smallCount = stats.getPrepareStatementCount();

        stats = statisticsCleared();
        mockMvc.perform(get(COMMENTS, largeScheduleId).param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(20));
        long largeCount = stats.getPrepareStatementCount();

        // 【殿の裁定 2026-08-11】上限は実測値（9本）＋わずかな余裕に改める。内訳は認可ゲートが
        // 呼ぶ F00 canView の固定コストが大半（設計書 §9.4 AC-29 参照）。
        // 「件数を変えた2回の値が一致する」検証（N+1 を捕まえる本体）は絶対に外さない。
        assertThat(largeCount)
                .as("トップレベル20件＋各3返信の一覧で SQL は 10 本以下でなければならない（§10.1・§9.4）")
                .isLessThanOrEqualTo(10L);
        assertThat(largeCount)
                .as("5件のとき %d 本・20件のとき %d 本。差があるなら投稿者・返信・可視性のいずれかが N+1 である",
                        smallCount, largeCount)
                .isEqualTo(smallCount);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-39 / AC-40: メンション候補の絞り込み
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-39【測定条件を是正・殿の裁定 2026-08-11】段1（ロール一括解決）は候補者数によらず定数。段3を切り離すため ADMIN_ONLY で候補者全員を段2で落とす")
    void AC39_段1のロール解決は候補者数に比例しない() throws Exception {
        // 直前版は min_view_role=MEMBER_PLUS（候補者全員が段3の canView まで進む）で HTTP
        // エンドポイント全体を測っており、段3のコスト（候補者数に比例してよい・設計どおり）が
        // 混入して「段1が定数」の検証になっていなかった。ADMIN_ONLY で段2にて全員を落とし、
        // ScheduleCommentVisibilityPerformanceIT の AC-39 と同一の分離手法に揃える。
        // ADMIN_ONLY（DEPUTY_ADMIN 以上）の予定を viewer 自身が閲覧・メンション候補取得できるよう、
        // 両チームで viewer に ADMIN ロールを付与する（MEMBER のままでは viewer 自身が 404 になる）。
        Long fiveTeamId = createTeamWithMembers("scp-five", 5);
        Long twentyTeamId = createTeamWithMembers("scp-twenty", 20);
        MembershipTestHelper.insertUserRole(em, viewerId, "ADMIN", fiveTeamId, null);
        MembershipTestHelper.insertUserRole(em, viewerId, "ADMIN", twentyTeamId, null);
        em.flush();
        em.clear();
        Long fiveScheduleId = saveScheduleInTeam(fiveTeamId, "候補5人", MinViewRole.ADMIN_ONLY);
        Long twentyScheduleId = saveScheduleInTeam(twentyTeamId, "候補20人", MinViewRole.ADMIN_ONLY);

        setAuthentication(viewerId);

        Statistics stats = statisticsCleared();
        mockMvc.perform(get(COMMENTS + "/mention-candidates", fiveScheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        long fiveCount = stats.getPrepareStatementCount();

        stats = statisticsCleared();
        mockMvc.perform(get(COMMENTS + "/mention-candidates", twentyScheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        long twentyCount = stats.getPrepareStatementCount();

        assertThat(twentyCount)
                .as("候補5人で %d 本・20人で %d 本。比例して増えるなら «ループの都度ロール解決» に落ちている。"
                        + "スコープは固定なのだから、ロール解決は候補集合の IN 句で一括して済ませられる",
                        fiveCount, twentyCount)
                .isEqualTo(fiveCount);
    }

    @Test
    @DisplayName("AC-39 補強【新設・殿の裁定】段3（canView）は候補者数に比例してよいが、候補者数を超えてはならない（二重ループの検出）")
    void AC39_段3は候補者数を超えない() throws Exception {
        // 段3は段2通過後の候補数に依存してよい設計（MEMBER_PLUS なら候補者全員が段3へ進む）。
        // ただし「候補者数を超える」SQLが出るなら二重ループ等の劣化である。
        Long fiveScheduleId = saveScheduleInTeam(createTeamWithMembers("scp-stage3", 5), "段3候補5人", MinViewRole.MEMBER_PLUS);

        setAuthentication(viewerId);
        Statistics stats = statisticsCleared();
        mockMvc.perform(get(COMMENTS + "/mention-candidates", fiveScheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5));
        long count = stats.getPrepareStatementCount();

        // 段1（定数・実測 ≦2）＋ 段3（候補数分＝5）を大きく超えない上限として、
        // 候補者数の5倍（段1固定分の余裕を含む）を二重ループ検出の上限とする。
        assertThat(count)
                .as("候補5人に対し %d 本の SQL。候補者数(5)の5倍を超えるなら二重ループ等の劣化が疑われる", count)
                .isLessThanOrEqualTo(5L * 5);
    }

    @Test
    @DisplayName("AC-40 母集団60人のうち10人が閲覧不可のとき size=50 は「可視な50人」を返す（先に切ってから除外する実装では40件になり失敗する）")
    void AC40_可視性フィルタはsizeで切る前に適用される() throws Exception {
        Long candidateTeamId = ScheduleCommentTestFixtures.insertTeam(
                em, "F0316 候補", "scp-cand-" + System.nanoTime());
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, candidateTeamId, RoleKind.MEMBER);
        // 50 人の MEMBER（閲覧可）と 10 人の SUPPORTER（min_view_role=MEMBER_PLUS では閲覧不可）。
        for (int i = 0; i < 50; i++) {
            Long id = ScheduleCommentTestFixtures.insertUser(
                    em, "scp-m-" + System.nanoTime() + "-" + i + "@example.com", "候補M" + i);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, candidateTeamId, RoleKind.MEMBER);
        }
        List<Long> invisible = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Long id = ScheduleCommentTestFixtures.insertUser(
                    em, "scp-s-" + System.nanoTime() + "-" + i + "@example.com", "候補S" + i);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, candidateTeamId, RoleKind.SUPPORTER);
            invisible.add(id);
        }
        em.flush();
        em.clear();
        Long scheduleId = saveScheduleInTeam(candidateTeamId, "候補60人");

        setAuthentication(viewerId);
        var result = mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(50));
        for (Long hidden : invisible) {
            result.andExpect(jsonPath("$.data[?(@.userId == " + hidden + ")]").isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 計測区間の直前で統計を有効化しリセットする。 */
    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sessionFactory = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private Long createTeamWithMembers(String slugPrefix, int memberCount) {
        Long newTeamId = ScheduleCommentTestFixtures.insertTeam(
                em, "F0316 " + slugPrefix, slugPrefix + "-" + System.nanoTime());
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, newTeamId, RoleKind.MEMBER);
        for (int i = 0; i < memberCount; i++) {
            Long id = ScheduleCommentTestFixtures.insertUser(
                    em, slugPrefix + "-" + System.nanoTime() + "-" + i + "@example.com", "候補" + i);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, newTeamId, RoleKind.MEMBER);
        }
        em.flush();
        em.clear();
        return newTeamId;
    }

    private void seedComments(Long targetScheduleId, int topLevelCount, int repliesPerTopLevel) {
        for (int i = 0; i < topLevelCount; i++) {
            ScheduleCommentEntity parent = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                    .scheduleId(targetScheduleId)
                    .userId(viewerId)
                    .body("トップレベル" + i)
                    .depth(0)
                    .replyCount(repliesPerTopLevel)
                    .build());
            UUID parentId = parent.getId();
            for (int j = 0; j < repliesPerTopLevel; j++) {
                scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                        .scheduleId(targetScheduleId)
                        .userId(viewerId)
                        .body("返信" + i + "-" + j)
                        .parentId(parentId)
                        .rootId(parentId)
                        .depth(1)
                        .build());
            }
        }
        em.flush();
        em.clear();
    }

    private Long saveSchedule(String label) {
        return saveScheduleInTeam(teamId, label);
    }

    private Long saveScheduleInTeam(Long targetTeamId, String label) {
        return saveScheduleInTeam(targetTeamId, label, MinViewRole.MEMBER_PLUS);
    }

    private Long saveScheduleInTeam(Long targetTeamId, String label, MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(targetTeamId)
                .title("F0316 性能検証予定 " + label)
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

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
