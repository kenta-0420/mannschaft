package com.mannschaft.app.parking;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5 — parking ドメイン ORGANIZATION スコープ認可契約テスト（試練）。
 *
 * <p>{@link ParkingScopeContractIT}（TEAM 版）の双子構成。{@link com.mannschaft.app.parking.service.ParkingAccessGuard}
 * は組織/チーム両系統の scope 系 Controller（申請・譲渡希望・サブリース・来場者予約・ウォッチリスト）の
 * public 入口に {@code SCOPE_TYPE="ORGANIZATION"} / {@code "TEAM"} で共通に敷かれる。TEAM 側だけを検証して
 * ORG 系の番人テストがゼロだった facility #2345 の轍（ORG 系欠落での差し戻し）を踏まないよう、本テストが
 * ORG 版を補完する。</p>
 *
 * <p>ガードは TEAM/ORG 共通メソッドを scope 違いで呼ぶだけのため、31EP 全数ではなく
 * <b>ガードメソッド × 粒度（member 読み取り / member 自己 write / admin manage）× 越境秘匿</b>を
 * 代表 EP で検証する（facility ORG 版と同方針）。</p>
 *
 * <p>認可モデル（{@code ParkingAccessGuard}）:</p>
 * <ul>
 *   <li>read（一覧/詳細/決済参照）= {@code requireScopeMember}（非メンバー 403 COMMON_002）。</li>
 *   <li>自己申請・自己保有系（申請・申込・来場者予約作成・ウォッチリスト等）= {@code requireScopeMember}
 *       （本人性は各 Service が最終判定）。</li>
 *   <li>manage（承認/却下/抽選/譲渡確定/サブリース更新・削除・承認・終了/来場者承認等）= {@code requireScopeAdmin}
 *       （非 ADMIN 403）。越境 ID は既存の scope 束縛が 404 で存在秘匿（二段防御）。</li>
 * </ul>
 *
 * <p>ADMIN 役は {@code checkMembership}（memberships 表）と {@code checkAdminOrAbove}（user_roles 表）の
 * 両方を満たすよう二重に seed する（Wave 踏襲の既知の地雷）。ORG では {@code insertUserRole} の
 * {@code team_id=null / organization_id=orgId} で発番する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("parking ドメイン ORGANIZATION スコープ認可契約テスト（Wave5）")
class ParkingOrgScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long adminAId;   // orgA の ADMIN（正当）
    private Long adminBId;   // orgB の ADMIN（越境テスト用）
    private Long memberAId;  // orgA の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("PK-ORG契約テスト組織A");
        orgBId = insertOrganization("PK-ORG契約テスト組織B");

        adminAId = insertUser("pk-org-contract-admin-a@example.com");
        adminBId = insertUser("pk-org-contract-admin-b@example.com");
        memberAId = insertUser("pk-org-contract-member-a@example.com");
        outsiderId = insertUser("pk-org-contract-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        // memberA は組織Aの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // outsiderId はどちらの組織にも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 一覧・読み取り（requireScopeMember・スコープ宣言型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 一覧・読み取り（requireScopeMember）")
    class ReadMembership {

        @Test
        @DisplayName("非メンバーの申請一覧は403")
        void 非メンバーの申請一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/parking/applications", orgAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの申請一覧は200")
        void 正当メンバーの申請一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/parking/applications", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのサブリース一覧は403")
        void 非メンバーのサブリース一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/parking/subleases", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーの来場者予約一覧は403（PII一覧の membership 強制）")
        void 非メンバーの来場者予約一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{organizationId}/parking/visitor-reservations", orgAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 自己保有系の write（requireScopeMember）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 自己保有系 write（requireScopeMember）")
    class SelfWriteMembership {

        @Test
        @DisplayName("非メンバーのウォッチリスト追加は403")
        void 非メンバーのウォッチリスト追加は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/watchlist", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーのウォッチリスト追加は201")
        void 正当メンバーのウォッチリスト追加は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/watchlist", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 承認系 manage（requireScopeAdmin）— 非ADMIN403 / 越境404 / ADMIN200
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 申請承認（requireScopeAdmin）")
    class ApproveAdmin {

        @Test
        @DisplayName("非ADMINメンバーの申請承認は403")
        void 非ADMINメンバーの申請承認は403() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAPP-01", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");
            em.flush();

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/applications/{id}/approve", orgAId, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織の申請を承認しようとすると404（越境秘匿・二段防御の二段目）")
        void 他組織ADMINによる越境承認は404() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAPP-02", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");
            em.flush();

            setAuthentication(adminBId); // 組織Bの ADMIN が、組織Bの URL パスに組織Aの申請ID を指定
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/applications/{id}/approve", orgBId, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_004"));
        }

        @Test
        @DisplayName("正当ADMINの申請承認は200")
        void 正当ADMINの申請承認は200() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAPP-03", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/applications/{id}/approve", orgAId, appId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. サブリースのライフサイクル操作は ADMIN 限定（BOLA根治の要・旧 authz=0）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. サブリース終了（requireScopeAdmin）")
    class SubleaseTerminateAdmin {

        @Test
        @DisplayName("非ADMINメンバーのサブリース終了は403")
        void 非ADMINメンバーのサブリース終了は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/subleases/{id}/terminate", orgAId, 999999L))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのサブリース終了は200")
        void 正当ADMINのサブリース終了は200() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OSLT-01", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long subleaseId = insertSublease(spaceId, assignmentId, memberAId, "組織終了対象1");
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/subleases/{id}/terminate", orgAId, subleaseId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織のサブリースを終了しようとすると404（越境秘匿）")
        void 他組織ADMINによる越境終了は404() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OSLT-02", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long subleaseId = insertSublease(spaceId, assignmentId, memberAId, "組織終了対象2");
            em.flush();

            setAuthentication(adminBId); // 組織Bの ADMIN が組織Bの URL に組織AのサブリースID を渡す
            mockMvc.perform(patch("/api/v1/organizations/{organizationId}/parking/subleases/{id}/terminate", orgBId, subleaseId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_025"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 区画CRUD（ParkingSpaceService が entity 由来 scope で敷済み・ORG 系の裏取り）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 区画作成（ParkingSpaceService・ORG scope）")
    class SpaceCreate {

        @Test
        @DisplayName("非ADMINメンバーの区画作成は403")
        void 非ADMINメンバーの区画作成は403() throws Exception {
            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceNumber", "O-101");
            body.put("spaceType", "INDOOR");
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの区画作成は201")
        void 正当ADMINの区画作成は201() throws Exception {
            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceNumber", "O-102");
            body.put("spaceType", "INDOOR");
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. Wave6: 割り当て操作系（assign / release / bulkAssign）の ORG 認可
    //    ParkingAssignmentService は currentUserId を assignedBy に記録するのみで
    //    認可判定に使っていなかった。兄弟 ParkingSpaceService に揃えて
    //    変更系＝checkAdminOrAbove（403 COMMON_002）・越境ID＝404 PARKING_001。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. Wave6: 区画割り当て操作(assign/release/bulk-assign)は ADMIN 限定")
    class Wave6AssignmentAuthz {

        @Test
        @DisplayName("非メンバーの区画割り当ては403（COMMON_002）")
        void 非メンバーの割り当ては403() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAS-01", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/assign", orgAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの区画割り当ては403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの割り当ては403() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAS-02", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/assign", orgAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの区画割り当ては200")
        void 正当ADMINの割り当ては200() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAS-03", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/assign", orgAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織の区画を割り当てようとすると404（越境秘匿）")
        void 他組織ADMINによる越境割り当ては404() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OAS-04", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/assign", orgBId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(adminBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_001"));
        }

        @Test
        @DisplayName("非ADMINメンバーの区画解除は403")
        void 非ADMINメンバーの解除は403() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "ORL-01", "NOT_ACCEPTING", adminAId);
            insertAssignment(spaceId, memberAId, adminAId);
            em.flush();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/release", orgAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの区画解除は204")
        void 正当ADMINの解除は204() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "ORL-02", "NOT_ACCEPTING", adminAId);
            insertAssignment(spaceId, memberAId, adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/{id}/release", orgAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("非メンバーの一括割り当ては403（スコープ入口で遮断）")
        void 非メンバーの一括割り当ては403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/bulk-assign", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody(999999L, outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの一括割り当ては403")
        void 非ADMINメンバーの一括割り当ては403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/bulk-assign", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody(999999L, memberAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの一括割り当ては200")
        void 正当ADMINの一括割り当ては200() throws Exception {
            Long spaceId = insertParkingSpace("ORGANIZATION", orgAId, "OBA-01", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/organizations/{organizationId}/parking/spaces/bulk-assign", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody(spaceId, memberAId))))
                    .andExpect(status().isOk());
        }

        /** assign の @Valid 必須項目（userId）を充足させ、bind時400ではなく認可判定へ到達させる。 */
        private Map<String, Object> assignBody(Long targetUserId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", targetUserId);
            return body;
        }

        /** bulk-assign の @Valid 必須項目（assignments[].spaceId/userId）を充足させる。 */
        private Map<String, Object> bulkAssignBody(Long spaceId, Long targetUserId) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("spaceId", spaceId);
            item.put("userId", targetUserId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assignments", List.of(item));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
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
                                + "VALUES (:email, 'PKOrgContract', 'テスト', 'PK-ORG契約テスト', 'ACTIVE', "
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

    private Long insertParkingSpace(String scopeType, Long scopeId, String spaceNumber,
                                     String applicationStatus, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO parking_spaces (scope_type, scope_id, space_number, space_type, "
                                + "status, application_status, created_by, created_at, updated_at) "
                                + "VALUES (:st, :sid, :num, 'INDOOR', 'VACANT', :appStatus, :createdBy, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("num", spaceNumber)
                .setParameter("appStatus", applicationStatus)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM parking_spaces WHERE scope_type = :st AND scope_id = :sid AND space_number = :num")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("num", spaceNumber)
                .getSingleResult()).longValue();
    }

    private Long insertParkingApplication(Long spaceId, Long userId, Long vehicleId, String status) {
        // test プロファイルは ddl-auto=create のため source_type / priority は @Column(nullable=false)。
        // @Builder.Default は DB デフォルトを生成しないため native INSERT では明示指定が必須。
        em.createNativeQuery(
                        "INSERT INTO parking_applications (space_id, user_id, vehicle_id, source_type, priority, "
                                + "status, created_at) "
                                + "VALUES (:spaceId, :userId, :vehicleId, 'VACANCY', 0, :status, NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("userId", userId)
                .setParameter("vehicleId", vehicleId)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_applications").getSingleResult()).longValue();
    }

    private Long insertAssignment(Long spaceId, Long userId, Long assignedBy) {
        em.createNativeQuery(
                        "INSERT INTO parking_assignments (space_id, user_id, assigned_by, assigned_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:spaceId, :userId, :assignedBy, NOW(), NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("userId", userId)
                .setParameter("assignedBy", assignedBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_assignments").getSingleResult()).longValue();
    }

    private Long insertSublease(Long spaceId, Long assignmentId, Long offeredBy, String title) {
        em.createNativeQuery(
                        "INSERT INTO parking_subleases (space_id, assignment_id, offered_by, title, "
                                + "price_per_month, payment_method, available_from, status, created_at, updated_at) "
                                + "VALUES (:spaceId, :assignmentId, :offeredBy, :title, 10000, 'DIRECT', "
                                + "CURDATE(), 'OPEN', NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("assignmentId", assignmentId)
                .setParameter("offeredBy", offeredBy)
                .setParameter("title", title)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_subleases").getSingleResult()).longValue();
    }
}
