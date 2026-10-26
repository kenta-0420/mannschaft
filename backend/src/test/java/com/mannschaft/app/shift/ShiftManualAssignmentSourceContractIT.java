package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260908-2117 — 手動割当したシフトが読み出し側に現れることの契約テスト（実 MySQL）。
 *
 * <p><b>症状</b>: 手動割当 {@code PATCH /api/v1/shifts/slots/{id}/assignments} は
 * {@code shift_slots.assigned_user_ids}(JSON) にしか書かず、読み出し側
 *（自分のシフト・今後の予定・充足サマリー）は {@code shift_assignments} の
 * {@code status = CONFIRMED} を見ていた。同表に書くのは自動割当だけだったため、
 * <b>手動で割り当てられた人のシフトはどこにも表示されなかった</b>。</p>
 *
 * <p><b>方針</b>: 現在の割当状態の正本は {@code assigned_user_ids}。読み出し 3 経路をそちらへ向け直し、
 * {@code shift_assignments} は「誰がいつ割り当て・解除したか」の履歴として手動割当でも記録する。</p>
 *
 * <p>受け入れ条件との対応:</p>
 * <ul>
 *   <li><b>AC-1</b> — {@link MyConfirmedSlots}: {@code GET /shifts/my/confirmed-slots} に現れる</li>
 *   <li><b>AC-2</b> — {@link UpcomingEvents}: 個人ダッシュボードの「今後の予定」に現れる</li>
 *   <li><b>AC-3</b> — {@link ScheduleSummary}: 管理者の充足サマリーが手動割当を数える</li>
 *   <li><b>AC-4</b> — 各 Nested の未公開ケース: 未公開シフト表は本人にも漏れない</li>
 *   <li><b>AC-5</b> — {@link AutoAssignRegression}: 自動割当（JSON 同期）経路が従来どおり読める</li>
 *   <li><b>AC-6 / AC-7</b> — {@link AssignmentHistory}: 割当・解除が履歴表に残る</li>
 * </ul>
 *
 * <p>金型は同ドメインの {@code ShiftUnpublishedScheduleVisibilityContractIT}
 *（{@code addFilters=false} + 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>{@code JSON_CONTAINS} について</b>: 読み出しの絞り込みは MySQL の {@code JSON_CONTAINS} を
 * 使うネイティブクエリであり、JPQL では書けない。したがって本挙動を固定できるのは実 MySQL の
 * 統合テストだけで、単体テスト（モック）では原理的に検証できない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260908-2117 手動割当が読み出し側に現れる契約テスト")
class ShiftManualAssignmentSourceContractIT extends AbstractMySqlIntegrationTest {

    private static final String MY_CONFIRMED_PATH = "/api/v1/shifts/my/confirmed-slots";
    private static final String UPCOMING_PATH = "/api/v1/dashboard/upcoming-events";
    private static final String SLOTS_ASSIGNMENTS_PATH = "/api/v1/shifts/slots/{id}/assignments";
    private static final String SUMMARY_PATH = "/api/v1/shifts/schedules/{id}/summary";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

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

    /** 公開済みシフト表と、その中の未割当の枠。 */
    private Long publishedScheduleId;
    private Long publishedSlotId;

    /** 未公開（DRAFT）シフト表と、その中の「memberId が割り当て済み」の枠。 */
    private Long draftScheduleId;
    private Long draftSlotId;

    /** 「今後の予定」の取得期間に確実に入る日付（当日を含む近い将来）。 */
    private LocalDate upcomingDate;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        teamId = insertTeam("CMP2117 対象チーム");
        adminId = insertUser("cmp2117-admin@example.com");
        memberId = insertUser("cmp2117-member@example.com");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        upcomingDate = LocalDate.now().plusDays(1);

        publishedScheduleId = insertSchedule("CMP2117 公開シフト表", ShiftScheduleStatus.PUBLISHED, true);
        publishedSlotId = insertSlot(publishedScheduleId, upcomingDate, 2, null);

        draftScheduleId = insertSchedule("CMP2117 未公開シフト表", ShiftScheduleStatus.DRAFT, false);
        // 未公開シフト表側は最初から割り当て済みにしておく（漏洩を検知するための仕掛け）。
        draftSlotId = insertSlot(draftScheduleId, upcomingDate, 2, List.of(memberId));

