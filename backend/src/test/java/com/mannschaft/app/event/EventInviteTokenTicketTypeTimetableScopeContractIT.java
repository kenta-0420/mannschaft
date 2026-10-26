package com.mannschaft.app.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import com.mannschaft.app.event.repository.EventTimetableItemRepository;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB12event — event 招待トークン・チケット種別・タイムテーブル
 * サブリソース API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code EventInviteTokenController}（一覧・作成・無効化）/
 * {@code EventTicketTypeController}（一覧・詳細・作成・更新）/
 * {@code EventTimetableController}（一覧・作成・更新・削除・並び替え）は
 * いずれも URL に eventId のみを持つフラットなサブリソースだが認可が一切敷設されておらず、
 * 招待トークン一覧（未使用トークン文字列＝登録バイパスの鍵）が非メンバーにも閲覧でき、
 * ticketTypeId/itemId の eventId 帰属も未検証だった（親子BOLA）。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("event（招待トークン・チケット種別・タイムテーブル）ドメイン 認可契約テスト（試練・Wave3-B12event）")
class EventInviteTokenTicketTypeTimetableScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventGuestInviteTokenRepository inviteTokenRepository;

    @Autowired
    private EventTicketTypeRepository ticketTypeRepository;

    @Autowired
    private EventTimetableItemRepository timetableRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;

    private Long adminTeamAId;
    private Long memberTeamAId;
    private Long outsiderId;

    private Long eventAId;
    private Long eventBId;

    private Long tokenAId;
    private Long tokenBId;      // eventB 所属（BOLA検証用）
    private Long ticketTypeAId;
    private Long ticketTypeBId; // eventB 所属（BOLA検証用）
    private Long timetableItemAId;
    private Long timetableItemBId; // eventB 所属（BOLA検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ITKAUTHZ チームA");
        Long teamBId = insertTeam("ITKAUTHZ チームB");

        adminTeamAId = insertUser("itkauthz-admin-team-a@example.com");
        memberTeamAId = insertUser("itkauthz-member-team-a@example.com");
        outsiderId = insertUser("itkauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        eventAId = insertEvent(teamAId, "itkauthz-event-a");
        eventBId = insertEvent(teamBId, "itkauthz-event-b");

        tokenAId = inviteTokenRepository.save(EventGuestInviteTokenEntity.builder()
                .eventId(eventAId).token(UUID.randomUUID().toString()).label("ITKAUTHZ A")
                .maxUses(10).createdBy(adminTeamAId).build()).getId();
        tokenBId = inviteTokenRepository.save(EventGuestInviteTokenEntity.builder()
                .eventId(eventBId).token(UUID.randomUUID().toString()).label("ITKAUTHZ B")
                .maxUses(10).createdBy(adminTeamAId).build()).getId();

        ticketTypeAId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventAId).name("ITKAUTHZ 一般A")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100)
                .build()).getId();
        ticketTypeBId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventBId).name("ITKAUTHZ 一般B")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100)
                .build()).getId();

        timetableItemAId = timetableRepository.save(EventTimetableItemEntity.builder()
                .eventId(eventAId).title("ITKAUTHZ 基調講演A")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 11, 0))
                .sortOrder(0)
                .build()).getId();
        timetableItemBId = timetableRepository.save(EventTimetableItemEntity.builder()
                .eventId(eventBId).title("ITKAUTHZ 基調講演B")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 11, 0))
                .sortOrder(0)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /events/{eventId}/invite-tokens（一覧: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /events/{eventId}/invite-tokens（一覧）")
    class ListInviteTokens {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/invite-tokens", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（招待トークン漏洩防止）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/invite-tokens", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/invite-tokens", eventAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /events/{eventId}/invite-tokens（作成: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /events/{eventId}/invite-tokens（作成）")
    class CreateInviteToken {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/invite-tokens", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/invite-tokens", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTokenBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createTokenBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("label", "ITKAUTHZ 新規");
            body.put("maxUses", 5);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /events/{eventId}/invite-tokens/{tokenId}/deactivate（無効化・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST .../invite-tokens/{tokenId}/deactivate（無効化）")
    class DeactivateInviteToken {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/invite-tokens/{tid}/deactivate", eventAId, tokenAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがtokenIdが他イベント所属は404（BOLA）")
        void tokenId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/invite-tokens/{tid}/deactivate", eventAId, tokenBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/invite-tokens/{tid}/deactivate", eventAId, tokenAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /events/{eventId}/ticket-types（一覧: メンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /events/{eventId}/ticket-types（一覧）")
    class ListTicketTypes {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/ticket-types", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（登録フローで参照するため）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/ticket-types", eventAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /events/{eventId}/ticket-types/{id}（詳細・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /events/{eventId}/ticket-types/{id}（詳細）")
    class GetTicketType {

        @Test
        @DisplayName("正当メンバーだがticketTypeIdが他イベント所属は404（BOLA）")
        void ticketTypeId越境は404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/ticket-types/{id}", eventAId, ticketTypeBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/ticket-types/{id}", eventAId, ticketTypeAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST /events/{eventId}/ticket-types（作成: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST /events/{eventId}/ticket-types（作成）")
    class CreateTicketType {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/ticket-types", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTicketTypeBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/ticket-types", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTicketTypeBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createTicketTypeBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "ITKAUTHZ 新規チケット");
            body.put("price", 1000);
            body.put("currency", "JPY");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PATCH /events/{eventId}/ticket-types/{id}（更新・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PATCH /events/{eventId}/ticket-types/{id}（更新）")
    class UpdateTicketType {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/ticket-types/{id}", eventAId, ticketTypeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがticketTypeIdが他イベント所属は404（BOLA）")
        void ticketTypeId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/ticket-types/{id}", eventAId, ticketTypeBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/ticket-types/{id}", eventAId, ticketTypeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "ITKAUTHZ 更新済み");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /events/{eventId}/timetable（一覧: メンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /events/{eventId}/timetable（一覧）")
    class ListTimetable {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/timetable", eventAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/timetable", eventAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. POST /events/{eventId}/timetable（作成: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST /events/{eventId}/timetable（作成）")
    class CreateTimetableItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/timetable", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createItemBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/events/{eventId}/timetable", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createItemBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createItemBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "ITKAUTHZ 新規セッション");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. PATCH /events/{eventId}/timetable/{itemId}（更新・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. PATCH /events/{eventId}/timetable/{itemId}（更新）")
    class UpdateTimetableItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateItemBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他イベント所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateItemBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateItemBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateItemBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "ITKAUTHZ 更新済みセッション");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. DELETE /events/{eventId}/timetable/{itemId}（削除・親子BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. DELETE /events/{eventId}/timetable/{itemId}（削除）")
    class DeleteTimetableItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他イベント所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/events/{eventId}/timetable/{id}", eventAId, timetableItemAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. PUT /events/{eventId}/timetable/reorder（並び替え: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. PUT /events/{eventId}/timetable/reorder（並び替え）")
    class ReorderTimetable {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/events/{eventId}/timetable/reorder", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reorderBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/events/{eventId}/timetable/reorder", eventAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reorderBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> reorderBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemIds", List.of(timetableItemAId));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long insertEvent(Long teamId, String slug) {
        EventEntity event = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamId).slug(slug)
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
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
                                + "VALUES (:email, 'ITKAUTHZ', 'テスト', 'ITKAUTHZ テスト', 'ACTIVE', "
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
