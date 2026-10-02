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
 * 認可根治戦役 Wave 3 バッチB12event — event 参加登録・チケットサブリソース
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code EventRegistrationController} / {@code EventTicketController} は URL に
 * eventId のみを持つフラットなサブリソースだが、認可が一切敷設されておらず、かつ
 * {@code registrationId}/{@code ticketId} の eventId 帰属も検証していなかった（BOLA）。
 * 非メンバーが任意イベントの参加登録一覧・チケット一覧を閲覧・承認・却下・キャンセルでき、
 * 正当なメンバーであっても他イベントの registrationId/ticketId を渡せば越境操作できた。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（adminTeamB の越境）/ 非ADMINメンバー
 * （memberTeamA）/ 正当ADMIN（adminTeamA）/ 本人 or 他人（cancel の所有者判定）/
 * 越境ID（eventB の registrationId/ticketId を eventA の URL で指定 = 親子BOLA）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("event（参加登録・チケット）ドメイン 認可契約テスト（試練・Wave3-B12event）")
class EventRegistrationTicketScopeContractIT extends AbstractMySqlIntegrationTest {

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
    private Long adminTeamBId;
    private Long memberTeamAId;
    private Long otherMemberTeamAId;
    private Long outsiderId;

    private Long eventAId;
    private Long eventBId;
    private Long ticketTypeAId;
    private Long ticketTypeBId;

    private Long registrationAId;    // memberTeamAId 所有・PENDING（承認/却下テスト用）
    private Long registrationA2Id;   // memberTeamAId 所有・APPROVED（キャンセルテスト用）
    private Long registrationBId;    // eventB 所属（BOLA検証用）
    private Long ticketAId;          // eventA 所属
    private Long ticketBId;          // eventB 所属（BOLA検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("REGAUTHZ チームA");
        teamBId = insertTeam("REGAUTHZ チームB");

