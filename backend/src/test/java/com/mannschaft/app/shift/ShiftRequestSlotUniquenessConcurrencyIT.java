package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260909-1143 / 手動シフト作成 戦役A-3 — シフト希望の一意性を
 * <b>DB（生成列 ＋ UNIQUE）で担保する</b>ことの契約テスト（試練 / red 先行）。
 *
 * <p>設計: {@code docs/features/F03.5_shift/06_manual_authoring.md} §11.5.1.2、
 * 受け入れ条件 AC-8-07 / AC-8-08。</p>
 *
 * <p><b>なぜアプリ層の存在確認では足りないか</b>: 「存在チェック → INSERT」は原子的でなく、
 * 同一ユーザーからの 2 リクエストが同時に届けば両方ともチェックを通過して重複 INSERT できる。
 * 前例として {@code shift_budget_consumptions} が
 * {@code deleted_at_uq} 生成列 ＋ UNIQUE で同型の問題を解決している
 *（{@code V11.031__create_shift_budget_consumptions.sql}）。</p>
 *
 * <p><b>本クラスが {@code @Transactional} でない理由</b>: テストメソッドを 1 本の
 * トランザクションで包むと、別スレッドから未コミットのフィクスチャが見えず、
 * また DB の一意性制約違反そのものを観測できない。よってフィクスチャは commit させ、
 * {@link AfterEach} で明示的に後始末する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト希望の一意性（DB 制約）契約テスト（試練）")
class ShiftRequestSlotUniquenessConcurrencyIT extends AbstractMySqlIntegrationTest {

    /** 同時に投げるリクエスト数。1 本しか成功してはならない。 */
    private static final int CONCURRENCY = 8;

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 3, 2);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    private Long teamId;
    private Long userId;
    private Long scheduleId;
    private Long slotId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("A3 一意性IT チーム" + System.nanoTime());
        userId = insertUser("a3-uniqueness-" + System.nanoTime() + "@example.com");
        insertMembership(userId, teamId);
        scheduleId = insertCollectingSchedule(teamId);
        slotId = insertSlot(scheduleId, TARGET_DATE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM shift_requests WHERE schedule_id = ?", scheduleId);
        jdbcTemplate.update("DELETE FROM shift_slots WHERE schedule_id = ?", scheduleId);
        jdbcTemplate.update("DELETE FROM shift_schedules WHERE id = ?", scheduleId);
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8-07 — 同時 2 リクエストで必ず一方が 409
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-8-07 同一(scheduleId,userId,slotId)へ同時リクエストしても成功は1件だけで残りは409（500にしない）")
    void 同時リクエストでも成功は1件だけ() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(CONCURRENCY);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(executor.submit(() -> {
                    setAuth(userId);
                    startLine.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/v1/shifts/requests")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(
                                            requestBody(scheduleId, slotId, TARGET_DATE))))
                            .andReturn().getResponse().getStatus();
                }));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses)
                    .as("成功は必ず 1 件だけ（アプリ層の事前チェックは原子的でないため DB の UNIQUE が最後の砦）")
                    .filteredOn(status -> status == 201)
                    .hasSize(1);
            assertThat(statuses)
                    .as("残りは 409。制約違反を握りつぶして 500 にしてはならない")
                    .filteredOn(status -> status != 201)
                    .containsOnly(409);

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM shift_requests WHERE schedule_id = ? AND user_id = ? AND slot_id = ?",
                    Integer.class, scheduleId, userId, slotId);
            assertThat(rows).as("重複行が物理的に作られていない").isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8-08 — 生成列 ＋ UNIQUE による NULL / 非 NULL の区別
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-8-08 slot_id が NULL の行と非NULLの行は区別され、それぞれの単位で一意性が保たれる")
    void 生成列とUNIQUEでNULL行と非NULL行が区別される() {
        // 枠指定の希望と日単位の希望は同一日に併存できる（互いに一意性を潰さない）
        insertRequestRow(slotId, TARGET_DATE, "PREFERRED");
        assertThatCode(() -> insertRequestRow(null, TARGET_DATE, "WEAK_REST"))
                .as("枠指定（非NULL）と日単位（NULL）は別の一意性キーであり併存する")
                .doesNotThrowAnyException();

        // 日単位（slot_id IS NULL）の同一日 2 件目は DB が拒否する
        assertThatThrownBy(() -> insertRequestRow(null, TARGET_DATE, "STRONG_REST"))
                .as("生成列 slot_id_uq（COALESCE(slot_id, 0)）＋ UNIQUE により日単位の重複は DB が拒否する")
                .isInstanceOf(DataIntegrityViolationException.class);

        // 枠指定の同一 slot_id 2 件目も DB が拒否する
        assertThatThrownBy(() -> insertRequestRow(slotId, TARGET_DATE, "AVAILABLE"))
                .as("同一 slot_id の重複も DB が拒否する")
                .isInstanceOf(DataIntegrityViolationException.class);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shift_requests WHERE schedule_id = ? AND user_id = ?",
                Integer.class, scheduleId, userId);
        assertThat(rows).as("残るのは枠指定 1 件と日単位 1 件の計 2 件").isEqualTo(2);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void insertRequestRow(Long slotIdOrNull, LocalDate slotDate, String preference) {
        jdbcTemplate.update(
                "INSERT INTO shift_requests (schedule_id, user_id, slot_id, slot_date, preference, note, "
                        + "submitted_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                scheduleId, userId, slotIdOrNull, java.sql.Date.valueOf(slotDate), preference, "A3 試練");
    }

    private Map<String, Object> requestBody(Long schedule, Long slot, LocalDate slotDate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", schedule);
        body.put("slotId", slot);
        body.put("slotDate", slotDate.toString());
        body.put("preference", "PREFERRED");
        body.put("note", "A3 試練");
        return body;
    }

    private void setAuth(Long id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
    }

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
                        + "VALUES (?, 'A3', 'テスト', 'A3 テスト', 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void insertMembership(Long user, Long team) {
        // MembershipTestHelper と同じ理由で roles を冪等 seed する（統合テスト環境は
        // flyway.enabled=false のため V2.014 の seed が入らず、priority 解決に失敗して
        // memberships のみのメンバーが権限判定で落ちる）。priority は正準表から採る。
        // 本 IT は非トランザクションのため EntityManager ではなく JdbcTemplate で行う。
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

    // シフト表・枠は列の既定値ドリフト（NOT NULL 列の追加）で生 SQL が壊れるため
    // Repository 経由で作る。本 IT は非トランザクションなので save はそのまま commit される。
    private Long insertCollectingSchedule(Long team) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(team)
                .title("A3 一意性IT")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.of(2099, 1, 1, 0, 0))
                .createdBy(userId)
                .build()).getId();
    }

    private Long insertSlot(Long schedule, LocalDate slotDate) {
        return slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(schedule)
                .slotDate(slotDate)
                .startTime(LocalTime.of(12, 0))
                .endTime(LocalTime.of(15, 0))
                .requiredCount(1)
                .build()).getId();
    }
}
