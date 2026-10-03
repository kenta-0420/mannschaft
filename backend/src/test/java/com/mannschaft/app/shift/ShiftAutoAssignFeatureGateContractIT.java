package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.5 §11.1「自動割当の停止」の契約テスト（AC-11-01 / AC-11-02 / AC-11-03 / AC-11-11）。
 *
 * <p>設計書: {@code docs/features/F03.5_shift/06_manual_authoring.md} §11.1。
 * 新フラグ {@code FEATURE_SHIFT_AUTO_ASSIGN_ENABLED} を {@code is_enabled = FALSE} で seed し、
 * {@code ShiftAutoAssignController} に<b>クラスレベル</b>で {@code @RequireFeature} を付けて
 * <b>6 経路すべて</b>（実行 / 割当確定 / 破棄 / 履歴一覧 / 履歴詳細 / 目視確認）を
 * {@code FEATURE_GATE_001} で塞ぐ、という設計を固定する。</p>
 *
 * <p><b>検証の要点</b>: 既存の per-scope 認可 403 と<b>撃ち分けられる</b>ように、
 * すべて<b>正当な ADMIN</b>で叩き、応答 body の {@code $.error.code} が
 * {@code FEATURE_GATE_001} であることを断言する。ステータスコードだけを見ると
 * 認可拒否と区別がつかず、ゲートが無くてもテストが通ってしまう。</p>
 *
 * <p>金型: {@code ShiftAutoAssignScopeContractIT}（同ドメイン・同エンドポイント群のフィクスチャ）＋
 * {@code FeatureGateAspectIT}（フラグ行の upsert とキャッシュ明示クリア）。</p>
 *
 * <p><b>フラグの ON/OFF の切り替え方</b>: テストプロファイルは Flyway を走らせないため
 * {@code feature_flags} は空であり、行が無いキーは fail-close で無効扱いになる。
 * 本テストは「無効」を偶然に頼らず、{@link #disableAutoAssignFlag()} で
 * {@code is_enabled = false} の行を<b>明示的に置く</b>。有効化は既存の
 * {@link FeatureFlagTestSupport#enable} を用いる。いずれの場合もフラグキャッシュを対で落とす
 * （{@code FeatureFlagService#isEnabled} は {@code @Cacheable} であり、
 * テストの {@code @Transactional} ロールバックの対象外のため）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift 自動割当の停止（機能フラグ）契約テスト")
class ShiftAutoAssignFeatureGateContractIT extends AbstractMySqlIntegrationTest {

    /** §11.1.2-1 で新設する停止フラグのキー。 */
    private static final String FLAG = "FEATURE_SHIFT_AUTO_ASSIGN_ENABLED";

    /** フラグ拒否時に返るエラーコード（{@code FeatureGateErrorCode.FEATURE_GATE_001}）。 */
    private static final String GATE_CODE = "FEATURE_GATE_001";

    /** 未確認 run があるときの公開拒否コード（{@code ShiftErrorCode.VISUAL_REVIEW_REQUIRED}）。 */
    private static final String VISUAL_REVIEW_REQUIRED = "SHIFT_025";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftAssignmentRunRepository runRepository;

    @Autowired
    private ShiftAssignmentRepository assignmentRepository;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long adminId;
    private Long memberId;

    private Long scheduleId;
    private Long slotId;

    /** SUCCEEDED（目視確認前）の run。目視確認と AC-11-11 の対象。 */
    private Long succeededRunId;
    /** SUCCEEDED の run（破棄用。破棄は run の状態を壊すため使い回さない）。 */
    private Long revokableRunId;
    /** CONFIRMED（目視確認済み）の run。割当確定の対象。 */
    private Long confirmedRunId;
    /** confirmedRun にぶら下がる PROPOSED 割当。 */
    private Long proposedAssignmentId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("B1 自動割当停止 チーム");

        adminId = insertUser("b1-gate-admin@example.com");
        memberId = insertUser("b1-gate-member@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため双方に張る。
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        scheduleId = insertSchedule(teamId, "B1 自動割当停止 スケジュール", adminId);

        slotId = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(12, 0))
                .requiredCount(1)
                .build()).getId();

        succeededRunId = insertRun(scheduleId, ShiftAssignmentRunStatus.SUCCEEDED);
        revokableRunId = insertRun(scheduleId, ShiftAssignmentRunStatus.SUCCEEDED);
        confirmedRunId = insertRun(scheduleId, ShiftAssignmentRunStatus.CONFIRMED);

        proposedAssignmentId = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(slotId)
                .userId(memberId)
                .runId(confirmedRunId)
                .status(ShiftAssignmentStatus.PROPOSED)
                .assignedBy(adminId)
                .build()).getId();

        em.flush();
        em.clear();

        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        setAuth(adminId);
    }

    @AfterEach
    void tearDown() {
        // フラグキャッシュはシングルトン Bean が持つためロールバックされない。次テストへ漏らさない。
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11-01: フラグ OFF で 6 経路すべてが FEATURE_GATE_001 で拒否される
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-11-01 フラグOFFのとき6経路すべてが FEATURE_GATE_001 で拒否される")
    class FlagOffBlocksAllSixRoutes {

        @Test
        @DisplayName("1/6 自動割当実行（POST /schedules/{id}/auto-assign）")
        void 実行が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }

        @Test
        @DisplayName("2/6 割当確定（POST /schedules/{id}/auto-assign/confirm）")
        void 割当確定が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }

        @Test
        @DisplayName("3/6 提案破棄（DELETE /schedules/{id}/auto-assign）")
        void 破棄が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }

        @Test
        @DisplayName("4/6 実行履歴一覧（GET /schedules/{id}/assignment-runs）")
        void 履歴一覧が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }

        @Test
        @DisplayName("5/6 実行ログ詳細（GET /assignment-runs/{runId}）")
        void 履歴詳細が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }

        @Test
        @DisplayName("6/6 目視確認（POST /assignment-runs/{runId}/confirm-visual-review）")
        void 目視確認が拒否される() throws Exception {
            disableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11-02: 拒否時に shift_assignment_runs の行が 1 件も増えない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11-02 フラグOFFでの拒否時に shift_assignment_runs の行が1件も増えない")
    void ac11_02_拒否時にrun行が増えない() throws Exception {
        disableAutoAssignFlag();

        long before = runRepository.count();

        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(runBody())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(GATE_CODE));

        em.clear();

        assertThat(runRepository.count())
                .as("ゲートは本体実行前に拒否するため run 行は増えない")
                .isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11-03: フラグ ON では 6 経路が従前どおり通る（テストを消していない担保）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-11-03 フラグONなら自動割当は従前どおり動く（既存資産を殺していない）")
    class FlagOnKeepsAutoAssignWorking {

        @Test
        @DisplayName("1/6 実行は201")
        void 実行は201() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(runBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("2/6 割当確定は200")
        void 割当確定は200() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmBody(confirmedRunId))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("3/6 破棄は204")
        void 破棄は204() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.valueOf(revokableRunId)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("4/6 履歴一覧は200")
        void 履歴一覧は200() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(get("/api/v1/shifts/schedules/{id}/assignment-runs", scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("5/6 履歴詳細は200")
        void 履歴詳細は200() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(get("/api/v1/shifts/assignment-runs/{runId}", confirmedRunId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("6/6 目視確認は200")
        void 目視確認は200() throws Exception {
            enableAutoAssignFlag();

            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "確認"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11-11: 既知の制約の固定（欠陥ではなく仕様）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * <b>これは「直すべきバグ」を検出するテストではない。</b>
     *
     * <p>設計書 §11.1.3 に記したとおり、{@code PUBLISHED} への遷移は
     * {@code ShiftAutoAssignService#assertNoUnreviewedRuns} を通り、当該 schedule に
     * {@code SUCCEEDED} の run が 1 件でも残っていれば {@code VISUAL_REVIEW_REQUIRED}（SHIFT_025）
     * で拒否される。それを解除できる唯一の経路である目視確認 API は、
     * クラスレベルのゲートによって同時に塞がれる。したがってフラグ OFF では
     * <b>未確認 run を抱えたシフト表は公開できなくなる</b>。
     * これは本設計における<b>既知の制約であり、仕様である</b>。</p>
     *
     * <p><b>この仕様が許されるのは「守るべき実データが無い」という現在の前提に依存している。</b>
     * 本プロジェクトは本番稼働前でローカルのデータしか無いため、該当行は破棄してよい
     * （移行設計もデータ移行マイグレーションも不要）。<b>本番データが存在する状態で
     * 改めてこのフラグを OFF にする場合、この経路は障害になる</b>（未確認 run を抱えた
     * シフト表が永久に公開できなくなり、利用者のデータが人質になる）。その時点では
     * 段階的停止か、後始末 2 経路（{@code confirm-visual-review} /
     * {@code DELETE .../auto-assign}）のゲート例外化が必須である。
     * <b>前提が変わったら設計書 §11.1.3 の申し送りを読み直し、本テストを見直すこと。</b></p>
     */
    @Nested
    @DisplayName("AC-11-11 既知の制約（欠陥ではない・設計書 §11.1.3 の申し送り参照）")
    class KnownConstraint {

        @Test
        @DisplayName("フラグOFF時_未確認runを持つシフト表は公開できない_既知の制約")
        void フラグOFF時_未確認runを持つシフト表は公開できない_既知の制約() throws Exception {
            disableAutoAssignFlag();

            // succeededRunId が SUCCEEDED（目視確認前）のまま残っているため公開は拒否される。
            mockMvc.perform(post("/api/v1/shifts/schedules/{id}/transition", scheduleId)
                            .param("status", ShiftScheduleStatus.PUBLISHED.name()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(VISUAL_REVIEW_REQUIRED));
        }

        @Test
        @DisplayName("フラグOFF時_制約を解除する唯一の経路である目視確認もゲートで塞がれている_既知の制約")
        void フラグOFF時_解除経路の目視確認もゲートで塞がれる_既知の制約() throws Exception {
            disableAutoAssignFlag();

            // 「公開できない」状態から自力で抜け出せないことこそが §11.1.3 の制約の本体である。
            mockMvc.perform(post("/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review", succeededRunId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "解除試行"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));

            em.clear();
            ShiftAssignmentRunEntity after = runRepository.findById(succeededRunId).orElseThrow();
            assertThat(after.getStatus())
                    .as("ゲートで拒否されたため CONFIRMED へ昇格していない")
                    .isEqualTo(ShiftAssignmentRunStatus.SUCCEEDED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 停止フラグを明示的に無効化する（行が無いことによる fail-close に偶然依存しない）。
     *
     * <p>既存行があれば更新、無ければ挿入の upsert。{@code FeatureGateAspectIT} が
     * 実測で踏んだ Duplicate entry を構造的に避ける流儀をそのまま踏襲する。</p>
     */
    private void disableAutoAssignFlag() {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(FLAG)
                .orElseGet(() -> FeatureFlagEntity.builder()
                        .flagKey(FLAG)
                        .description("F03.5 §11.1 試練用: 自動割当の停止フラグ")
                        .build());
        entity.updateFlag(false, null);
        featureFlagRepository.save(entity);
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    /** 停止フラグを有効化する（既存の共通補助に委ねる。キャッシュ退避も内部で行われる）。 */
    private void enableAutoAssignFlag() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, FLAG);
    }

    private Map<String, Object> runBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", AssignmentStrategyType.GREEDY_V1.name());
        return body;
    }

    private Map<String, Object> confirmBody(Long runId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("assignmentIds", List.of(proposedAssignmentId));
        body.put("scheduleVersion", 0);
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertSchedule(Long team, String title, Long createdBy) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(team)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.ADJUSTING)
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long insertRun(Long schedule, ShiftAssignmentRunStatus status) {
        return runRepository.save(ShiftAssignmentRunEntity.builder()
                .scheduleId(schedule)
                .strategy(AssignmentStrategyType.GREEDY_V1)
                .status(status)
                .triggeredBy(adminId)
                .slotsTotal(1)
                .slotsFilled(0)
                .build()).getId();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'B1', 'テスト', 'B1 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
