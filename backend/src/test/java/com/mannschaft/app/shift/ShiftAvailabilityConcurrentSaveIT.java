package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261010-1432 欠陥 H — 勤務希望（曜日既定）一括保存の並行 PUT 契約テスト（試練）。
 *
 * <p>是正前の欠陥:</p>
 * <ul>
 *   <li>行が空の状態で同一ユーザー・同一チームへ 2 本の PUT が重なると、両方が「消す行なし」と見て
 *       INSERT し、一意キー違反（{@code DataIntegrityViolationException}）が未処理のまま 500 になる。</li>
 *   <li>既存行がある状態で重なると、後着の削除が 0 行になり Hibernate の楽観ロック例外 → 409 {@code COMMON_003}
 *       （シフト側の楽観ロック競合コード {@code SHIFT_018} と食い違う）。</li>
 *   <li>内容が異なる 2 本が重なると一意キーで検出できず、両方の行が混ざって残る（AC-H6）。</li>
 * </ul>
 *
 * <p><b>重なりの固定</b>: 「行が空」の並行は、両者が削除（＋flush）まで進んでから INSERT に入ったときに
 * 初めて事故になる。タイミング任せでは再現しないため、{@link MemberAvailabilityDefaultRepository#flush()}
 * を spy し、2 本ともが flush を終えた地点で {@link CyclicBarrier} により揃えてから INSERT へ進ませる。
 * 本クラスはテストメソッドをトランザクションで包まない（各リクエストが独立トランザクションで commit／rollback
 * することを観測するため）。フィクスチャは {@link AfterEach} で明示的に消す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261010-1432 H: 勤務希望一括保存の並行 PUT（試練）")
class ShiftAvailabilityConcurrentSaveIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/availability";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private MemberAvailabilityDefaultRepository availabilityRepository;

    private Long teamId;
    private Long otherTeamId;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        long nonce = System.nanoTime();
        teamId = insertTeam("H 並行保存IT チーム" + nonce);
        otherTeamId = insertTeam("H 並行保存IT 別チーム" + nonce);
        userId = insertUser("h-concurrent-" + nonce + "@example.com");
        otherUserId = insertUser("h-concurrent-other-" + nonce + "@example.com");
        insertMembership(userId, teamId);
        insertMembership(userId, otherTeamId);
        insertMembership(otherUserId, teamId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM member_availability_defaults WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
        jdbcTemplate.update("DELETE FROM teams WHERE id IN (?, ?)", teamId, otherTeamId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-H1 — 空の状態で同内容の並行 PUT
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-H1: 空の状態で同一ユーザー・同一チームへ同内容の PUT を2本重ねると、片方200・片方409 SHIFT_018で500にならず、行は1本分だけ")
    void AC_H1_空状態の並行PUTは片方200片方409で行は重複しない() throws Exception {
        List<Map<String, Object>> body = List.of(availability(1, "09:00", "17:00", "PREFERRED", "H1"));

        List<MockHttpServletResponse> responses = runOverlappedOnFlush(List.of(body, body));

        assertThat(statuses(responses)).as("片方200・片方409（500にしない）").containsExactlyInAnyOrder(200, 409);
        assertConflictCodeIsShift018(responses);
        assertThat(rowsOf(userId, teamId)).as("行は1本分だけ（重複しない）").containsExactly("1|09:00|17:00|H1");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-H6 — 空の状態で内容の異なる並行 PUT
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-H6: 空の状態から内容の異なる2本のPUTを並行に送っても、最終状態はどちらか一方の一括設定と完全に一致し、行が混ざらない")
    void AC_H6_内容の異なる並行PUTでも行が混ざらない() throws Exception {
        List<Map<String, Object>> bodyA = List.of(
                availability(1, "09:00", "12:00", "PREFERRED", "A"),
                availability(2, "09:00", "12:00", "AVAILABLE", "A"));
        List<Map<String, Object>> bodyB = List.of(
                availability(3, "13:00", "18:00", "WEAK_REST", "B"),
                availability(4, "13:00", "18:00", "STRONG_REST", "B"));

        List<MockHttpServletResponse> responses = runOverlappedOnFlush(List.of(bodyA, bodyB));

        assertThat(statuses(responses)).as("500 にしない").doesNotContain(500);
        assertConflictCodeIsShift018(responses);
        Set<String> rows = rowsOf(userId, teamId);
        Set<String> expectedA = Set.of("1|09:00|12:00|A", "2|09:00|12:00|A");
        Set<String> expectedB = Set.of("3|13:00|18:00|B", "4|13:00|18:00|B");
        assertThat(rows).as("最終状態は A か B の一括設定と完全一致（混ざらない）")
                .satisfiesAnyOf(
                        r -> assertThat(r).isEqualTo(expectedA),
                        r -> assertThat(r).isEqualTo(expectedB));
        // 200 を返した側の内容が残っていること（409 を返した側の内容が残っていない）
        int okIndex = statuses(responses).indexOf(200);
        assertThat(okIndex).as("少なくとも一方は成功する").isGreaterThanOrEqualTo(0);
        assertThat(rows).isEqualTo(okIndex == 0 ? expectedA : expectedB);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-H2 / AC-H3 — 既存行がある状態の並行 PUT
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-H2: 既存行がある状態の並行PUTは、競合しても409 SHIFT_018に揃い COMMON_003・500にならない")
    void AC_H2_既存行ありの並行PUTはSHIFT_018に揃う() throws Exception {
        seedExisting(userId, teamId, "OLD");

        List<List<Map<String, Object>>> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bodies.add(List.of(availability(i % 7, "08:00", "10:00", "AVAILABLE", "T" + i)));
        }
        List<MockHttpServletResponse> responses = runConcurrently(bodies);

        assertThat(statuses(responses)).as("200 か 409 のみ（500にしない）").isSubsetOf(200, 409).contains(200);
        assertConflictCodeIsShift018(responses);
        assertFinalStateIsExactlyOneSucceededBody(responses, bodies);
    }

    @Test
    @DisplayName("AC-H3: 409になった側のトランザクションはロールバックし、既存行（または勝者の行）が消えない")
    void AC_H3_409側はロールバックし既存行が消えない() throws Exception {
        seedExisting(userId, teamId, "OLD");

        List<List<Map<String, Object>>> bodies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bodies.add(List.of(
                    availability(i % 7, "08:00", "10:00", "AVAILABLE", "R" + i),
                    availability((i + 1) % 7, "10:00", "12:00", "PREFERRED", "R" + i)));
        }
        List<MockHttpServletResponse> responses = runConcurrently(bodies);

        assertThat(rowsOf(userId, teamId)).as("行が0件に消えていない").isNotEmpty();
        assertFinalStateIsExactlyOneSucceededBody(responses, bodies);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-H4 — 他ユーザー・他チームへの非干渉
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-H4: 並行PUTは他ユーザー（同チーム）・同ユーザーの他チームの行に影響しない")
    void AC_H4_他ユーザー他チームの行に影響しない() throws Exception {
        seedExisting(otherUserId, teamId, "OTHER_USER");
        seedExisting(userId, otherTeamId, "OTHER_TEAM");
        Set<String> otherUserBefore = rowsOf(otherUserId, teamId);
        Set<String> otherTeamBefore = rowsOf(userId, otherTeamId);

        List<Map<String, Object>> bodyA = List.of(availability(1, "09:00", "12:00", "PREFERRED", "A"));
        List<Map<String, Object>> bodyB = List.of(availability(2, "09:00", "12:00", "PREFERRED", "B"));
        runOverlappedOnFlush(List.of(bodyA, bodyB));

        assertThat(rowsOf(otherUserId, teamId)).as("同チームの他ユーザーの行は不変").isEqualTo(otherUserBefore);
        assertThat(rowsOf(userId, otherTeamId)).as("同ユーザーの他チームの行は不変").isEqualTo(otherTeamBefore);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 実行ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 各リクエストが {@code availabilityRepository.flush()} を終えた地点で全員を揃えてから先へ進ませる
     * （＝「削除を確定させた後・INSERT の前」で重なりを固定する）。
     */
    private List<MockHttpServletResponse> runOverlappedOnFlush(List<List<Map<String, Object>>> bodies)
            throws Exception {
        CyclicBarrier afterFlush = new CyclicBarrier(bodies.size());
        // Spring Data のリポジトリは JDK プロキシのため、spy の既定応答は「本物への委譲」（delegatesTo）。
        // callRealMethod はインタフェースの抽象メソッドを呼ぼうとして失敗するので、既定応答で本物へ委譲する。
        Answer<?> delegateToReal = mockingDetails(availabilityRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = delegateToReal.answer(invocation);
            afterFlush.await(15, TimeUnit.SECONDS);
            return result;
        }).when(availabilityRepository).flush();
        return runConcurrently(bodies);
    }

    private List<MockHttpServletResponse> runConcurrently(List<List<Map<String, Object>>> bodies) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(bodies.size());
        ExecutorService executor = Executors.newFixedThreadPool(bodies.size());
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (List<Map<String, Object>> body : bodies) {
                futures.add(executor.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
                    startLine.await(15, TimeUnit.SECONDS);
                    return mockMvc.perform(put(BASE)
                                    .param("teamId", teamId.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("availabilities", body))))
                            .andReturn().getResponse();
                }));
            }
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(90, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Integer> statuses(List<MockHttpServletResponse> responses) {
        return responses.stream().map(MockHttpServletResponse::getStatus).toList();
    }

    private static void assertConflictCodeIsShift018(List<MockHttpServletResponse> responses) throws Exception {
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 409) {
                String code = JsonPath.read(response.getContentAsString(), "$.error.code");
                assertThat(code).as("409 はシフトの楽観ロック競合コードに揃える（COMMON_003 にしない）")
                        .isEqualTo("SHIFT_018");
            }
        }
    }

    /** 最終状態が「200 を返したリクエストのいずれか 1 本」の一括設定と完全一致すること（混在・消失なし）。 */
    private void assertFinalStateIsExactlyOneSucceededBody(
            List<MockHttpServletResponse> responses, List<List<Map<String, Object>>> bodies) {
        Set<String> rows = rowsOf(userId, teamId);
        List<Set<String>> succeeded = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            if (responses.get(i).getStatus() == 200) {
                succeeded.add(expectedRows(bodies.get(i)));
            }
        }
        assertThat(succeeded).as("成功したリクエストのいずれかの内容と完全一致").contains(rows);
    }

    private static Set<String> expectedRows(List<Map<String, Object>> body) {
        Set<String> rows = new TreeSet<>();
        for (Map<String, Object> a : body) {
            rows.add(a.get("dayOfWeek") + "|" + LocalTime.parse((String) a.get("startTime")) + "|"
                    + LocalTime.parse((String) a.get("endTime")) + "|" + a.get("note"));
        }
        return rows;
    }

    /**
     * 行の読み出しはアプリと同じ JPA の時刻変換を通す（JDBC で TIME を生読みすると JVM／接続のタイムゾーン差で
     * 時刻がずれて見え、アプリが書いた内容と比べられないため）。
     */
    private Set<String> rowsOf(Long user, Long team) {
        Set<String> rows = new TreeSet<>();
        for (MemberAvailabilityDefaultEntity e
                : availabilityRepository.findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(user, team)) {
            rows.add(e.getDayOfWeek() + "|" + e.getStartTime() + "|" + e.getEndTime() + "|" + e.getNote());
        }
        return rows;
    }

    private void seedExisting(Long user, Long team, String note) {
        for (int dow = 0; dow < 3; dow++) {
            jdbcTemplate.update(
                    "INSERT INTO member_availability_defaults (user_id, team_id, day_of_week, start_time, end_time, "
                            + "preference, note, created_at, updated_at) "
                            + "VALUES (?, ?, ?, '07:00:00', '09:00:00', 'AVAILABLE', ?, NOW(), NOW())",
                    user, team, dow, note);
        }
    }

    private static Map<String, Object> availability(int dayOfWeek, String start, String end, String preference,
                                                    String note) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("dayOfWeek", dayOfWeek);
        a.put("startTime", start);
        a.put("endTime", end);
        a.put("preference", preference);
        a.put("note", note);
        return a;
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private Long insertTeam(String name) {
        jdbcTemplate.update(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (?, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())", name);
        return jdbcTemplate.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, name);
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'H', 'テスト', 'H テスト', 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void insertMembership(Long user, Long team) {
        // ShiftRequestSlotUniquenessConcurrencyIT と同じ理由で roles を冪等 seed する
        // （統合テスト環境は flyway.enabled=false のため seed が入らない）。
        Integer roleRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = 'MEMBER'", Integer.class);
        if (roleRows == null || roleRows == 0) {
            jdbcTemplate.update(
                    "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                            + "VALUES ('MEMBER', 'MEMBER', ?, 0, NOW(), NOW())",
                    RolePriority.priority("MEMBER"));
        }
        jdbcTemplate.update(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                        + "created_at, updated_at) VALUES (?, 'TEAM', ?, 'MEMBER', NOW(), NOW(), NOW())",
                user, team);
    }
}
