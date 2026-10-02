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
 * 認可根治戦役 Wave5 早馬 — facility ドメイン（施設・予約・設定・統計）API 認可契約テスト（試練）。
 *
 * <p>正本: 早馬（殿からの直接指示）。facility ドメインは {@code FacilityBookingService}/
 * {@code FacilityService} が {@code AccessControlService} を注入すらしておらず全 EP で認可が皆無だった
 * （任意ログインユーザーが bookingId/facilityId 総当りで他組織・他チームの予約・施設を read/承認/取消/
 * 削除できる重大 BOLA/IDOR）。金型: {@code MemberScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。</p>
 *
 * <p>認可モデル（{@code FacilityAccessGuard} により全 Controller 入口で敷く）:</p>
 * <ul>
 *   <li><b>スコープ宣言型 EP</b>（一覧/設定/統計/カレンダー。URL パスが scope を明示）:
 *       非メンバーは 403（COMMON_002）。scope 自体は秘匿不要。</li>
 *   <li><b>entity 由来 scope の EP</b>（施設 id / 予約 id 直指定）: entity を fetch → entity 由来 scope で
 *       認可し、URL パスの scope と食い違う越境 id は 404（{@code FACILITY_001}/{@code FACILITY_006}）で
 *       存在秘匿する（BOLA 対策）。</li>
 *   <li>read = {@code checkMembership} / write = {@code checkAdminOrAbove} /
 *       予約の更新・キャンセルのみ {@code checkOwnerOrAdmin}（正当な本人操作を温存）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("facility ドメイン（施設/予約/設定/統計）認可契約テスト（Wave5 早馬 試練）")
class FacilityScopeContractIT extends AbstractMySqlIntegrationTest {

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

    private Long teamAId;
    private Long teamBId;

    private Long adminAId;   // teamA の ADMIN（正当）
    private Long memberAId;  // teamA の非 ADMIN メンバー（bookingA の予約者本人）
    private Long memberCId;  // teamA の非 ADMIN メンバー（bookingA の予約者ではない＝他人メンバー）
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long facilityAId; // teamA の施設
    private Long facilityBId; // teamB の施設（越境アクセステスト用）

    private Long bookingAId;  // facilityA(teamA) の予約。予約者 = memberA
    private Long bookingBId;  // facilityB(teamB) の予約（越境アクセステスト用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("FACAUTHZ チームA");
        teamBId = insertTeam("FACAUTHZ チームB");

        adminAId = insertUser("facauthz-admin-a@example.com");
        memberAId = insertUser("facauthz-member-a@example.com");
        memberCId = insertUser("facauthz-member-c@example.com");
        outsiderId = insertUser("facauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberCId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        SharedFacilityEntity facilityA = facilityRepository.save(SharedFacilityEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).name("FACAUTHZ 会議室A")
                .facilityType(FacilityType.MEETING_ROOM).capacity(10).createdBy(adminAId).build());
        facilityAId = facilityA.getId();

        SharedFacilityEntity facilityB = facilityRepository.save(SharedFacilityEntity.builder()
                .scopeType("TEAM").scopeId(teamBId).name("FACAUTHZ 会議室B")
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

    private String facilities(Long teamId) {
        return "/api/v1/teams/" + teamId + "/facilities";
    }

    private String bookings(Long teamId) {
        return "/api/v1/teams/" + teamId + "/facilities/bookings";
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /facilities（一覧・スコープ宣言型: checkMembership → 403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /facilities（施設一覧）")
    class ListFacilities {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(teamAId))).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId))).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /facilities/{id}（詳細・entity由来: 越境404 / 非メンバー403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /facilities/{id}（施設詳細）")
    class GetFacility {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityAId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境ID（teamAパスでteamB施設）は404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityAId)).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /facilities（作成・スコープ宣言型: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /facilities（施設作成）")
    class CreateFacility {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(facilities(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFacilityBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(facilities(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFacilityBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createFacilityBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "FACAUTHZ 新規室" + System.nanoTime());
            body.put("facilityType", "MEETING_ROOM");
            body.put("capacity", 8);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT/DELETE /facilities/{id}（更新・削除: 越境404 / 非ADMIN403 / ADMIN成功）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT/DELETE /facilities/{id}")
    class UpdateDeleteFacility {

        @Test
        @DisplayName("越境ID更新は404")
        void 越境ID更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(teamAId) + "/" + facilityBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー更新は403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(teamAId) + "/" + facilityAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMIN更新は200")
        void 正当ADMIN更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(teamAId) + "/" + facilityAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateFacilityBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境ID削除は404")
        void 越境ID削除は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(facilities(teamAId) + "/" + facilityBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN削除は204")
        void 正当ADMIN削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(facilities(teamAId) + "/" + facilityAId)).andExpect(status().isNoContent());
        }

        private Map<String, Object> updateFacilityBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "FACAUTHZ 更新室");
            body.put("facilityType", "MEETING_ROOM");
            body.put("capacity", 12);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET/PUT /facilities/{id}/rules（ルール参照/更新: 越境404 / read=member・write=admin）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET/PUT /facilities/{id}/rules")
    class UsageRule {

        @Test
        @DisplayName("越境IDルール取得は404")
        void 越境IDルール取得は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityBId + "/rules"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーはルール取得200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityAId + "/rules"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーはルール更新403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(teamAId) + "/" + facilityAId + "/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはルール更新200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(teamAId) + "/" + facilityAId + "/rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET/POST /facilities/{id}/equipment（備品一覧/作成: 越境404 / read=member・write=admin）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET/POST /facilities/{id}/equipment")
    class Equipment {

        @Test
        @DisplayName("越境ID備品一覧は404")
        void 越境ID一覧は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityBId + "/equipment"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは備品一覧200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId) + "/" + facilityAId + "/equipment"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは備品作成403")
        void 非ADMIN作成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(facilities(teamAId) + "/" + facilityAId + "/equipment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "FACAUTHZ 備品"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは備品作成201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(facilities(teamAId) + "/" + facilityAId + "/equipment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "FACAUTHZ 備品" + System.nanoTime()))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET/PUT /facilities/settings（設定・スコープ宣言型: read=member・write=admin）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET/PUT /facilities/settings")
    class Settings {

        @Test
        @DisplayName("非メンバーは設定取得403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(teamAId) + "/settings")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは設定取得200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId) + "/settings")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは設定更新403")
        void 非ADMIN更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put(facilities(teamAId) + "/settings")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは設定更新200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put(facilities(teamAId) + "/settings")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /facilities/stats（統計・機微データ=売上/手数料: ADMIN限定 checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /facilities/stats（ADMIN限定）")
    class Stats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(facilities(teamAId) + "/stats")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（売上・手数料は運営側材料ゆえ非公開）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(facilities(teamAId) + "/stats")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(facilities(teamAId) + "/stats")).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET /bookings（予約一覧・スコープ宣言型: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET /bookings（予約一覧）")
    class ListBookings {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(teamAId))).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(bookings(teamAId))).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. POST /bookings（予約作成・entity由来: 越境facilityId404 / 非メンバー403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. POST /bookings（予約作成）")
    class CreateBooking {

        @Test
        @DisplayName("越境facilityId（teamAパスでteamB施設予約）は404")
        void 越境facilityIdは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post(bookings(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBookingBody(facilityBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(bookings(teamAId))
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
    // 11. GET /bookings/{id}（予約詳細・entity由来: 越境404 / 非メンバー403 / メンバー200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. GET /bookings/{id}（予約詳細）")
    class GetBooking {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingAId)).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境ID（teamAパスでteamB予約）は404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingBId)).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingAId)).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. DELETE /bookings/{id}（キャンセル・本人 or ADMIN: 越境404 / 本人200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. DELETE /bookings/{id}（キャンセル）")
    class CancelBooking {

        @Test
        @DisplayName("越境ID（teamAパスでteamB予約）は404")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete(bookings(teamAId) + "/" + bookingBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("cancellationReason", "越境"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("予約者本人はキャンセル200")
        void 本人は200() throws Exception {
            setAuth(memberAId); // bookingA の予約者
            mockMvc.perform(delete(bookings(teamAId) + "/" + bookingAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("cancellationReason", "都合が悪くなった"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. PATCH /bookings/{id}/approve（承認・admin: 越境404 / 非ADMIN403 / ADMIN200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. PATCH /bookings/{id}/approve（承認）")
    class ApproveBooking {

        @Test
        @DisplayName("越境ID承認は404")
        void 越境IDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingBId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー（予約者本人でも）承認は403")
        void 非ADMINは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingAId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは承認200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingAId + "/approve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("adminComment", "承認します"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. 支払い系（GET /payment=予約単位: 越境404・他人403・予約者本人200 / PATCH /confirm: 越境404・非ADMIN403・ADMIN200）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. GET /payment（本人orADMIN）・PATCH /payment/confirm（ADMIN）")
    class Payment {

        @Test
        @DisplayName("越境ID支払い取得は404")
        void 越境ID取得は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingBId + "/payment"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("他人（非owner非adminメンバー）支払い取得は403")
        void 他人メンバー取得は403() throws Exception {
            setAuth(memberCId); // teamA メンバーだが bookingA の予約者ではない
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバー支払い取得は403")
        void 非メンバー取得は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("予約者本人は支払い取得200")
        void 予約者本人取得は200() throws Exception {
            setAuth(memberAId); // bookingA の予約者本人
            mockMvc.perform(get(bookings(teamAId) + "/" + bookingAId + "/payment"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境ID支払い確認は404")
        void 越境ID確認は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingBId + "/payment/confirm"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー支払い確認は403")
        void 非ADMIN確認は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingAId + "/payment/confirm"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは支払い確認200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch(bookings(teamAId) + "/" + bookingAId + "/payment/confirm"))
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