        adminTeamAId = insertUser("regauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("regauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("regauthz-member-team-a@example.com");
        otherMemberTeamAId = insertUser("regauthz-other-member-team-a@example.com");
        outsiderId = insertUser("regauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        eventAId = insertEvent(teamAId, "regauthz-event-a");
        eventBId = insertEvent(teamBId, "regauthz-event-b");

        ticketTypeAId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventAId).name("REGAUTHZ 一般A")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100)
                .build()).getId();
        ticketTypeBId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventBId).name("REGAUTHZ 一般B")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100)
                .build()).getId();

        registrationAId = registrationRepository.save(EventRegistrationEntity.builder()
                .eventId(eventAId).userId(memberTeamAId).ticketTypeId(ticketTypeAId)
                .status(RegistrationStatus.PENDING).quantity(1)
                .build()).getId();
        registrationA2Id = registrationRepository.save(EventRegistrationEntity.builder()
                .eventId(eventAId).userId(memberTeamAId).ticketTypeId(ticketTypeAId)
                .status(RegistrationStatus.APPROVED).quantity(1)
                .build()).getId();
        registrationBId = registrationRepository.save(EventRegistrationEntity.builder()
                .eventId(eventBId).userId(adminTeamBId).ticketTypeId(ticketTypeBId)
                .status(RegistrationStatus.PENDING).quantity(1)
                .build()).getId();

        ticketAId = ticketRepository.save(EventTicketEntity.builder()
                .registrationId(registrationA2Id).eventId(eventAId).ticketTypeId(ticketTypeAId)
                .qrToken(uniqueToken()).ticketNumber("REGAUTHZ-A-0001")
                .status(TicketStatus.VALID)
                .build()).getId();
        ticketBId = ticketRepository.save(EventTicketEntity.builder()
                .registrationId(registrationBId).eventId(eventBId).ticketTypeId(ticketTypeBId)
                .qrToken(uniqueToken()).ticketNumber("REGAUTHZ-B-0001")
                .status(TicketStatus.VALID)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /events/{eventId}/registrations（一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /events/{eventId}/registrations（一覧）")
    class ListRegistrations {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations", eventAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /events/{eventId}/registrations/{registrationId}（詳細・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /events/{eventId}/registrations/{registrationId}（詳細）")
    class GetRegistration {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations/{rid}", eventAId, registrationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーだがregistrationIdが他イベント所属は404（BOLA）")
        void registrationId越境は404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations/{rid}", eventAId, registrationBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/registrations/{rid}", eventAId, registrationAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /events/{eventId}/registrations（作成・本人分）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /events/{eventId}/registrations（作成）")
    class CreateRegistration {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(ticketTypeAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(ticketTypeAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody(Long ticketTypeId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketTypeId", ticketTypeId);
            body.put("quantity", 1);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /events/{eventId}/registrations/{registrationId}/approve（承認: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST .../approve（承認）")
    class ApproveRegistration {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/approve", eventAId, registrationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/approve", eventAId, registrationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがregistrationIdが他イベント所属は404（BOLA）")
        void registrationId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/approve", eventAId, registrationBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/approve", eventAId, registrationAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /events/{eventId}/registrations/{registrationId}/reject（却下: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST .../reject（却下）")
    class RejectRegistration {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/reject", eventAId, registrationAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/reject", eventAId, registrationAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST /events/{eventId}/registrations/{registrationId}/cancel（本人 or ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST .../cancel（キャンセル）")
    class CancelRegistration {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/cancel", eventAId, registrationA2Id))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("本人以外のメンバー（無関係な第三者）は403")
        void 他人は403() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/cancel", eventAId, registrationA2Id))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("本人は200")
        void 本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/cancel", eventAId, registrationA2Id))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("ADMINは本人以外でも200")
        void ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/cancel", eventAId, registrationA2Id))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET /events/{eventId}/tickets（一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET /events/{eventId}/tickets（一覧）")
    class ListTickets {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets", eventAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /events/{eventId}/tickets/{ticketId}（詳細・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /events/{eventId}/tickets/{ticketId}（詳細）")
    class GetTicket {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/{tid}", eventAId, ticketAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーだがticketIdが他イベント所属は404（BOLA）")
        void ticketId越境は404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/{tid}", eventAId, ticketBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/{tid}", eventAId, ticketAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET /events/{eventId}/tickets/by-qr（QR検索・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET /events/{eventId}/tickets/by-qr（QR検索）")
    class GetTicketByQr {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            EventTicketEntity ticket = ticketRepository.findById(ticketAId).orElseThrow();
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/by-qr", eventAId)
                            .param("qrToken", ticket.getQrToken()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーだが他イベントのQRトークンは404（BOLA）")
        void qrToken越境は404() throws Exception {
            EventTicketEntity ticketB = ticketRepository.findById(ticketBId).orElseThrow();
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/by-qr", eventAId)
                            .param("qrToken", ticketB.getQrToken()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            EventTicketEntity ticket = ticketRepository.findById(ticketAId).orElseThrow();
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/tickets/by-qr", eventAId)
                            .param("qrToken", ticket.getQrToken()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. POST /events/{eventId}/tickets/{ticketId}/cancel（キャンセル: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. POST .../tickets/{ticketId}/cancel（キャンセル）")
    class CancelTicket {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/tickets/{tid}/cancel", eventAId, ticketAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがticketIdが他イベント所属は404（BOLA）")
        void ticketId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/tickets/{tid}/cancel", eventAId, ticketBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/tickets/{tid}/cancel", eventAId, ticketAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ロットDステータス契約（EVENT_005/017）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ロットDステータス契約（ALREADY_REGISTERED/INVALID_REGISTRATION_STATUS）")
    class LotDStatusContract {

        @Test
        @DisplayName("既に登録済みユーザーの再登録は409（ALREADY_REGISTERED）")
        void 既に登録済みの再登録は409() throws Exception {
            // memberTeamAId は setUp で registrationAId（eventA）を既に保有している。
            setAuth(memberTeamAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ticketTypeId", ticketTypeAId);
            body.put("quantity", 1);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("却下済みの参加登録を再承認しようとする操作は409（INVALID_REGISTRATION_STATUS）")
        void 却下済みの再承認は409() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/reject", eventAId, registrationAId))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/events/{eventId}/registrations/{rid}/approve", eventAId, registrationAId))
                    .andExpect(status().isConflict());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * event_tickets.qr_token カラム（length=36）に収まる一意なトークンを生成する。
     * UUID の文字列表現は 36 文字ちょうどで、接頭辞を付けると桁溢れ（Data too long）するため付けない。
     */
    private String uniqueToken() {
        return UUID.randomUUID().toString();
    }

    private Long insertEvent(Long teamId, String slug) {
        EventEntity event = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamId).slug(slug)
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .maxCapacity(1000)
                .build());
        return event.getId();
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
                                + "VALUES (:email, 'REGAUTHZ', 'テスト', 'REGAUTHZ テスト', 'ACTIVE', "
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
