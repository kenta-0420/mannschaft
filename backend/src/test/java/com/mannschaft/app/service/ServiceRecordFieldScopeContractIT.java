package com.mannschaft.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — service ドメイン（F07.1 カスタムフィールド定義・設定）API 契約テスト。
 *
 * <p>{@code ServiceRecordFieldController} が委譲する {@code ServiceRecordFieldService} は
 * {@code AccessControlService} を保持しておらず、スコープ認可が未回収のまま残っていた構造だった。
 * 本テストは 7 エンドポイントへ敷設した認可の契約を固定する。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA: teamB の ADMIN が teamA の
 * フィールドへアクセス）/ 非 ADMIN メンバー / 正当 ADMIN。加えて
 * <b>path の teamId と entity の teamId の不一致（BOLA）は 404 で存在秘匿</b>することを検証する。</p>
 *
 * <p>権限粒度は兄弟 {@code ServiceRecordService} に揃えた:
 * 参照（listFields / getSettings）= {@code checkMembership}、
 * 変更（createField / updateField / deactivateField / updateSortOrder / updateSettings）
 * = {@code checkAdminOrAbove}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("service ドメイン（カスタムフィールド・設定）認可契約テスト")
class ServiceRecordFieldScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRecordFieldRepository fieldRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long fieldTeamAId;   // TEAM A のフィールド
    private Long fieldTeamBId;   // TEAM B のフィールド（BOLA 検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SRFAUTHZ チームA");
        teamBId = insertTeam("SRFAUTHZ チームB");

        adminTeamAId = insertUser("srfauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("srfauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("srfauthz-member-team-a@example.com");
        outsiderId = insertUser("srfauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        ServiceRecordFieldEntity fieldTeamA = fieldRepository.save(ServiceRecordFieldEntity.builder()
                .teamId(teamAId).fieldName("SRFAUTHZ 体温").fieldType(FieldType.TEXT)
                .sortOrder(1)
                .build());
        fieldTeamAId = fieldTeamA.getId();

        ServiceRecordFieldEntity fieldTeamB = fieldRepository.save(ServiceRecordFieldEntity.builder()
                .teamId(teamBId).fieldName("SRFAUTHZ 血圧").fieldType(FieldType.TEXT)
                .sortOrder(1)
                .build());
        fieldTeamBId = fieldTeamB.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/service-record-fields（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/service-record-fields（一覧）")
    class ListFields {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-record-fields", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-record-fields", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（参照はmembershipで足りる）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-record-fields", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-record-fields", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/service-record-fields（作成: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/service-record-fields（作成）")
    class CreateField {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-record-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-record-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-record-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-record-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldName", "新規フィールド");
            body.put("fieldType", "TEXT");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT /teams/{teamId}/service-record-fields/{id}（更新: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT /teams/{teamId}/service-record-fields/{id}（更新）")
    class UpdateField {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのフィールドIDを自チームのteamIdで叩くと404（存在秘匿・BOLA）")
        void 越境フィールドIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldName", "更新後フィールド");
            body.put("fieldType", "TEXT");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. DELETE /teams/{teamId}/service-record-fields/{id}（無効化: entity 由来 scope）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE /teams/{teamId}/service-record-fields/{id}（無効化）")
    class DeactivateField {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのフィールドIDを自チームのteamIdで叩くと404（存在秘匿・BOLA）")
        void 越境フィールドIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-record-fields/{id}", teamAId, fieldTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PATCH /teams/{teamId}/service-record-fields/sort-order（並び替え）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. PATCH /teams/{teamId}/service-record-fields/sort-order（並び替え）")
    class UpdateSortOrder {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-record-fields/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortBody(fieldTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-record-fields/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortBody(fieldTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのフィールドIDを混ぜると404（存在秘匿・BOLA）")
        void 越境フィールドIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-record-fields/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortBody(fieldTeamBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-record-fields/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortBody(fieldTeamAId))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> sortBody(Long fieldId) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fieldId", fieldId);
            entry.put("sortOrder", 3);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldOrders", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /teams/{teamId}/service-records/settings（設定取得: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /teams/{teamId}/service-records/settings（設定取得）")
    class GetSettings {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（参照はmembershipで足りる）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/settings", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PUT /teams/{teamId}/service-records/settings（設定更新: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PUT /teams/{teamId}/service-records/settings（設定更新）")
    class UpdateSettings {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(settingsBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> settingsBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("isDashboardEnabled", true);
            body.put("isReactionEnabled", true);
            return body;
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
                                + "VALUES (:email, 'SRFAUTHZ', 'テスト', 'SRFAUTHZ テスト', 'ACTIVE', "
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
