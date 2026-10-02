package com.mannschaft.app.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB12event — event RSVP・主催者点呼・解散通知・チャットチャンネル取得
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code EventRsvpController} / {@code EventRollCallController} /
 * {@code EventDismissalController} / {@code EventChatController} は URL に teamId/orgId/eventId を
 * 持つ（または eventId のみのフラット）エンドポイントでありながら認可が一切敷設されておらず、
 * 非メンバーが RSVP 一覧（氏名・出欠・コメント）や主催者点呼候補者（ケア対象フラグ等の個人情報）を
 * 閲覧・改竄でき、解散通知も無関係な第三者が送信できた。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("event（RSVP・点呼・解散通知・チャット）ドメイン 認可契約テスト（試練・Wave3-B12event）")
class EventRsvpRollCallDismissalChatScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Autowired
    private ChatChannelRepository chatChannelRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long orgAId;

    private Long adminTeamAId;
    private Long memberTeamAId;
    private Long rsvpMemberTeamAId; // 既存RSVP(ATTENDING)を持つメンバー（update/late-notice/roll-call対象）
    private Long outsiderId;

    private Long eventTeamAId;
    private Long eventOrgAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("RSVPAUTHZ チームA");
        orgAId = insertOrganization("RSVPAUTHZ 組織A");

        adminTeamAId = insertUser("rsvpauthz-admin-team-a@example.com");
        memberTeamAId = insertUser("rsvpauthz-member-team-a@example.com");
        rsvpMemberTeamAId = insertUser("rsvpauthz-rsvp-member-team-a@example.com");
        outsiderId = insertUser("rsvpauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, rsvpMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // 組織Aにも同じADMINユーザーを所属させ、組織スコープの動作確認に流用する。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        eventTeamAId = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamAId).slug("rsvpauthz-event-team-a")
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .attendanceMode(EventAttendanceMode.RSVP)
                .build()).getId();

        eventOrgAId = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.ORGANIZATION).scopeId(orgAId).slug("rsvpauthz-event-org-a")
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false)
                .attendanceMode(EventAttendanceMode.RSVP)
                .build()).getId();

        rsvpResponseRepository.save(EventRsvpResponseEntity.builder()
                .eventId(eventTeamAId).userId(rsvpMemberTeamAId).response("ATTENDING")
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/events/{eventId}/rsvp-responses（一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET .../rsvp-responses（一覧・チームスコープ）")
    class ListTeamRsvp {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/events/{eventId}/rsvp-responses（送信・本人）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST .../rsvp-responses（送信）")
    class SubmitTeamRsvp {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rsvpBody("ATTENDING"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rsvpBody("ATTENDING"))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> rsvpBody(String response) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("response", response);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT /teams/{teamId}/events/{eventId}/rsvp-responses/me（更新・本人）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT .../rsvp-responses/me（更新）")
    class UpdateTeamRsvp {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/me", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rsvpBody("MAYBE"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバー（既存RSVP保有）は200")
        void 正当メンバーは200() throws Exception {
            setAuth(rsvpMemberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/me", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rsvpBody("MAYBE"))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> rsvpBody(String response) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("response", response);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET .../rsvp-responses/summary（集計）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET .../rsvp-responses/summary（集計）")
    class GetTeamRsvpSummary {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/summary", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/summary", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST .../rsvp-responses/late-notice（事前遅刻連絡）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST .../late-notice（事前遅刻連絡）")
    class SubmitLateNotice {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice",
                            teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lateNoticeBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/late-notice",
                            teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lateNoticeBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> lateNoticeBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", rsvpMemberTeamAId);
            body.put("expectedArrivalMinutesLate", 15);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET .../advance-notices（事前通知一覧: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET .../advance-notices（事前通知一覧）")
    class GetAdvanceNotices {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/advance-notices", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/advance-notices", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET /organizations/{orgId}/events/{eventId}/rsvp-responses（組織スコープ動作確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET .../organizations/{orgId}/events/{eventId}/rsvp-responses（組織スコープ）")
    class ListOrgRsvp {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses", orgAId, eventOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses", orgAId, eventOrgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET .../roll-call/candidates（点呼候補者一覧: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET .../roll-call/candidates（点呼候補者一覧）")
    class GetRollCallCandidates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/roll-call/candidates", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（点呼はADMIN/STAFF専用）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/roll-call/candidates", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/roll-call/candidates", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. POST .../roll-call（点呼セッション一括登録: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST .../roll-call（点呼セッション一括登録）")
    class SubmitRollCall {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/roll-call", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rollCallBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/roll-call", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rollCallBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> rollCallBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", rsvpMemberTeamAId);
            entry.put("status", "PRESENT");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rollCallSessionId", "RSVPAUTHZ-SESSION-1");
            body.put("entries", List.of(entry));
            body.put("notifyGuardiansImmediately", false);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. GET .../roll-call/sessions（点呼セッション履歴: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET .../roll-call/sessions（点呼セッション履歴）")
    class GetRollCallSessions {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/roll-call/sessions", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/roll-call/sessions", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. PATCH .../roll-call/{userId}（点呼結果個別修正: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. PATCH .../roll-call/{userId}（点呼結果個別修正）")
    class PatchRollCallEntry {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/events/{eventId}/roll-call/{userId}",
                            teamAId, eventTeamAId, rsvpMemberTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(entryBody())))
                    .andExpect(status().isForbidden());
        }

        private Map<String, Object> entryBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", rsvpMemberTeamAId);
            body.put("status", "ABSENT");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. POST .../dismissal（解散通知送信: ADMIN専用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. POST .../dismissal（解散通知送信）")
    class SendDismissal {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/dismissal", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/dismissal", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/events/{eventId}/dismissal", teamAId, eventTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. GET .../dismissal/status（解散通知状態確認: メンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. GET .../dismissal/status（解散通知状態確認）")
    class GetDismissalStatus {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/dismissal/status", teamAId, eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/events/{eventId}/dismissal/status", teamAId, eventTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. GET /events/{eventId}/channel（イベント専用チャンネル取得: メンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. GET /events/{eventId}/channel（チャンネル取得）")
    class GetEventChannel {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            createChannelForEvent();
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/events/{eventId}/channel", eventTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            createChannelForEvent();
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/events/{eventId}/channel", eventTeamAId))
                    .andExpect(status().isOk());
        }

        private void createChannelForEvent() {
            chatChannelRepository.save(ChatChannelEntity.builder()
                    .channelType(ChannelType.EVENT_CHAT)
                    .teamId(teamAId)
                    .name("RSVPAUTHZ イベントチャット")
                    .isPrivate(false)
                    .sourceType("EVENT")
                    .sourceId(eventTeamAId)
                    .build());
            em.flush();
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
                                + "VALUES (:email, 'RSVPAUTHZ', 'テスト', 'RSVPAUTHZ テスト', 'ACTIVE', "
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