        em.flush();
        em.clear();
    }

    // =====================================================
    // AC-1: 自分の確定シフト
    // =====================================================

    @Nested
    @DisplayName("AC-1 / AC-4: 自分の確定シフト")
    class MyConfirmedSlots {

        @Test
        @DisplayName("AC-1: 手動割当したユーザーが自分の確定シフト一覧に現れる")
        void 手動割当が自分のシフトに現れる() throws Exception {
            manuallyAssign(publishedSlotId, memberId);

            setAuth(memberId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].slotId", hasItem(publishedSlotId.intValue())));
        }

        @Test
        @DisplayName("AC-4: 未公開（DRAFT）シフト表の割当は本人にも現れない")
        void 未公開シフトは本人にも漏れない() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].slotId", not(hasItem(draftSlotId.intValue()))));
        }

        @Test
        @DisplayName("AC-4: 調整中（ADJUSTING = 割当マスク）の割当も本人には返さない")
        void 調整中シフトは本人に返さない() throws Exception {
            Long adjustingScheduleId =
                    insertSchedule("CMP2117 調整中シフト表", ShiftScheduleStatus.ADJUSTING, false);
            Long adjustingSlotId = insertSlot(adjustingScheduleId, upcomingDate, 2, List.of(memberId));
            em.flush();
            em.clear();

            setAuth(memberId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].slotId", not(hasItem(adjustingSlotId.intValue()))));
        }

        @Test
        @DisplayName("他人の枠は返さない（割当されていない人には空）")
        void 割当のない人には返さない() throws Exception {
            manuallyAssign(publishedSlotId, memberId);

            setAuth(adminId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", empty()));
        }
    }

    // =====================================================
    // AC-2: 今後の予定
    // =====================================================

    @Nested
    @DisplayName("AC-2 / AC-4: 個人ダッシュボードの今後の予定")
    class UpcomingEvents {

        @Test
        @DisplayName("AC-2: 手動割当したユーザーの今後の予定にシフトが現れる")
        void 手動割当が今後の予定に現れる() throws Exception {
            manuallyAssign(publishedSlotId, memberId);

            setAuth(memberId);
            mockMvc.perform(get(UPCOMING_PATH).param("days", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.kind == 'SHIFT')].id",
                            hasItem(publishedSlotId.intValue())));
        }

        @Test
        @DisplayName("AC-4: 未公開シフト表の割当は今後の予定に現れない")
        void 未公開シフトは今後の予定に漏れない() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(UPCOMING_PATH).param("days", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.kind == 'SHIFT')].id",
                            not(hasItem(draftSlotId.intValue()))));
        }
    }

    // =====================================================
    // AC-3: 充足サマリー
    // =====================================================

    @Nested
    @DisplayName("AC-3: 管理者の充足サマリー")
    class ScheduleSummary {

        @Test
        @DisplayName("AC-3: 手動割当が充足数に数えられる")
        void 手動割当が充足数に数えられる() throws Exception {
            setAuth(adminId);
            mockMvc.perform(get(SUMMARY_PATH, publishedScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summaryByDate[0].totalConfirmed").value(0));

            manuallyAssign(publishedSlotId, memberId);

            setAuth(adminId);
            mockMvc.perform(get(SUMMARY_PATH, publishedScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summaryByDate[0].totalConfirmed").value(1));
        }

        @Test
        @DisplayName("ポジション未設定の枠があってもサマリーが 500 にならない（NULL キーのグループ化）")
        void ポジション未設定の枠でも500にならない() throws Exception {
            // 本 IT のフィクスチャは positionId を設定していない。旧実装の
            // Collectors.groupingBy(s -> s.getPositionId(), HashMap::new, ...) は
            // 分類関数の戻り値を必ず null 検査するため、この状態で NPE → 500 になっていた
            //（既存の単体テストは常に positionId を設定していたため気づけなかった）。
            setAuth(adminId);
            mockMvc.perform(get(SUMMARY_PATH, publishedScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summaryByDate[0].byPosition[0].positionId").doesNotExist());
        }
    }

    // =====================================================
    // AC-5: 自動割当経路の回帰
    // =====================================================

    @Nested
    @DisplayName("AC-5: 自動割当経路の回帰")
    class AutoAssignRegression {

        @Test
        @DisplayName("AC-5: 自動割当の確定で同期された JSON も従来どおり読める")
        void 自動割当の同期結果も読める() throws Exception {
            // 自動割当の確定（ShiftAutoAssignService#updateSlotAssignedUsers）が行うのは
            // 「CONFIRMED な shift_assignments から assigned_user_ids を再構築する」ことなので、
            // 同じ最終状態（履歴行 + JSON 同期済み）を作って読み出しを確認する。
            ShiftSlotEntity slot = slotRepository.findById(publishedSlotId).orElseThrow();
            slot.updateAssignedUserIds(objectMapper.writeValueAsString(List.of(memberId)));
            slotRepository.save(slot);
            assignmentRepository.save(ShiftAssignmentEntity.builder()
                    .slotId(publishedSlotId)
                    .userId(memberId)
                    .runId(9999L)
                    .status(ShiftAssignmentStatus.CONFIRMED)
                    .assignedBy(adminId)
                    .build());
            em.flush();
            em.clear();

            setAuth(memberId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].slotId", hasItem(publishedSlotId.intValue())));

            setAuth(adminId);
            mockMvc.perform(get(SUMMARY_PATH, publishedScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.summaryByDate[0].totalConfirmed").value(1));
        }
    }

    // =====================================================
    // AC-6 / AC-7: 手動割当の操作履歴
    // =====================================================

    @Nested
    @DisplayName("AC-6 / AC-7: 手動割当の操作履歴")
    class AssignmentHistory {

        @Test
        @DisplayName("AC-6: 手動割当で操作者と時刻を伴う履歴行が記録される（run_id は NULL = 手動）")
        void 手動割当で履歴が残る() throws Exception {
            LocalDateTime before = LocalDateTime.now().minusMinutes(1);
            manuallyAssign(publishedSlotId, memberId);
            em.flush();
            em.clear();

            List<ShiftAssignmentEntity> history = assignmentRepository.findAllBySlotId(publishedSlotId);
            assertThat(history).singleElement().satisfies(a -> {
                assertThat(a.getUserId()).isEqualTo(memberId);
                assertThat(a.getRunId()).isNull();
                assertThat(a.getAssignedBy()).isEqualTo(adminId);
                assertThat(a.getStatus()).isEqualTo(ShiftAssignmentStatus.CONFIRMED);
                assertThat(a.getCreatedAt()).isAfter(before);
            });
        }

        @Test
        @DisplayName("AC-7: 手動解除も履歴から追える（REVOKED へ遷移し、正本の JSON からも消える）")
        void 手動解除も履歴から追える() throws Exception {
            manuallyAssign(publishedSlotId, memberId);
            em.flush();
            em.clear();

            manuallyUnassign(publishedSlotId, memberId);
            em.flush();
            em.clear();

            List<ShiftAssignmentEntity> history = assignmentRepository.findAllBySlotId(publishedSlotId);
            assertThat(history).singleElement().satisfies(a -> {
                assertThat(a.getUserId()).isEqualTo(memberId);
                assertThat(a.getStatus()).isEqualTo(ShiftAssignmentStatus.REVOKED);
                assertThat(a.getUpdatedAt()).isAfterOrEqualTo(a.getCreatedAt());
            });

            // 正本（JSON）からも消えているので、本人のシフト一覧にも出ない。
            setAuth(memberId);
            mockMvc.perform(get(MY_CONFIRMED_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].slotId", not(hasItem(publishedSlotId.intValue()))));
        }

        @Test
        @DisplayName("外して再度入れると、REVOKED 行と新しい CONFIRMED 行が並ぶ")
        void 解除後の再割当は新しい行になる() throws Exception {
            manuallyAssign(publishedSlotId, memberId);
            em.flush();
            em.clear();
            manuallyUnassign(publishedSlotId, memberId);
            em.flush();
            em.clear();
            manuallyAssign(publishedSlotId, memberId);
            em.flush();
            em.clear();

            List<ShiftAssignmentEntity> history = assignmentRepository.findAllBySlotId(publishedSlotId);
            assertThat(history).hasSize(2);
            assertThat(history).extracting(ShiftAssignmentEntity::getStatus)
                    .containsExactlyInAnyOrder(
                            ShiftAssignmentStatus.REVOKED, ShiftAssignmentStatus.CONFIRMED);
        }
    }

    // =====================================================
    // ヘルパー
    // =====================================================

    /** 管理者として手動割当 API を叩く（本 CMP が対象とする実際の操作経路）。 */
    private void manuallyAssign(Long slotId, Long userId) throws Exception {
        patchAssignments(slotId, "addUserIds", userId);
    }

    /** 管理者として手動解除 API を叩く。 */
    private void manuallyUnassign(Long slotId, Long userId) throws Exception {
        patchAssignments(slotId, "removeUserIds", userId);
    }

    private void patchAssignments(Long slotId, String field, Long userId) throws Exception {
        int version = slotRepository.findById(slotId).orElseThrow().getVersion().intValue();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                field, List.of(userId),
                "slotVersion", version));
        setAuth(adminId);
        mockMvc.perform(patch(SLOTS_ASSIGNMENTS_PATH, slotId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        // 後続の読み出しはネイティブクエリ（JSON_CONTAINS）で行うため、
        // 永続化コンテキストに溜めたままだと DB に無い状態を読むことになる。明示的に流す。
        em.flush();
        em.clear();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertSchedule(String title, ShiftScheduleStatus status, boolean published) {
        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(7))
                .status(status)
                .publishedAt(published ? LocalDateTime.now().minusDays(1) : null)
                .createdBy(adminId)
                .build());
        return schedule.getId();
    }

    private Long insertSlot(Long scheduleId, LocalDate date, int requiredCount, List<Long> assignedUserIds) {
        String json;
        try {
            json = assignedUserIds == null ? null : objectMapper.writeValueAsString(assignedUserIds);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(date)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .requiredCount(requiredCount)
                .assignedUserIds(json)
                .build());
        return slot.getId();
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
                                + "VALUES (:email, 'CMP2117', 'テスト', 'CMP2117 テスト', 'ACTIVE', "
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
