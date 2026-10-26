package com.mannschaft.app.facility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.facility.repository.FacilityBookingPaymentRepository;
import com.mannschaft.app.facility.repository.FacilityBookingRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import com.mannschaft.app.facility.repository.SharedFacilityRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * 認可根治戦役 Wave5 — facility ドメイン（施設・予約・設定・統計）ORGANIZATION スコープ認可契約テスト。
 *
 * <p>{@link FacilityScopeContractIT}（TEAM 版）の完全な双子構成。PR #2345 は
 * {@link com.mannschaft.app.facility.service.FacilityAccessGuard} を全 Controller の public 入口に敷いたが、
 * 契約 IT は TEAM 系 3 Controller しか検証しておらず、ORG 系 3 Controller
 * （{@code OrgFacilityController}=15EP / {@code OrgFacilityBookingController}=13EP /
 * {@code OrgFacilitySettingsController}=3EP・計 31EP）の番人テストがゼロだった。本テストが ORG 版を補完する。</p>
 *
 * <p>ガードは TEAM/ORG で共通の {@code FacilityAccessGuard} メソッドを {@code SCOPE_TYPE="ORGANIZATION"} で呼ぶため、
 * ガードクラス単位の網羅（各ガードメソッド × read/write/owner・スコープ宣言型/entity 由来型の分岐）を代表 EP で検証する。
 * 31EP 全数の逐一検証は不要（TEAM 側もガードクラス単位の網羅）。</p>
 *
 * <p>認可モデル（{@code FacilityAccessGuard}）:</p>
 * <ul>
 *   <li><b>スコープ宣言型 EP</b>（一覧/作成/設定/統計/カレンダー。URL パスが scope を明示）:
 *       {@code requireScopeMember}（read）/{@code requireScopeAdmin}（write）。非メンバーは 403（COMMON_002）。</li>
 *   <li><b>entity 由来 scope の EP</b>（施設 id / 予約 id 直指定）: entity を fetch → entity 由来 scope で認可し、
 *       URL パスの scope と食い違う越境 id は 404（{@code FACILITY_001}/{@code FACILITY_006}）で存在秘匿する（BOLA 対策）。</li>
 *   <li>予約の更新・キャンセル・支払い参照のみ {@code requireBookingOwnerOrAdmin}（予約者本人 or ADMIN）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("facility ドメイン（施設/予約/設定/統計）ORGANIZATION スコープ認可契約テスト（Wave5）")
class FacilityOrgScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SharedFacilityRepository facilityRepository;

    @Autowired
    private FacilityBookingRepository bookingRepository;

    @Autowired
    private FacilityUsageRuleRepository usageRuleRepository;

    @Autowired
    private FacilityBookingPaymentRepository paymentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;

    private Long adminAId;   // orgA の ADMIN（正当）
    private Long memberAId;  // orgA の非 ADMIN メンバー（bookingA の予約者本人）
    private Long memberCId;  // orgA の非 ADMIN メンバー（bookingA の予約者ではない＝他人メンバー）
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long facilityAId; // orgA の施設
    private Long facilityBId; // orgB の施設（越境アクセステスト用）

    private Long bookingAId;  // facilityA(orgA) の予約。予約者 = memberA
    private Long bookingBId;  // facilityB(orgB) の予約（越境アクセステスト用）

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("FACAUTHZ 組織A");
        orgBId = insertOrganization("FACAUTHZ 組織B");

        adminAId = insertUser("facauthz-org-admin-a@example.com");
        memberAId = insertUser("facauthz-org-member-a@example.com");
        memberCId = insertUser("facauthz-org-member-c@example.com");
        outsiderId = insertUser("facauthz-org-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        // ORG では insertUserRole の team_id=null / organization_id=orgId で発番する。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberCId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        SharedFacilityEntity facilityA = facilityRepository.save(SharedFacilityEntity.builder()
                .scopeType("ORGANIZATION").scopeId(orgAId).name("FACAUTHZ 組織会議室A")
                .facilityType(FacilityType.MEETING_ROOM).capacity(10).createdBy(adminAId).build());
        facilityAId = facilityA.getId();

        SharedFacilityEntity facilityB = facilityRepository.save(SharedFacilityEntity.builder()
                .scopeType("ORGANIZATION").scopeId(orgBId).name("FACAUTHZ 組織会議室B")
                .facilityType(FacilityType.MEETING_ROOM).capacity(10).createdBy(outsiderId).build());
        facilityBId = facilityB.getId();

        // 施設を repository 直投入したため、createFacility が自動生成するデフォルト利用ルールが無い。
        // rules 参照/更新 EP の正当系（200）が USAGE_RULE_NOT_FOUND にならないよう facilityA に投入する。
        usageRuleRepository.save(FacilityUsageRuleEntity.builder().facilityId(facilityAId).build());

        FacilityBookingEntity bookingA = bookingRepository.save(FacilityBookingEntity.builder()
                .facilityId(facilityAId).bookedBy(memberAId)
                .bookingDate(LocalDate.now().plusDays(3))
                .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(12, 0)).slotCount(4)
                .status(BookingStatus.PENDING_APPROVAL)
                .usageFee(BigDecimal.valueOf(2000)).equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000)).build());
        bookingAId = bookingA.getId();

        FacilityBookingEntity bookingB = bookingRepository.save(FacilityBookingEntity.builder()
                .facilityId(facilityBId).bookedBy(outsiderId)
                .bookingDate(LocalDate.now().plusDays(3))
                .timeFrom(LocalTime.of(10, 0)).timeTo(LocalTime.of(12, 0)).slotCount(4)
                .status(BookingStatus.PENDING_APPROVAL)
                .usageFee(BigDecimal.valueOf(2000)).equipmentFee(BigDecimal.ZERO)
                .totalFee(BigDecimal.valueOf(2000)).build());
        bookingBId = bookingB.getId();

        // bookingA の支払い行（予約者本人=memberA による支払い取得の正当系 200 用）。
        paymentRepository.save(FacilityBookingPaymentEntity.builder()
                .bookingId(bookingAId).payerUserId(memberAId)
                .amount(BigDecimal.valueOf(2000)).build());

        em.flush();
        em.clear();
    }

    private String facilities(Long orgId) {
        return "/api/v1/organizations/" + orgId + "/facilities";
    }

    private String bookings(Long orgId) {
        return "/api/v1/organizations/" + orgId + "/facilities/bookings";
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /facilities（一覧・requireScopeMember: 非メンバー403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /facilities（施設一覧・requireScopeMember）")
    class ListFacilities {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(orgAId))).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId))).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /facilities/{id}（詳細・requireFacilityMember: 越境404 / 非メンバー403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /facilities/{id}（施設詳細・requireFacilityMember）")
    class GetFacility {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityAId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境ID（orgAパスでorgB施設）は404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityAId)).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /facilities（作成・requireScopeAdmin: 非ADMIN403 / ADMIN201）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /facilities（施設作成・requireScopeAdmin）")
    class CreateFacility {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(facilities(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFacilityBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(facilities(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFacilityBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createFacilityBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "FACAUTHZ 組織新規室" + System.nanoTime());
            body.put("facilityType", "MEETING_ROOM");
            body.put("capacity", 8);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT/DELETE /facilities/{id}（更新・削除・requireFacilityAdmin: 越境404 / 非ADMIN403 / ADMIN成功）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT/DELETE /facilities/{id}（requireFacilityAdmin）")
    class UpdateDeleteFacility {

        @Test
        @DisplayName("越境ID更新は404")
        void 越境ID更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(orgAId) + "/" + facilityBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー更新は403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(orgAId) + "/" + facilityAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMIN更新は200")
        void 正当ADMIN更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(orgAId) + "/" + facilityAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境ID削除は404")
        void 越境ID削除は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(facilities(orgAId) + "/" + facilityBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN削除は204")
        void 正当ADMIN削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(facilities(orgAId) + "/" + facilityAId)).andExpect(status().isNoContent());
        }

        private Map<String, Object> updateFacilityBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "FACAUTHZ 組織更新室");
            body.put("facilityType", "MEETING_ROOM");
            body.put("capacity", 12);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET/PUT /facilities/{id}/rules（requireFacilityMember 参照 / requireFacilityAdmin 更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET/PUT /facilities/{id}/rules")
    class UsageRule {

        @Test
        @DisplayName("越境IDルール取得は404")
        void 越境IDルール取得は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityBId + "/rules"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーはルール取得200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityAId + "/rules"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーはルール更新403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(orgAId) + "/" + facilityAId + "/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはルール更新200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(orgAId) + "/" + facilityAId + "/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET/POST /facilities/{id}/equipment（requireFacilityMember 参照 / requireFacilityAdmin 作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET/POST /facilities/{id}/equipment")
    class Equipment {

        @Test
        @DisplayName("越境ID備品一覧は404")
        void 越境ID一覧は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityBId + "/equipment"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは備品一覧200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId) + "/" + facilityAId + "/equipment"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは備品作成403")
        void 非ADMIN作成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(facilities(orgAId) + "/" + facilityAId + "/equipment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "FACAUTHZ 組織備品"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは備品作成201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(facilities(orgAId) + "/" + facilityAId + "/equipment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "FACAUTHZ 組織備品" + System.nanoTime()))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET/PUT /facilities/settings（設定・requireScopeMember 参照 / requireScopeAdmin 更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET/PUT /facilities/settings")
    class Settings {

        @Test
        @DisplayName("非メンバーは設定取得403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(orgAId) + "/settings")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは設定取得200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId) + "/settings")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは設定更新403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(orgAId) + "/settings")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは設定更新200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(orgAId) + "/settings")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /facilities/stats（統計・requireScopeAdmin: ADMIN限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /facilities/stats（requireScopeAdmin・ADMIN限定）")
    class Stats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(orgAId) + "/stats")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（売上・手数料は運営側材料ゆえ非公開）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(orgAId) + "/stats")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(orgAId) + "/stats")).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET /bookings（予約一覧・requireScopeMember）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET /bookings（予約一覧・requireScopeMember）")
    class ListBookings {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(orgAId))).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(bookings(orgAId))).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. POST /bookings（予約作成・requireFacilityMember: 越境facilityId404 / 非メンバー403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. POST /bookings（予約作成・requireFacilityMember）")
    class CreateBooking {

        @Test
        @DisplayName("越境facilityId（orgAパスでorgB施設予約）は404")
        void 越境facilityIdは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(bookings(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBookingBody(facilityBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(bookings(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBookingBody(facilityAId))))
                    .andExpect(status().isForbidden());
        }

        private Map<String, Object> createBookingBody(Long facilityId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("facilityId", facilityId);
            body.put("bookingDate", LocalDate.now().plusDays(4).toString());
            body.put("timeFrom", "10:00:00");
            body.put("timeTo", "12:00:00");
            body.put("purpose", "打ち合わせ");
            body.put("attendeeCount", 3);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. GET /bookings/{id}（予約詳細・requireBookingMember: 越境404 / 非メンバー403 / メンバー200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. GET /bookings/{id}（予約詳細・requireBookingMember）")
    class GetBooking {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingAId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境ID（orgAパスでorgB予約）は404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingAId)).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. DELETE /bookings/{id}（キャンセル・requireBookingOwnerOrAdmin: 越境404 / 本人200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. DELETE /bookings/{id}（キャンセル・requireBookingOwnerOrAdmin）")
    class CancelBooking {

        @Test
        @DisplayName("越境ID（orgAパスでorgB予約）は404")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(bookings(orgAId) + "/" + bookingBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("cancellationReason", "越境"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("予約者本人はキャンセル200")
        void 本人は200() throws Exception {
            setAuth(memberAId); // bookingA の予約者
            mockMvc.perform(delete(bookings(orgAId) + "/" + bookingAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("cancellationReason", "都合が悪くなった"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. PATCH /bookings/{id}/approve（承認・requireBookingAdmin: 越境404 / 非ADMIN403 / ADMIN200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. PATCH /bookings/{id}/approve（承認・requireBookingAdmin）")
    class ApproveBooking {

        @Test
        @DisplayName("越境ID承認は404")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingBId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー（予約者本人でも）承認は403")
        void 非ADMINは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingAId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは承認200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingAId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認します"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. 支払い系（GET /payment=requireBookingOwnerOrAdmin: 越境404・他人403・本人200 /
    //     PATCH /payment/confirm=requireBookingAdmin: 越境404・非ADMIN403・ADMIN200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. GET /payment（本人orADMIN）・PATCH /payment/confirm（ADMIN）")
    class Payment {

        @Test
        @DisplayName("越境ID支払い取得は404")
        void 越境ID取得は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingBId + "/payment"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("他人（非owner非adminメンバー）支払い取得は403")
        void 他人メンバー取得は403() throws Exception {
            setAuth(memberCId); // orgA メンバーだが bookingA の予約者ではない
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバー支払い取得は403")
        void 非メンバー取得は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("予約者本人は支払い取得200")
        void 予約者本人取得は200() throws Exception {
            setAuth(memberAId); // bookingA の予約者本人
            mockMvc.perform(get(bookings(orgAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境ID支払い確認は404")
        void 越境ID確認は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingBId + "/payment/confirm"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー支払い確認は403")
        void 非ADMIN確認は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingAId + "/payment/confirm"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは支払い確認200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(orgAId) + "/" + bookingAId + "/payment/confirm"))
                    .andExpect(status().isOk());
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
                                + "VALUES (:email, 'FACAUTHZ', 'テスト', 'FACAUTHZ テスト', 'ACTIVE', "
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
}
