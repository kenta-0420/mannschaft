package com.mannschaft.app.school;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave7 — school 保護者連絡（{@code FamilyAttendanceNoticeController}）認可契約テスト。
 *
 * <p>{@code getTeamNotices} / {@code acknowledgeNotice} / {@code applyToRecord} の 3EP は
 * 認可が皆無で、{@code acknowledge}・{@code apply} は path の {@code teamId} を Service に
 * 渡してすらいなかった（BOLA 未封鎖）。金型: {@code SchoolAttendanceScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。</p>
 *
 * <p><b>認可モデル</b>:</p>
 * <ul>
 *   <li><b>{@code getTeamNotices}</b>（スコープ宣言型・クラス全児童の PII 横断参照）:
 *       宣言スコープの ADMIN/DEPUTY_ADMIN（＝教員相当。マスター裁可 A-1）のみ 200。
 *       部外者・別チーム ADMIN・非 ADMIN メンバーはいずれも 403。</li>
 *   <li><b>{@code acknowledgeNotice} / {@code applyToRecord}</b>（親子 bare id）:
 *       まず連絡 entity を fetch し <b>entity 由来 teamId</b> と path を突合。不一致は
 *       404（存在秘匿）。一致したら当該チームの ADMIN/DEPUTY_ADMIN のみ許可（それ以外は 403）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("school 保護者連絡 認可契約テスト（Wave7）")
class SchoolFamilyNoticeScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FamilyAttendanceNoticeRepository noticeRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long teacherAId;   // teamA の ADMIN（＝担任相当・正当）
    private Long teacherBId;   // teamB の ADMIN（別クラスの越境攻撃者）
    private Long memberAId;    // teamA の非 ADMIN メンバー
    private Long outsiderId;   // どこにも所属しない部外者

    private Long noticeAId;    // teamA の保護者連絡
    private Long noticeBId;    // teamB の保護者連絡（越境アクセス検証用）

    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 5, 1);

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("FNAUTHZ クラスA");
        teamBId = insertTeam("FNAUTHZ クラスB");

        teacherAId = insertUser("fnauthz-teacher-a@example.com");
        teacherBId = insertUser("fnauthz-teacher-b@example.com");
        memberAId = insertUser("fnauthz-member-a@example.com");
        outsiderId = insertUser("fnauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, teacherAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teacherAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teacherBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teacherBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        noticeAId = noticeRepository.save(buildNotice(teamAId)).getId();
        noticeBId = noticeRepository.save(buildNotice(teamBId)).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/attendance/notices（クラス全児童の連絡一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/attendance/notices（一覧）")
    class GetTeamNotices {

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", teamAId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別クラスADMIN（クラスBの担任）は403（BOLA）")
        void 別クラスADMINは403() throws Exception {
            setAuth(teacherBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", teamAId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（大量PII参照は教員相当に限定）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", teamAId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当な担任（クラスAのADMIN）は200")
        void 正当な担任は200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", teamAId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(1));
        }

        @Test
        @DisplayName("B-1特有: クラスAの担任は teamId を差し替えても他クラスの連絡一覧を取得できない")
        void 他クラスのteamId指定は403() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/notices", teamBId)
                            .param("date", TARGET_DATE.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/attendance/notices/{noticeId}/acknowledge
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST .../notices/{noticeId}/acknowledge（確認）")
    class AcknowledgeNotice {

        @Test
        @DisplayName("越境（自クラスpath＋他クラスnoticeId）は404で存在秘匿（BOLA）")
        void 他クラスのnoticeIdは404() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            teamAId, noticeBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_FAMILY_NOTICE_NOT_FOUND"));
        }

        @Test
        @DisplayName("越境（他クラスpath＋他クラスnoticeId）は403")
        void 他クラスpathは403() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            teamBId, noticeBId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            teamAId, noticeAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            teamAId, noticeAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当な担任は200（機能非回帰）")
        void 正当な担任は200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/acknowledge",
                            teamAId, noticeAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /teams/{teamId}/attendance/notices/{noticeId}/apply
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST .../notices/{noticeId}/apply（出欠反映）")
    class ApplyToRecord {

        @Test
        @DisplayName("越境（自クラスpath＋他クラスnoticeId）は404で存在秘匿（BOLA）")
        void 他クラスのnoticeIdは404() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            teamAId, noticeBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHOOL_FAMILY_NOTICE_NOT_FOUND"));
        }

        @Test
        @DisplayName("部外者は403")
        void 部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            teamAId, noticeAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            teamAId, noticeAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当な担任は200（機能非回帰）")
        void 正当な担任は200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/attendance/notices/{noticeId}/apply",
                            teamAId, noticeAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.appliedToRecord").value(true));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private FamilyAttendanceNoticeEntity buildNotice(Long teamId) {
        // reasonDetail / attachedFileKeys は省略する（明示 null を渡すと @Builder.Default が
        // 無効化される罠を避けるため、省略したいフィールドはそもそも渡さない）。
        return FamilyAttendanceNoticeEntity.builder()
                .teamId(teamId)
                .studentUserId(memberAId)
                .submitterUserId(outsiderId)
                .attendanceDate(TARGET_DATE)
                .noticeType(FamilyNoticeType.ABSENCE)
                .build();
    }

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
                                + "VALUES (:email, 'FNAUTHZ', 'テスト', 'FNAUTHZ テスト', 'ACTIVE', "
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
