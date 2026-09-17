package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
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

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code ShiftAvailabilityController}（{@code GET/PUT/DELETE /api/v1/shifts/availability}）の
 * 認可契約テスト（試練 / red 先行）。
 *
 * <p>実機で確認済みの欠陥: 3 メソッドいずれも {@code teamId} に対する所属検証が無く、
 * 他テナントの利用者が他チームの曜日既定デフォルト勤務可能時間を読み書き・削除できる
 * （{@code SelfScopedEndpoint} の宣言は「userId は必ず呼び出し元自身」を保証するのみで、
 * teamId は絞り込みにのみ働き所属を検証していない）。</p>
 *
 * <p>本試練が固定する認可式（実装はまだ無いため red）:</p>
 * <pre>
 * isSystemAdmin || isAdminOrAbove || (isMember &amp;&amp; !isSupporter)
 * </pre>
 *
 * <p>拒否時は {@code CommonErrorCode.COMMON_002} → 403。存在しない {@code teamId} も
 * 非メンバーと同一の 403 とし、存在オラクルを作らない（AC-5 / AC-7b）。</p>
 *
 * <p>金型: {@code ShiftHourlyRateScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（勤務可能時間デフォルト）認可契約テスト（試練）")
class ShiftAvailabilityScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/availability";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberAvailabilityDefaultRepository availabilityRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;

    /** teams に一度も存在しない teamId（AC-5 / AC-7b 用）。 */
    private Long nonExistentTeamId;

    private Long memberTeamAId;       // TEAM A の通常在籍メンバー（AC-4）
    private Long adminUserRoleOnlyId; // user_roles にのみ ADMIN 行を持ち memberships 行を持たない利用者（AC-4b）
    private Long supporterTeamAId;    // TEAM A の SUPPORTER 種別メンバー（AC-6）
    private Long systemAdminId;       // SYSTEM_ADMIN（AC-7 / AC-7b）
    private Long nonMemberId;         // TEAM A に一切所属しない利用者（AC-1〜3, AC-5, AC-8）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("試練 勤務可能時間 チームA");
        nonExistentTeamId = teamAId + 900_000_000L;

        memberTeamAId = insertUser("shiren-avail-member@example.com");
        adminUserRoleOnlyId = insertUser("shiren-avail-admin-userrole-only@example.com");
        supporterTeamAId = insertUser("shiren-avail-supporter@example.com");
        systemAdminId = insertUser("shiren-avail-system-admin@example.com");
        nonMemberId = insertUser("shiren-avail-non-member@example.com");

        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterTeamAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);

        // AC-4b: user_roles にのみ ADMIN 行を持ち、memberships 行を持たない利用者。
        // AccessControlService.isMember は memberships しか見ないため、isMember 単独判定だと
        // ここが落ちる（本 AC の主眼）。
        MembershipTestHelper.insertUserRole(em, adminUserRoleOnlyId, "ADMIN", teamAId, null);

        // SYSTEM_ADMIN はプラットフォームロール（teamId/organizationId ともに null）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/availability?teamId=
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/availability（デフォルト勤務可能時間取得）")
    class GetAvailabilityDefaults {

        @Test
        @DisplayName("AC-1: 非メンバーがteamAを参照すると403 COMMON_002")
        void 非メンバーは403() throws Exception {
            setAuth(nonMemberId);
            mockMvc.perform(get(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-4: 通常在籍メンバーは自チームで200")
        void 通常メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-4b: user_rolesにのみADMIN行を持ちmembershipsを持たない利用者は200"
                + "（isMember単独判定では落ちる）")
        void userRoles限定ADMINは200() throws Exception {
            setAuth(adminUserRoleOnlyId);
            mockMvc.perform(get(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-5: 存在しないteamIdは非メンバーと同じ403（存在オラクルを作らない）")
        void 存在しないteamIdは403() throws Exception {
            setAuth(nonMemberId);
            mockMvc.perform(get(BASE).param("teamId", nonExistentTeamId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-6: SUPPORTER種別のメンバーは403（isMemberのみの判定では落ちる）")
        void SUPPORTERは403() throws Exception {
            setAuth(supporterTeamAId);
            mockMvc.perform(get(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-7: SYSTEM_ADMINは所属していなくても200")
        void SYSTEM_ADMINは200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-7b: SYSTEM_ADMINでも存在しないteamIdは403")
        void SYSTEM_ADMINでも存在しないteamIdは403() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(get(BASE).param("teamId", nonExistentTeamId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-9: teamId省略は400（現行実装がRequestParam必須のため）")
        void teamId省略は400() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PUT /shifts/availability?teamId=
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PUT /shifts/availability（デフォルト勤務可能時間一括設定）")
    class SetAvailabilityDefaults {

        @Test
        @DisplayName("AC-2: 非メンバーがteamAへPUTすると403 COMMON_002 かつ行が1件も作成されない")
        void 非メンバーは403かつ作成されない() throws Exception {
            setAuth(nonMemberId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            assertThat(availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(nonMemberId, teamAId))
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-4: 通常在籍メンバーは自チームで200")
        void 通常メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-4b: user_rolesにのみADMIN行を持ちmembershipsを持たない利用者は200")
        void userRoles限定ADMINは200() throws Exception {
            setAuth(adminUserRoleOnlyId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-5: 存在しないteamIdへのPUTは403")
        void 存在しないteamIdは403() throws Exception {
            setAuth(nonMemberId);
            mockMvc.perform(put(BASE).param("teamId", nonExistentTeamId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-6: SUPPORTER種別のメンバーは403")
        void SUPPORTERは403() throws Exception {
            setAuth(supporterTeamAId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-7: SYSTEM_ADMINは所属していなくても200")
        void SYSTEM_ADMINは200() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-7b: SYSTEM_ADMINでも存在しないteamIdへのPUTは403 かつデータ非変更")
        void SYSTEM_ADMINでも存在しないteamIdは403かつ非変更() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(put(BASE).param("teamId", nonExistentTeamId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            assertThat(availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(systemAdminId, nonExistentTeamId))
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-8: 既存行がある状態で非メンバーがPUTすると403 かつ既存行は残る"
                + "（全削除→再作成の実装が拒否経路でも破壊的先行削除をしないこと）")
        void 既存行がある状態で非メンバーPUTは既存行を破壊しない() throws Exception {
            // Given: teamA の正当メンバーが既にデフォルトを設定済み
            setAuth(memberTeamAId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());

            List<MemberAvailabilityDefaultEntity> before = availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(memberTeamAId, teamAId);
            assertThat(before).isNotEmpty();

            // When: 非メンバー（別ユーザー）が同じ teamId へ PUT を試みる。
            // 攻撃者自身の行は無いため、既存行破壊の有無は「メンバー本人の行」で確認する
            // （PUT は userId=呼び出し元自身に自己スコープされるため、攻撃者の PUT は
            //   本来 memberTeamA の行には触れ得ない設計だが、認可チェックそのものが
            //   無い現状ではリクエストが 200 で通ってしまい、この AC が red になる）。
            setAuth(nonMemberId);
            mockMvc.perform(put(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            // Then: memberTeamA 本人の既存行は変化していない
            List<MemberAvailabilityDefaultEntity> after = availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(memberTeamAId, teamAId);
            assertThat(after).hasSameSizeAs(before);
        }

        @Test
        @DisplayName("AC-9: teamId省略は400")
        void teamId省略は400() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isBadRequest());
        }

        private Map<String, Object> bulkBody() {
            return Map.of("availabilities", List.of(Map.of(
                    "dayOfWeek", 1,
                    "startTime", LocalTime.of(9, 0).toString(),
                    "endTime", LocalTime.of(17, 0).toString(),
                    "preference", "AVAILABLE",
                    "note", "試練フィクスチャ")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. DELETE /shifts/availability?teamId=
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DELETE /shifts/availability（デフォルト勤務可能時間削除）")
    class DeleteAvailabilityDefaults {

        @Test
        @DisplayName("AC-3: 非メンバーがteamAへDELETEすると403 COMMON_002 かつ既存行は削除されない")
        void 非メンバーは403かつ削除されない() throws Exception {
            // Given: memberTeamA が既にデフォルトを保有
            saveAvailability(memberTeamAId, teamAId);

            setAuth(nonMemberId);
            mockMvc.perform(delete(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            assertThat(availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(memberTeamAId, teamAId))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("AC-4: 通常在籍メンバーは自チームで204")
        void 通常メンバーは204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-4b: user_rolesにのみADMIN行を持ちmembershipsを持たない利用者は204")
        void userRoles限定ADMINは204() throws Exception {
            setAuth(adminUserRoleOnlyId);
            mockMvc.perform(delete(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-5: 存在しないteamIdへのDELETEは403")
        void 存在しないteamIdは403() throws Exception {
            setAuth(nonMemberId);
            mockMvc.perform(delete(BASE).param("teamId", nonExistentTeamId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-6: SUPPORTER種別のメンバーは403")
        void SUPPORTERは403() throws Exception {
            setAuth(supporterTeamAId);
            mockMvc.perform(delete(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("AC-7: SYSTEM_ADMINは所属していなくても204")
        void SYSTEM_ADMINは204() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(delete(BASE).param("teamId", teamAId.toString()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-7b: SYSTEM_ADMINでも存在しないteamIdへのDELETEは403 かつデータ非変更")
        void SYSTEM_ADMINでも存在しないteamIdは403かつ非変更() throws Exception {
            saveAvailability(systemAdminId, teamAId);

            setAuth(systemAdminId);
            mockMvc.perform(delete(BASE).param("teamId", nonExistentTeamId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));

            // 存在しない teamId への削除であり、実在する teamA の行には触れていないこと
            assertThat(availabilityRepository
                    .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(systemAdminId, teamAId))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("AC-9: teamId省略は400")
        void teamId省略は400() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete(BASE))
                    .andExpect(status().isBadRequest());
        }

        private void saveAvailability(Long userId, Long teamId) {
            availabilityRepository.save(MemberAvailabilityDefaultEntity.builder()
                    .userId(userId)
                    .teamId(teamId)
                    .dayOfWeek(1)
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .preference(ShiftPreference.AVAILABLE)
                    .note("試練フィクスチャ")
                    .build());
            em.flush();
            em.clear();
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
                                + "VALUES (:email, 'シレン', 'テスト', 'シレン テスト', 'ACTIVE', "
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
