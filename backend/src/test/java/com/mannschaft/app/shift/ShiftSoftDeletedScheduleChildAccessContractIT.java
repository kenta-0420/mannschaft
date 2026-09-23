package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 親スケジュール論理削除時の子（枠／希望）アクセス契約テスト（試練 / red 先行・CMP-260917-1136）。
 *
 * <p>シフトスケジュールは論理削除（{@code deleted_at} ＋ {@code @SQLRestriction}）だが、子（
 * {@code shift_slots} / {@code shift_requests}）は論理削除を持たないため、親削除後も物理的には残り続ける
 *（実データで孤児枠421件・割当225件・希望13件を確認済み）。</p>
 *
 * <p><b>不具合(a)</b>: {@code ShiftSlotService#checkScheduleAdminAccess} / {@code checkScheduleReadAccess} は
 * SYSTEM_ADMIN を親の生存確認より先に短絡させていたため、<b>SYSTEM_ADMIN だけが親削除済みの枠を
 * 編集・削除できてしまい</b>、一般 ADMIN（{@code resolveTeamId} で404）と挙動が食い違っていた。
 * {@code ShiftRequestService.deleteRequest} も同型（本人一致なら親を一度も引かずに削除が通っていた）。</p>
 *
 * <p><b>案A</b>: 親の生存確認を SYSTEM_ADMIN にも一律で課す（本クラスの主眼）。</p>
 * <p><b>案C</b>: {@code GET /shifts/my/requests} は提出履歴を消さず、親削除済みなら
 * {@code scheduleDeleted=true} を返す（一覧からは消さない。詳細は404のまま）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（親削除済みスケジュールの子アクセス）契約テスト（試練・CMP-260917-1136）")
class ShiftSoftDeletedScheduleChildAccessContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private ShiftRequestRepository requestRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    private Long systemAdminId;   // プラットフォーム級 SYSTEM_ADMIN（当該チームの非メンバー）
    private Long adminId;         // チームの一般 ADMIN
    private Long memberId;        // 希望の提出者本人（非ADMIN）

    private Long liveScheduleId;      // 生存しているスケジュール
    private Long liveSlotId;

    private Long deletedScheduleId;   // 論理削除済みスケジュール（孤児枠・孤児希望の親）
    private Long orphanSlotId;
    private Long orphanRequestId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("CMP-260917-1136 チーム");

        systemAdminId = insertUser("cmp260917-system-admin@example.com");
        adminId = insertUser("cmp260917-admin@example.com");
        memberId = insertUser("cmp260917-member@example.com");

        // SYSTEM_ADMIN はプラットフォーム級。当該チームの memberships は敢えて張らない
        // （ShiftUnpublishedScheduleVisibilityContractIT 踏襲）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        // 生存しているスケジュール（退行確認用）
        ShiftScheduleEntity liveSchedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP-260917-1136 生存スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 4, 1))
                .endDate(LocalDate.of(2026, 4, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 3, 20, 10, 0))
                .createdBy(adminId)
                .build());
        liveScheduleId = liveSchedule.getId();

        ShiftSlotEntity liveSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(liveScheduleId)
                .slotDate(LocalDate.of(2026, 4, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build());
        liveSlotId = liveSlot.getId();

        // 論理削除済みスケジュール（孤児の枠・希望の親）
        ShiftScheduleEntity deletedSchedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP-260917-1136 削除済みスケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminId)
                .build());
        deletedScheduleId = deletedSchedule.getId();

        ShiftSlotEntity orphanSlot = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(deletedScheduleId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .requiredCount(2)
                .build());
        orphanSlotId = orphanSlot.getId();

        ShiftRequestEntity orphanRequest = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(deletedScheduleId)
                .userId(memberId)
                .slotDate(LocalDate.of(2026, 3, 2))
                .preference(ShiftPreference.PREFERRED)
                .build());
        orphanRequestId = orphanRequest.getId();

        // 親を論理削除する（@SQLRestriction により以降 findById 等は見えなくなる）。
        deletedSchedule.softDelete();
        scheduleRepository.save(deletedSchedule);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 不具合(a) 案A: 親削除済みの枠に対する SYSTEM_ADMIN 更新
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /shifts/slots/{slotId}（親削除済み・SYSTEM_ADMIN）")
    class UpdateOrphanSlotAsSystemAdmin {

        @Test
        @DisplayName("SYSTEM_ADMINでも親削除済みなら404（案A本体）")
        void SYSTEM_ADMINでも親削除済みなら404() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", orphanSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "亡霊枠編集"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SYSTEM_ADMINは親が生存していれば200（退行なし）")
        void SYSTEM_ADMINは親が生存していれば200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", liveSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "生存枠編集"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般ADMINは親削除済みなら404（退行なし・既存挙動）")
        void 一般ADMINは親削除済みなら404() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch("/api/v1/shifts/slots/{id}", orphanSlotId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("note", "亡霊枠編集"))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /shifts/slots/{slotId}（親削除済み・SYSTEM_ADMIN）")
    class DeleteOrphanSlotAsSystemAdmin {

        @Test
        @DisplayName("SYSTEM_ADMINでも親削除済みなら404（案A本体）")
        void SYSTEM_ADMINでも親削除済みなら404() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(delete("/api/v1/shifts/slots/{id}", orphanSlotId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 不具合(b): 希望削除は親が削除済みなら本人でも拒否
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /shifts/requests/{requestId}（親削除済み・本人）")
    class DeleteOrphanRequestAsOwner {

        @Test
        @DisplayName("本人でも親削除済みの希望を削除しようとすると404")
        void 本人でも親削除済みなら404() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete("/api/v1/shifts/requests/{id}", orphanRequestId))
                    .andExpect(status().isNotFound());

            // 対処療法（例外を握りつぶして何もしない）でないことも実データで裏取りする:
            // 行は消えていないはず（404 は認可・存在確認の結果であり、削除処理自体は走っていない）。
            assertThat(requestRepository.findById(orphanRequestId)).isPresent();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 案C: GET /shifts/my/requests は削除済みでも一覧に残し、フラグを立てる
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /shifts/my/requests（案C）")
    class ListMyRequests {

        @Test
        @DisplayName("親削除済みの希望も一覧からは消えず、scheduleDeleted=trueが立つ")
        void 親削除済みの希望はscheduleDeletedがtrue() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/shifts/my/requests"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(orphanRequestId))
                    .andExpect(jsonPath("$.data[0].scheduleDeleted").value(true));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'CMP260917', 'テスト', 'CMP260917 テスト', 'ACTIVE', "
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
