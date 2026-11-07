package com.mannschaft.app.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 予約詳細 {@code GET /api/v1/teams/{teamId}/reservations/{reservationId}} の認可契約テスト
 * （CMP-260923-0954 W3a・存在オラクル是正）。
 *
 * <h2>EP 別許可主体表（本クラスが固定する契約）</h2>
 * <pre>
 * 主体                                   | 是正前                     | 是正後
 * 予約の所属チームの ADMIN/DEPUTY_ADMIN    | 200                        | 200
 * user_roles のみの ADMIN/DEPUTY_ADMIN    | 200                        | 200
 * 予約の本人（在籍不問）                   | 200                        | 200
 * 同チームの他の会員                       | 403 RESERVATION_021        | 403 RESERVATION_021（変えない）
 * 越境（所属チームの非メンバー）           | 403 RESERVATION_021        | 404 RESERVATION_003（不在と完全同一）
 * SYSTEM_ADMIN（非メンバー・非本人）       | 403 RESERVATION_021        | 403 RESERVATION_021（新規許可しない。マスター裁可 2026-09-30）
 * 不在・論理削除済み                       | 404 RESERVATION_003        | 404 RESERVATION_003
 * </pre>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("予約詳細 認可契約テスト（W3a 存在オラクル）")
class ReservationDetailScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminTeamAId;
    private Long adminTeamBId;
    private Long ownerMemberId;
    private Long ownerNonMemberId;
    private Long otherMemberId;
    private Long supporterId;
    private Long userRolesOnlyAdminId;
    private Long userRolesOnlyDeputyId;
    private Long systemAdminId;
    private Long outsiderId;

    private Long reservationId;          // owner = ownerMember
    private Long supporterOwnedResId;   // owner = supporter
    private Long nonMemberOwnerResId;    // owner = ownerNonMember（在籍なし）
    private Long deletedReservationId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("W3A チームA");
        teamBId = insertTeam("W3A チームB");
        adminTeamAId = insertUser("w3a-admin-a@example.com");
        adminTeamBId = insertUser("w3a-admin-b@example.com");
        ownerMemberId = insertUser("w3a-owner-member@example.com");
        ownerNonMemberId = insertUser("w3a-owner-nonmember@example.com");
        otherMemberId = insertUser("w3a-other-member@example.com");
        supporterId = insertUser("w3a-supporter@example.com");
        userRolesOnlyAdminId = insertUser("w3a-ur-admin@example.com");
        userRolesOnlyDeputyId = insertUser("w3a-ur-deputy@example.com");
        systemAdminId = insertUser("w3a-system-admin@example.com");
        outsiderId = insertUser("w3a-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, ownerMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, userRolesOnlyAdminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, userRolesOnlyDeputyId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        reservationId = saveReservation(teamAId, ownerMemberId);
        nonMemberOwnerResId = saveReservation(teamAId, ownerNonMemberId);
        supporterOwnedResId = saveReservation(teamAId, supporterId);
        deletedReservationId = saveReservation(teamAId, ownerMemberId);
        em.createNativeQuery("UPDATE reservations SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", deletedReservationId).executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-2: 同チームの他の会員は403 RESERVATION_021のまま")
    void 同チーム閲覧不可は403() throws Exception {
        setAuth(otherMemberId);
        expectError(detail(teamAId, reservationId), 403, "RESERVATION_021");
    }

    @Test
    @DisplayName("AC-1: 越境（部外者・別チームADMIN）は、実在IDと不在IDで応答が一致（404 RESERVATION_003）")
    void 越境は不在と同一応答() throws Exception {
        for (Long actor : List.of(outsiderId, adminTeamBId)) {
            setAuth(actor);
            assertSameAsMissing(detail(teamAId, reservationId), detail(teamAId, MISSING_ID));
        }
    }

    @Test
    @DisplayName("AC-1: 越境者が他チームのteamIdを付けても同じ404（従来どおり）")
    void 取り違えteamIdも同一応答() throws Exception {
        setAuth(adminTeamBId);
        assertSameAsMissing(detail(teamBId, reservationId), detail(teamAId, MISSING_ID));
    }

    @Test
    @DisplayName("AC-5: 論理削除済みの予約は不在と同一の404（管理者にも）")
    void 論理削除済みは404() throws Exception {
        setAuth(adminTeamAId);
        assertSameAsMissing(detail(teamAId, deletedReservationId), detail(teamAId, MISSING_ID));
    }

    @Test
    @DisplayName("許可主体: 管理者・本人・在籍のない本人は200")
    void 許可主体は200() throws Exception {
        for (Long actor : List.of(adminTeamAId, ownerMemberId)) {
            setAuth(actor);
            mockMvc.perform(detail(teamAId, reservationId)).andExpect(status().isOk());
        }
        setAuth(ownerNonMemberId);
        mockMvc.perform(detail(teamAId, nonMemberOwnerResId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-3: user_rolesのみのADMIN・DEPUTY_ADMINは200（404に化けない）")
    void userRolesOnly管理者は200() throws Exception {
        for (Long actor : List.of(userRolesOnlyAdminId, userRolesOnlyDeputyId)) {
            setAuth(actor);
            mockMvc.perform(detail(teamAId, reservationId)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("SUPPORTER（在籍・非本人）は同チームの他の会員と同じ403 RESERVATION_021（404に化けない）")
    void サポーターは非本人予約で403() throws Exception {
        setAuth(supporterId);
        expectError(detail(teamAId, reservationId), 403, "RESERVATION_021");
    }

    @Test
    @DisplayName("SUPPORTER が自分の予約を見るのは本人として200（ReservationDetailFacade の本人許可）")
    void サポーターは自分の予約なら200() throws Exception {
        setAuth(supporterId);
        mockMvc.perform(detail(teamAId, supporterOwnedResId)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN（非メンバー・非本人）は是正前どおり403 RESERVATION_021（新規許可も404化もしない）")
    void SYSTEM_ADMINは従来どおり403() throws Exception {
        setAuth(systemAdminId);
        expectError(detail(teamAId, reservationId), 403, "RESERVATION_021");
        expectError(detail(teamAId, MISSING_ID), 404, "RESERVATION_003");
        expectError(detail(teamAId, deletedReservationId), 404, "RESERVATION_003");
    }

    @Test
    @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値は400")
    void ID境界() throws Exception {
        for (Long actor : List.of(adminTeamAId, outsiderId)) {
            setAuth(actor);
            for (String id : List.of("0", "-1", String.valueOf(Long.MAX_VALUE))) {
                expectError(get("/api/v1/teams/" + teamAId + "/reservations/" + id), 404, "RESERVATION_003");
            }
            mockMvc.perform(get("/api/v1/teams/" + teamAId + "/reservations/abc"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ---- ヘルパー ----

    private RequestBuilder detail(Long teamId, Long id) {
        return get("/api/v1/teams/" + teamId + "/reservations/" + id);
    }

    private void assertSameAsMissing(RequestBuilder real, RequestBuilder missing) throws Exception {
        ErrorView realView = perform(real);
        ErrorView missingView = perform(missing);
        assertThat(realView).as("実在IDへの応答と不在IDへの応答が一致すること").isEqualTo(missingView);
        assertThat(realView.status()).isEqualTo(404);
        assertThat(realView.code()).isEqualTo("RESERVATION_003");
        assertThat(realView.message()).isNotBlank();
    }

    private void expectError(RequestBuilder request, int expectedStatus, String expectedCode) throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).isEqualTo(expectedStatus);
        assertThat(view.code()).isEqualTo(expectedCode);
    }

    private ErrorView perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode error = body.isBlank() ? null : objectMapper.readTree(body).path("error");
        return new ErrorView(result.getResponse().getStatus(),
                error == null ? null : error.path("code").asText(null),
                error == null ? null : error.path("message").asText(null));
    }

    private record ErrorView(int status, String code, String message) {
    }

    private Long saveReservation(Long teamId, Long userId) {
        ReservationSlotEntity slot = slotRepository.save(ReservationSlotEntity.builder()
                .teamId(teamId)
                .slotDate(LocalDate.of(2026, 12, 1))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .slotStatus(SlotStatus.AVAILABLE)
                .build());
        ReservationEntity saved = reservationRepository.save(ReservationEntity.builder()
                .reservationSlotId(slot.getId())
                .lineId(8001L)
                .teamId(teamId)
                .userId(userId)
                .status(ReservationStatus.CONFIRMED)
                .build());
        return saved.getId();
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
                                + "VALUES (:email, 'W3A', 'テスト', 'W3A テスト', 'ACTIVE', "
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
