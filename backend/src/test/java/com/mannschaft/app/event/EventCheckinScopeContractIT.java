package com.mannschaft.app.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketRepository;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB12event — event チェックインサブリソース
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code EventCheckinController} の 4 EP（スタッフチェックイン・セルフチェックイン・
 * チェックイン一覧・チェックイン数）に認可が一切敷設されておらず、非メンバーでも他チームの
 * イベントに対して任意の QR トークンでチェックインを記録・改竄でき、一覧・件数も閲覧できた。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 非ADMINメンバー（memberTeamA）/ ADMIN（adminTeamA）/
 * 本人（selfCheckin の所有者）/ 他人（selfCheckin の非所有者）。
 * スタッフチェックイン・一覧・件数は URL に eventId を持たないか持つかで異なる認可経路
 * （{@code requireAdminByEventId} / {@code requireMemberByEventId}）を通るため、両方を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("event（チェックイン）ドメイン 認可契約テスト（試練・Wave3-B12event）")
class EventCheckinScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventTicketTypeRepository ticketTypeRepository;

    @Autowired
    private EventRegistrationRepository registrationRepository;

    @Autowired
    private EventTicketRepository ticketRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;
    private Long memberTeamAId;
    private Long otherMemberTeamAId;
    private Long outsiderId;

    private Long eventTeamAId;
    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CKAUTHZ チームA");
        teamBId = insertTeam("CKAUTHZ チームB");

        adminTeamAId = insertUser("ckauthz-admin-team-a@example.com");
        memberTeamAId = insertUser("ckauthz-member-team-a@example.com");
        otherMemberTeamAId = insertUser("ckauthz-other-member-team-a@example.com");
        outsiderId = insertUser("ckauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        EventEntity eventTeamA = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamAId).slug("ckauthz-event-a")
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .build());
        eventTeamAId = eventTeamA.getId();

        ticketTypeId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventTeamAId).name("CKAUTHZ 一般")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100)
                .build()).getId();

        em.flush();
        em.clear();
    }

    /** memberTeamAId 所有の VALID チケットを新規発行する（テストごとに独立させる）。 */
    private String issueValidTicketForOwner(Long ownerUserId) {
        Long registrationId = registrationRepository.save(EventRegistrationEntity.builder()
                .eventId(eventTeamAId).userId(ownerUserId).ticketTypeId(ticketTypeId)
                .status(RegistrationStatus.APPROVED).quantity(1)
                .build()).getId();
        // qr_token(length=36)は UUID 文字列(36文字ちょうど)を用いて桁溢れを避ける。
        // ticket_number(length=30)は UUID 先頭12文字を用いた短い一意値にする。
        String qrToken = UUID.randomUUID().toString();
        String ticketNumber = "CK-" + UUID.randomUUID().toString().substring(0, 12);
        ticketRepository.save(EventTicketEntity.builder()
                .registrationId(registrationId).eventId(eventTeamAId).ticketTypeId(ticketTypeId)
                .qrToken(qrToken).ticketNumber(ticketNumber)
                .status(TicketStatus.VALID)
                .build());
        em.flush();
        return qrToken;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. POST /api/v1/events/checkin（スタッフチェックイン: requireAdminByEventId）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /events/checkin（スタッフチェックイン）")
    class StaffCheckin {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/events/checkin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(checkinBody(qrToken))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/checkin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(checkinBody(qrToken))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/checkin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(checkinBody(qrToken))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> checkinBody(String qrToken) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("qrToken", qrToken);
            body.put("note", "CKAUTHZ 検証");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /api/v1/events/checkin/self（セルフチェックイン: 本人専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /events/checkin/self（セルフチェックイン）")
    class SelfCheckin {

        @Test
        @DisplayName("チケット所有者本人は201")
        void 本人は201() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/checkin/self")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfCheckinBody(qrToken))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("チケット所有者でない他人は403")
        void 他人は403() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/events/checkin/self")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfCheckinBody(qrToken))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバー（無関係な第三者）は403")
        void 非メンバーは403() throws Exception {
            String qrToken = issueValidTicketForOwner(memberTeamAId);
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/events/checkin/self")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfCheckinBody(qrToken))))
                    .andExpect(status().isForbidden());
        }

        private Map<String, Object> selfCheckinBody(String qrToken) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("qrToken", qrToken);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /api/v1/events/{eventId}/checkins（一覧: requireMemberByEventId）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /events/{eventId}/checkins（一覧）")
    class ListCheckins {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/checkins", eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/checkins", eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /api/v1/events/{eventId}/checkins/count（件数: requireMemberByEventId）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /events/{eventId}/checkins/count（件数）")
    class GetCheckinCount {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/checkins/count", eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/checkins/count", eventTeamAId))
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
                                + "VALUES (:email, 'CKAUTHZ', 'テスト', 'CKAUTHZ テスト', 'ACTIVE', "
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
