package com.mannschaft.app.equipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.time.LocalDateTime;
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
 * 認可根治戦役 Wave 2 トランシェ2B — equipment ドメイン（備品管理・貸出返却）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2B equipment 節。
 * equipment ドメインは {@code AccessControlService} が一切敷設されておらず、任意チーム/組織の
 * 備品を閲覧・作成・更新・削除・貸出・返却できる状態だった。</p>
 *
 * <p>金型: {@code TimetableScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA: teamB・orgB の ADMIN が
 * teamA・orgA の備品へアクセス）/ 非 ADMIN メンバー / 正当 ADMIN。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("equipment ドメイン（備品管理）認可契約テスト（試練）")
class EquipmentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentItemRepository itemRepository;

    @Autowired
    private EquipmentAssignmentRepository assignmentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long itemTeamAId;
    private Long itemOrgAId;
    private Long consumableItemTeamAId;
    private Long assignmentTeamAId;
    private Long assignmentTeamAId2;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("EQAUTHZ チームA");
        teamBId = insertTeam("EQAUTHZ チームB");
        orgAId = insertOrganization("EQAUTHZ 組織A");
        orgBId = insertOrganization("EQAUTHZ 組織B");

        adminTeamAId = insertUser("eqauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("eqauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("eqauthz-member-team-a@example.com");
        adminOrgAId = insertUser("eqauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("eqauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("eqauthz-member-org-a@example.com");
        outsiderId = insertUser("eqauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（TimetableScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        EquipmentItemEntity itemTeamA = itemRepository.save(EquipmentItemEntity.builder()
                .teamId(teamAId).name("EQAUTHZ サッカーボール").category("スポーツ用品")
                .quantity(5).assignedQuantity(1).status(EquipmentStatus.AVAILABLE)
                .isConsumable(false).qrCode("EQAUTHZ-QR-TEAM-A-" + System.nanoTime())
                .build());
        itemTeamAId = itemTeamA.getId();

        EquipmentItemEntity itemOrgA = itemRepository.save(EquipmentItemEntity.builder()
                .organizationId(orgAId).name("EQAUTHZ 会議用テーブル").category("備品")
                .quantity(3).assignedQuantity(0).status(EquipmentStatus.AVAILABLE)
                .isConsumable(false).qrCode("EQAUTHZ-QR-ORG-A-" + System.nanoTime())
                .build());
        itemOrgAId = itemOrgA.getId();

        EquipmentItemEntity consumableItemTeamA = itemRepository.save(EquipmentItemEntity.builder()
                .teamId(teamAId).name("EQAUTHZ 絆創膏").category("消耗品")
                .quantity(20).assignedQuantity(0).status(EquipmentStatus.AVAILABLE)
                .isConsumable(true).qrCode("EQAUTHZ-QR-CONSUME-A-" + System.nanoTime())
                .build());
        consumableItemTeamAId = consumableItemTeamA.getId();

        EquipmentAssignmentEntity assignmentTeamA = assignmentRepository.save(EquipmentAssignmentEntity.builder()
                .equipmentItemId(itemTeamAId).assignedToUserId(memberTeamAId).assignedByUserId(adminTeamAId)
                .quantity(1).assignedAt(LocalDateTime.now())
                .build());
        assignmentTeamAId = assignmentTeamA.getId();

        // 一括返却テスト用に別途返却対象を用意（在庫と assignedQuantity の整合は認可検証の主眼外のため簡略化）
        EquipmentAssignmentEntity assignmentTeamA2 = assignmentRepository.save(EquipmentAssignmentEntity.builder()
                .equipmentItemId(itemTeamAId).assignedToUserId(memberTeamAId).assignedByUserId(adminTeamAId)
                .quantity(1).assignedAt(LocalDateTime.now())
                .build());
        assignmentTeamAId2 = assignmentTeamA2.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/equipment（一覧・閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/equipment（一覧）")
    class ListEquipment {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/equipment（作成・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/equipment（作成）")
    class CreateEquipment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規備品");
            body.put("category", "スポーツ用品");
            body.put("quantity", 3);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/equipment/{id}（詳細・entity由来: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/equipment/{id}（詳細）")
    class GetEquipment {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのURLを叩く越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT/DELETE /teams/{teamId}/equipment/{id}（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT/DELETE /teams/{teamId}/equipment/{id}")
    class UpdateDeleteEquipment {

        @Test
        @DisplayName("非ADMINメンバーは更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別scope ADMINは削除403（BOLA）")
        void 別scopeADMINは削除403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/equipment/{id}", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminTeamAId);
            // 貸出中の資産は削除不可のため、貸出の無い組織側アイテムで検証する代わりに
            // チームAの未使用アイテムを追加作成して削除する。
            EquipmentItemEntity deletable = itemRepository.save(EquipmentItemEntity.builder()
                    .teamId(teamAId).name("EQAUTHZ 削除対象").category("雑品")
                    .quantity(1).assignedQuantity(0).status(EquipmentStatus.AVAILABLE)
                    .isConsumable(false).qrCode("EQAUTHZ-QR-DEL-" + System.nanoTime())
                    .build());
            em.flush();
            mockMvc.perform(delete("/api/v1/teams/{teamId}/equipment/{id}", teamAId, deletable.getId()))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後の名前");
            body.put("quantity", 5);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /teams/{teamId}/equipment/{id}/assign（貸出・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /teams/{teamId}/equipment/{id}/assign（貸出）")
    class AssignEquipment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/assign", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/assign", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/assign", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> assignBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assignedToUserId", memberTeamAId);
            body.put("quantity", 1);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. PATCH /teams/{teamId}/equipment/{id}/return（返却・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. PATCH /teams/{teamId}/equipment/{id}/return（返却）")
    class ReturnEquipment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/equipment/{id}/return", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(returnBody(assignmentTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/equipment/{id}/return", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(returnBody(assignmentTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/equipment/{id}/return", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(returnBody(assignmentTeamAId))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> returnBody(Long assignmentId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assignmentId", assignmentId);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST /teams/{teamId}/equipment/{id}/consume（消費・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /teams/{teamId}/equipment/{id}/consume（消費）")
    class ConsumeEquipment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/consume", teamAId, consumableItemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(consumeBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/consume", teamAId, consumableItemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(consumeBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> consumeBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("quantity", 1);
            body.put("consumedByUserId", memberTeamAId);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. POST/PATCH .../assign-bulk・return-bulk（一括操作: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 一括貸出・一括返却")
    class BulkOperations {

        @Test
        @DisplayName("非ADMINメンバーは一括貸出403")
        void 非ADMINメンバーは一括貸出403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/assign-bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一括貸出201")
        void 正当ADMINは一括貸出201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/equipment/{id}/assign-bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバーは一括返却403")
        void 非ADMINメンバーは一括返却403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/equipment/{id}/return-bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkReturnBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一括返却200")
        void 正当ADMINは一括返却200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/equipment/{id}/return-bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkReturnBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> bulkAssignBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assignedToUserId", memberTeamAId);
            entry.put("quantity", 1);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assignments", List.of(entry));
            return body;
        }

        private Map<String, Object> bulkReturnBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assignmentIds", List.of(assignmentTeamAId2));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET .../history・.../overdue（閲覧系: checkMembership、entity由来/scope直）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET history / overdue")
    class HistoryAndOverdue {

        @Test
        @DisplayName("非メンバーは履歴403")
        void 非メンバーは履歴403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}/history", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは履歴403（BOLA）")
        void 別scopeADMINは履歴403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}/history", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは履歴200")
        void 非ADMINメンバーは履歴200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/{id}/history", teamAId, itemTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーは遅延一覧403")
        void 非メンバーは遅延一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/overdue", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは遅延一覧200")
        void 非ADMINメンバーは遅延一覧200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/overdue", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET categories / qr-codes（閲覧系: checkMembership、scope直）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET categories / qr-codes")
    class CategoriesAndQrCodes {

        @Test
        @DisplayName("非メンバーはカテゴリ一覧403")
        void 非メンバーはカテゴリ一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/categories", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはカテゴリ一覧200")
        void 非ADMINメンバーはカテゴリ一覧200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/categories", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーはQRコード一覧403")
        void 非メンバーはQRコード一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/qr-codes", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーはQRコード一覧200")
        void 非ADMINメンバーはQRコード一覧200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/equipment/qr-codes", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. 組織スコープ（/organizations/{orgId}/equipment）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. 組織スコープ /organizations/{orgId}/equipment")
    class OrganizationScope {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/equipment", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は一覧403（BOLA）")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/equipment", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一覧200")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/equipment", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/equipment", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは作成403（BOLA）")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/equipment", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/equipment", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別scope ADMINは詳細取得403（BOLA）")
        void 別scopeADMINは詳細取得403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/equipment/{id}", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminOrgBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "乗っ取り更新");
            body.put("quantity", 1);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/equipment/{id}", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは削除403（BOLA）")
        void 別scopeADMINは削除403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/equipment/{id}", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        private Map<String, Object> createOrgBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規組織備品");
            body.put("category", "備品");
            body.put("quantity", 2);
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
                                + "VALUES (:email, 'EQAUTHZ', 'テスト', 'EQAUTHZ テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
