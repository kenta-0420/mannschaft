package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-017c AC-9 の回帰番人（検分🔵是正）: <b>fan-out enqueue が失敗しても、変換（convert）トランザクションは
 * 生き残り、schedule / keep が実 DB に永続する</b>ことを実 DB で裏取りする。
 *
 * <p>単体 {@code ScheduleKeepNotificationServiceTest#ac9_...} はモックで呼出順序・例外伝播しか見ていない。
 * 本 IT は convert 経路（{@code ScheduleKeepService.convert}）を実際に通し、
 * {@link NotificationFanoutJobService} を {@link MockitoBean} で差し替えて enqueue を {@link RuntimeException} で
 * 失敗させたうえで、<b>変換が 500（{@code UnexpectedRollbackException}）にならず 200 で成立し、変換後の予定と
 * キープの状態遷移が実 DB にコミットされている</b>ことを検証する（§6.2 best-effort・§6.2.1 の TX 救済）。</p>
 *
 * <h2>なぜ非トランザクション IT か</h2>
 * <p>「convert の外側 TX がコミットされる」ことの検証なので、テスト自身が外側 TX を張ってはならない
 * （張ると commit ではなく test の rollback で片付き、救済の成否が観測できない）。フィクスチャは
 * {@link TransactionTemplate} で明示コミットし、検証は convert 後に repository で読み直す（committed read）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-017c AC-9 fan-out enqueue 失敗時の変換 TX 生存 実DB IT")
class ScheduleKeepConvertFanoutFailureIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduleKeepRepository scheduleKeepRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    /** fan-out enqueue を失敗させるためにモック差し替えする（作成者直送 publisher は本物のまま）。 */
    @MockitoBean
    private NotificationFanoutJobService fanoutJobService;

    @Test
    @DisplayName("ac9: fan-out enqueue が RuntimeException で失敗しても convert は 200 で成立し、"
            + "schedule と keep(SCHEDULED) が実 DB に永続する")
    void ac9_enqueueFailureKeepsConvertTransactionCommitted() throws Exception {
        // enqueue（12 引数版）を常に失敗させる。DB 一時障害などの enqueue 失敗を模す。
        doThrow(new RuntimeException("fan-out enqueue 失敗（DB 一時障害を模す）"))
                .when(fanoutJobService).enqueue(anyString(), anyString(), anyString(), any(UUID.class), isNull(),
                        anyString(), anyString(), any(), anyString(), anyLong(), anyString(), anyLong());

        long suffix = System.nanoTime() % 1_000_000L;
        String teamSlug = "kcf-" + suffix;

        // --- フィクスチャを実コミットする（非トランザクション IT のため明示的に commit する）---
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicReference<Long> teamIdRef = new AtomicReference<>();
        AtomicReference<Long> memberIdRef = new AtomicReference<>();
        AtomicReference<UUID> keepIdRef = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            Long teamId = insertTeam("キープ変換fan-out失敗検証チーム", teamSlug);
            Long memberId = insertUser("keepconv-fanoutfail-" + suffix + "@example.com");
            // convert 権限（MEMBER 以上）は user_roles を見る AccessControlService が判定するため両系統を張る。
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            // 作成者=操作者（memberId）にして作成者直送はスキップさせ、fan-out enqueue 失敗の一点に絞る。
            ScheduleKeepEntity keep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                    .teamId(teamId)
                    .title("fan-out失敗でも決まる合宿")
                    .status(ScheduleKeepStatus.KEPT)
                    .sortOrder(0)
                    .createdBy(memberId)
                    .build());
            em.flush();
            teamIdRef.set(teamId);
            memberIdRef.set(memberId);
            keepIdRef.set(keep.getId());
        });

        Long teamId = teamIdRef.get();
        Long memberId = memberIdRef.get();
        UUID keepId = keepIdRef.get();

        // --- convert を実際に叩く。enqueue は必ず失敗するが、変換は 200 で成立しなければならない ---
        setAuthentication(memberId);
        mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/convert", teamSlug, keepId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startAt", "2026-08-15T00:00:00", "allDay", true))))
                .andExpect(status().isOk());

        // --- 実 DB に永続していること（committed read）。enqueue の失敗が変換を巻き戻していない ---
        ScheduleKeepEntity persistedKeep = scheduleKeepRepository.findById(keepId).orElseThrow();
        assertThat(persistedKeep.getStatus())
                .as("AC-9: enqueue 失敗でもキープは SCHEDULED へ遷移してコミットされている")
                .isEqualTo(ScheduleKeepStatus.SCHEDULED);
        assertThat(persistedKeep.getConvertedScheduleId())
                .as("AC-9: 変換先の予定 ID が紐づいてコミットされている")
                .isNotNull();

        Long scheduleId = persistedKeep.getConvertedScheduleId();
        List<ScheduleEntity> created = scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                teamId, LocalDateTime.of(2026, 8, 14, 0, 0), LocalDateTime.of(2026, 8, 16, 0, 0));
        assertThat(created)
                .as("AC-9: 変換で作られた予定が実 DB に永続している（500=UnexpectedRollbackException で消えていない）")
                .anyMatch(s -> s.getId().equals(scheduleId) && "fan-out失敗でも決まる合宿".equals(s.getTitle()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ（ScheduleKeepConvertContractIT と同じ native INSERT 作法）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
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
                                + "VALUES (:email, 'F0317', 'テスト', 'F0317 テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
