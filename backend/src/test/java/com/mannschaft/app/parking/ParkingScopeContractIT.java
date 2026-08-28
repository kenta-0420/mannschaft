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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2B: parking ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（parking 節）・
 * {@code AccessControlService}（{@code checkMembership}/{@code checkAdminOrAbove}）。
 * 金型: {@code TeamAdvertiserScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>担当スコープ（他は対象外）:</p>
 * <ul>
 *   <li>ParkingSpaceService 全メソッド + ParkingSettingsService: 閲覧=checkMembership、変更=checkAdminOrAbove</li>
 *   <li>ParkingApplicationService approve/reject/executeLottery/cancel:
 *       findById 全テナント串刺し是正（entity 由来 scope で checkAdminOrAbove。cancel は本人 or ADMIN）</li>
 *   <li>ParkingVisitorReservationService getDetail/approve/reject/checkIn/complete/cancel:
 *       来場者氏名・ナンバープレート PII の越境防止</li>
 *   <li>ParkingListingService update/delete（作成者 listedBy 一致）、
 *       ParkingSubleaseService approve（applicationId↔subleaseId 紐付け検証）</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("parking ドメイン API 契約テスト（認可根治 Wave2 トランシェ2B）")
class ParkingScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, false);

        teamAId = insertTeam("PK契約テストチームA");
        teamBId = insertTeam("PK契約テストチームB");

        adminAId = insertUser("pk-contract-admin-a@example.com");
        adminBId = insertUser("pk-contract-admin-b@example.com");
        memberAId = insertUser("pk-contract-member-a@example.com");
        outsiderId = insertUser("pk-contract-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ParkingSpaceService / ParkingSettingsService
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("区画(space)・設定(settings)")
    class SpaceAndSettings {

        @Test
        @DisplayName("非メンバーの区画一覧取得は403（COMMON_002）")
        void 非メンバーの区画一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/spaces", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの区画一覧取得は403（越境拒否）")
        void 他チームADMINの区画一覧は403() throws Exception {
            setAuthentication(adminBId); // チームBのADMINがチームAのURLを叩く

            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/spaces", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの区画一覧取得は200")
        void 正当ADMINの区画一覧は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/spaces", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは区画を作成できる（201）")
        void 正当ADMINは区画を作成できる() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceNumber", "A-101");
            body.put("spaceType", "INDOOR");

            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーの区画作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの区画作成は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceNumber", "A-102");
            body.put("spaceType", "INDOOR");

            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーの設定閲覧は200（checkMembershipのみ）")
        void 一般メンバーの設定閲覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般メンバーの設定更新は403（変更系はcheckAdminOrAbove）")
        void 一般メンバーの設定更新は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("maxSpacesPerUser", 2);
            body.put("maxVisitorReservationsPerDay", 3);
            body.put("visitorReservationMaxDaysAhead", 14);
            body.put("visitorReservationRequiresApproval", true);

            mockMvc.perform(put("/api/v1/teams/{teamId}/parking/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ParkingApplicationService（findById 全テナント串刺しBOLA是正）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("区画申請(application)")
    class Application {

        @Test
        @DisplayName("非ADMINメンバーの申請承認は403")
        void 非ADMINメンバーの申請承認は403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "APP-01", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/applications/{id}/approve", teamAId, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームの申請を承認しようとすると404（BOLA是正: entity由来scope検証）")
        void 他チームADMINによる越境承認は404() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "APP-02", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");

            // チームBのADMINが、自分のチームBのURLパスに「teamAの申請ID」を指定して承認を試みる
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/applications/{id}/approve", teamBId, appId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_004"));
        }

        @Test
        @DisplayName("正当ADMINの申請承認は200")
        void 正当ADMINの申請承認は200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "APP-03", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/applications/{id}/approve", teamAId, appId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));
        }

        @Test
        @DisplayName("申請者本人はADMINでなくても自分の申請をキャンセルできる（204）")
        void 申請者本人はADMINでなくても自分の申請をキャンセルできる() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "APP-04", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/parking/applications/{id}", teamAId, appId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他人の申請を本人でもADMINでもないメンバーがキャンセルすると403")
        void 他人の申請の第三者キャンセルは403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "APP-05", "ACCEPTING", adminAId);
            Long appId = insertParkingApplication(spaceId, memberAId, 1L, "PENDING");
            Long otherMemberId = insertUser("pk-contract-other-member@example.com");
            MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            em.flush();

            setAuthentication(otherMemberId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/parking/applications/{id}", teamAId, appId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ParkingVisitorReservationService（来場者PIIのBOLA是正）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("来場者予約(visitor-reservation)")
    class VisitorReservation {

        @Test
        @DisplayName("非メンバーの来場者予約詳細取得は403")
        void 非メンバーの来場者予約詳細は403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "VR-01", "NOT_ACCEPTING", adminAId);
            Long reservationId = insertVisitorReservation(spaceId, memberAId, "来場者太郎", "品川300あ12-34");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/visitor-reservations/{id}", teamAId, reservationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームの来場者予約を閲覧しようとすると404（PII越境是正）")
        void 他チームADMINによる越境閲覧は404() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "VR-02", "NOT_ACCEPTING", adminAId);
            Long reservationId = insertVisitorReservation(spaceId, memberAId, "来場者花子", "品川300あ56-78");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/visitor-reservations/{id}", teamBId, reservationId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_006"));
        }

        @Test
        @DisplayName("正当メンバーの来場者予約詳細取得は200")
        void 正当メンバーの来場者予約詳細は200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "VR-03", "NOT_ACCEPTING", adminAId);
            Long reservationId = insertVisitorReservation(spaceId, memberAId, "来場者次郎", "品川300あ90-12");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/visitor-reservations/{id}", teamAId, reservationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.visitorName").value("来場者次郎"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ParkingListingService（作成者一致検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("譲渡希望(listing)")
    class Listing {

        @Test
        @DisplayName("作成者以外が譲渡希望を更新すると403（ADMINでも作成者一致が必須）")
        void 作成者以外の更新は403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "LS-01", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long listingId = insertListing(spaceId, assignmentId, memberAId, "引越しのため");

            setAuthentication(adminAId); // 作成者(memberAId)ではないADMIN
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", "乗っ取り更新");

            mockMvc.perform(put("/api/v1/teams/{teamId}/parking/listings/{id}", teamAId, listingId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("作成者本人による譲渡希望の更新は200")
        void 作成者本人の更新は200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "LS-02", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long listingId = insertListing(spaceId, assignmentId, memberAId, "引越しのため");

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", "転勤のため");

            mockMvc.perform(put("/api/v1/teams/{teamId}/parking/listings/{id}", teamAId, listingId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reason").value("転勤のため"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ParkingSubleaseService（applicationId↔subleaseId 紐付け検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("サブリース(sublease)")
    class Sublease {

        @Test
        @DisplayName("別サブリースの申請IDで承認しようとすると404（紐付け未検証だったBOLAの是正）")
        void 別サブリースの申請での承認は404() throws Exception {
            Long spaceId1 = insertParkingSpace("TEAM", teamAId, "SL-01", "NOT_ACCEPTING", adminAId);
            Long assignment1 = insertAssignment(spaceId1, memberAId, adminAId);
            Long sublease1 = insertSublease(spaceId1, assignment1, memberAId, "サブリース1");

            Long spaceId2 = insertParkingSpace("TEAM", teamAId, "SL-02", "NOT_ACCEPTING", adminAId);
            Long otherMemberId = insertUser("pk-contract-sublease-other@example.com");
            Long assignment2 = insertAssignment(spaceId2, otherMemberId, adminAId);
            Long sublease2 = insertSublease(spaceId2, assignment2, otherMemberId, "サブリース2");
            Long foreignApplicationId = insertSubleaseApplication(sublease2, 999L, 1L);
            em.flush();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("applicationId", foreignApplicationId);

            // sublease1 に対して sublease2 所属の applicationId を承認しようとする
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/subleases/{id}/approve", teamAId, sublease1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_026"));
        }

        @Test
        @DisplayName("正しい紐付けのサブリース承認は200")
        void 正しい紐付けの承認は200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "SL-03", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long subleaseId = insertSublease(spaceId, assignmentId, memberAId, "サブリース3");
            Long applicationId = insertSubleaseApplication(subleaseId, 999L, 1L);
            em.flush();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("applicationId", applicationId);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/subleases/{id}/approve", teamAId, subleaseId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("MATCHED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Wave5: getSpaceIds 経由の read / 自己保有系 / サブリース管理に
    //        ParkingAccessGuard で membership/ADMIN を強制（旧 authz=0 の根治）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wave5: 一覧・読み取りの membership 強制（旧 authz=0）")
    class Wave5ReadMembership {

        @Test
        @DisplayName("非メンバーの申請一覧は403")
        void 非メンバーの申請一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/applications", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの申請一覧は200")
        void 正当メンバーの申請一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/applications", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのサブリース一覧は403")
        void 非メンバーのサブリース一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/subleases", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーのサブリース一覧は200")
        void 正当メンバーのサブリース一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/subleases", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの譲渡希望一覧は403")
        void 非メンバーの譲渡希望一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/listings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーの来場者予約一覧は403（PII一覧の membership 強制）")
        void 非メンバーの来場者予約一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/visitor-reservations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーのウォッチリスト一覧は403")
        void 非メンバーのウォッチリスト一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/parking/watchlist", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーのウォッチリスト追加は403（自己保有系も入口は membership）")
        void 非メンバーのウォッチリスト追加は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/watchlist", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーのウォッチリスト追加は201（自己保有系 write=member）")
        void 正当メンバーのウォッチリスト追加は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/watchlist", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非メンバーの来場者予約作成は403（作成入口の membership 強制）")
        void 非メンバーの来場者予約作成は403() throws Exception {
            setAuthentication(outsiderId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceId", 1L);
            body.put("visitorName", "来場者");
            body.put("visitorPlateNumber", "品川300あ00-00");
            body.put("reservedDate", "2999-01-01");
            body.put("timeFrom", "10:00:00");
            body.put("timeTo", "11:00:00");
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/visitor-reservations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Wave5: サブリースのライフサイクル操作は ADMIN 限定（BOLA根治の要・旧 authz=0）")
    class Wave5SubleaseManageAdmin {

        @Test
        @DisplayName("非ADMINメンバーのサブリース終了は403（guardがサービス到達前に遮断）")
        void 非ADMINメンバーのサブリース終了は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/subleases/{id}/terminate", teamAId, 999999L))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーのサブリース更新は403")
        void 非メンバーのサブリース更新は403() throws Exception {
            setAuthentication(outsiderId);
            // @Valid が guard より先に走るため、body は必須項目を充足させ bind時400を回避し認可(403)へ到達させる
            mockMvc.perform(put("/api/v1/teams/{teamId}/parking/subleases/{id}", teamAId, 999999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"更新\",\"pricePerMonth\":10000,\"availableFrom\":\"2026-08-01\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのサブリース終了は200")
        void 正当ADMINのサブリース終了は200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "SLT-03", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long subleaseId = insertSublease(spaceId, assignmentId, memberAId, "終了対象3");
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/subleases/{id}/terminate", teamAId, subleaseId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームのサブリースを終了しようとすると404（越境秘匿・二段防御の二段目）")
        void 他チームADMINによる越境終了は404() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "SLT-04", "NOT_ACCEPTING", adminAId);
            Long assignmentId = insertAssignment(spaceId, memberAId, adminAId);
            Long subleaseId = insertSublease(spaceId, assignmentId, memberAId, "終了対象4");
            em.flush();

            setAuthentication(adminBId); // teamB の ADMIN が teamB の URL に teamA のサブリースID を渡す
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/subleases/{id}/terminate", teamBId, subleaseId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_025"));
        }

        @Test
        @DisplayName("非ADMINメンバーの譲渡確定(transfer)は403（旧 authz=0・manage=admin）")
        void 非ADMINメンバーの譲渡確定は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/parking/listings/{id}/transfer", teamAId, 999999L))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Wave6: 割り当て操作系（assign / release / bulkAssign）の認可
    //        currentUserId を assignedBy（監査欄）に記録するだけで認可判定に
    //        使っていなかった取りこぼしの根治。兄弟 ParkingSpaceService（Wave2 2B）に揃え、
    //        変更系＝checkAdminOrAbove（403 COMMON_002）・越境ID＝404 PARKING_001。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wave6: 区画割り当て操作(assign/release/bulk-assign)は ADMIN 限定")
    class Wave6AssignmentAuthz {

        @Test
        @DisplayName("非メンバーの区画割り当ては403（COMMON_002）")
        void 非メンバーの割り当ては403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "AS-01", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/assign", teamAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの区画割り当ては403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの割り当ては403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "AS-02", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/assign", teamAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの区画割り当ては200")
        void 正当ADMINの割り当ては200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "AS-03", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/assign", teamAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームの区画を割り当てようとすると404（越境秘匿）")
        void 他チームADMINによる越境割り当ては404() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "AS-04", "NOT_ACCEPTING", adminAId);
            em.flush();

            // teamB の ADMIN が teamB の URL に teamA の区画ID を渡す
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/assign", teamBId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(adminBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_001"));
        }

        @Test
        @DisplayName("非ADMINメンバーの区画解除は403")
        void 非ADMINメンバーの解除は403() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "RL-01", "NOT_ACCEPTING", adminAId);
            insertAssignment(spaceId, memberAId, adminAId);
            em.flush();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/release", teamAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの区画解除は204")
        void 正当ADMINの解除は204() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "RL-02", "NOT_ACCEPTING", adminAId);
            insertAssignment(spaceId, memberAId, adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/release", teamAId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他チームADMINが自チームURLで他チームの区画を解除しようとすると404（越境秘匿）")
        void 他チームADMINによる越境解除は404() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "RL-03", "NOT_ACCEPTING", adminAId);
            insertAssignment(spaceId, memberAId, adminAId);
            em.flush();

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/{id}/release", teamBId, spaceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("PARKING_001"));
        }

        @Test
        @DisplayName("非メンバーの一括割り当ては403（スコープ入口で遮断）")
        void 非メンバーの一括割り当ては403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/bulk-assign", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody(999999L, outsiderId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの一括割り当ては403")
        void 非ADMINメンバーの一括割り当ては403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/bulk-assign", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkAssignBody(999999L, memberAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの一括割り当ては200")
        void 正当ADMINの一括割り当ては200() throws Exception {
            Long spaceId = insertParkingSpace("TEAM", teamAId, "BA-01", "NOT_ACCEPTING", adminAId);
            em.flush();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/parking/spaces/bulk-assign", teamAId)
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

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
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
                                + "VALUES (:email, 'PKContract', 'テスト', 'PK契約テスト', 'ACTIVE', "
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
        // test プロファイルは ddl-auto=create のため、parking_applications のスキーマは
        // ParkingApplicationEntity から生成される。source_type / priority は @Column(nullable=false)
        // だが @Builder.Default は DB デフォルトを生成しないため、native INSERT では明示指定が必須。
        // （is_proxy_input は columnDefinition="TINYINT(1) DEFAULT 0" で DB デフォルトを持つため省略可）
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

    private Long insertVisitorReservation(Long spaceId, Long reservedBy, String visitorName, String plateNumber) {
        em.createNativeQuery(
                        "INSERT INTO parking_visitor_reservations (space_id, reserved_by, visitor_name, "
                                + "visitor_plate_number, reserved_date, time_from, time_to, status, created_at, updated_at) "
                                + "VALUES (:spaceId, :reservedBy, :name, :plate, "
                                + "DATE_ADD(CURDATE(), INTERVAL 1 DAY), '10:00:00', '12:00:00', 'PENDING_APPROVAL', NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("reservedBy", reservedBy)
                .setParameter("name", visitorName)
                .setParameter("plate", plateNumber)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_visitor_reservations").getSingleResult()).longValue();
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

    private Long insertListing(Long spaceId, Long assignmentId, Long listedBy, String reason) {
        em.createNativeQuery(
                        "INSERT INTO parking_listings (space_id, assignment_id, listed_by, reason, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:spaceId, :assignmentId, :listedBy, :reason, 'OPEN', NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("assignmentId", assignmentId)
                .setParameter("listedBy", listedBy)
                .setParameter("reason", reason)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_listings").getSingleResult()).longValue();
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

    private Long insertSubleaseApplication(Long subleaseId, Long userId, Long vehicleId) {
        em.createNativeQuery(
                        "INSERT INTO parking_sublease_applications (sublease_id, user_id, vehicle_id, status, created_at) "
                                + "VALUES (:subleaseId, :userId, :vehicleId, 'PENDING', NOW())")
                .setParameter("subleaseId", subleaseId)
                .setParameter("userId", userId)
                .setParameter("vehicleId", vehicleId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_sublease_applications").getSingleResult()).longValue();
    }
}
