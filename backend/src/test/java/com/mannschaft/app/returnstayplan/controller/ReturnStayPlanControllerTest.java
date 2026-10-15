package com.mannschaft.app.returnstayplan.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Full Security filter, MVC advice and MySQL HTTP contract tests. */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReturnStayPlanControllerTest extends AbstractMySqlIntegrationTest {

    private static final long OWNER_ID = 922001L;
    private static final long OTHER_ID = 922002L;
    private static final long TEAM_ID = 922003L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReturnStayPlanService service;

    @MockitoBean(name = "utcClock")
    private Clock utcClock;

    @BeforeEach
    void seed() {
        when(utcClock.withZone(any(ZoneId.class)))
                .thenAnswer(invocation -> Clock.fixed(
                        java.time.Instant.parse("2026-08-17T03:00:00Z"),
                        invocation.getArgument(0)));
        jdbc.update("DELETE FROM return_stay_plan_team_visibilities");
        jdbc.update("DELETE FROM return_stay_plans WHERE owner_user_id BETWEEN 922000 AND 922999");
        jdbc.update("DELETE FROM return_stay_plan_owner_locks WHERE owner_user_id BETWEEN 922000 AND 922999");
        jdbc.update("DELETE FROM memberships WHERE user_id BETWEEN 922000 AND 922999");
        jdbc.update("DELETE FROM teams WHERE id BETWEEN 922000 AND 922999");
        jdbc.update("DELETE FROM users WHERE id BETWEEN 922000 AND 922999");
        insertUser(OWNER_ID, "controller-owner@example.test", "Owner");
        insertUser(OTHER_ID, "controller-other@example.test", "Other");
        insertTeam(TEAM_ID, "f0211-controller-team");
        insertMembership(OWNER_ID, TEAM_ID);
        insertMembership(OTHER_ID, TEAM_ID);
    }

    @Test
    @DisplayName("AC-01 unauthenticated self list is rejected by the real filter")
    void ac01_unauthenticatedIs401() throws Exception {
        mockMvc.perform(get("/api/v1/me/return-stay-plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-03 create returns 201 and ApiResponse data")
    void ac03_createReturns201Wrapper() throws Exception {
        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.planType").value("HOMECOMING"))
                .andExpect(jsonPath("$.data.location.prefectureCode").value("13"))
                .andExpect(jsonPath("$.data.teamIds[0]").value(TEAM_ID))
                .andExpect(jsonPath("$.data.ownerUserId").doesNotExist());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-04 missing required fields returns 400 before service invocation")
    void ac04_missingFieldsAre400() throws Exception {
        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-05 unknown JSON field returns 400")
    void ac05_unknownFieldIs400() throws Exception {
        Map<String, Object> payload = Map.of(
                "planType", "HOMECOMING",
                "isPublished", false,
                "location", Map.of("countryCode", "JP", "prefectureCode", "13"),
                "startDate", "2026-08-17",
                "endDate", "2026-08-20",
                "teamIds", List.of(),
                "unknown", true);
        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-17 stale version returns mapped 409")
    void ac17_staleVersionIs409() throws Exception {
        var created = service.create(OWNER_ID, validRequest());
        mockMvc.perform(put("/api/v1/me/return-stay-plans/{id}", created.id())
                        .with(csrf())
                        .param("version", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-22 size above 100 returns 400")
    void ac22_invalidPageSizeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/me/return-stay-plans").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "922001", roles = "MEMBER")
    @DisplayName("AC-02 another owner's item and missing item share 404")
    void ac02_otherOwnerIs404() throws Exception {
        var otherPlan = service.create(OTHER_ID, validRequest());
        mockMvc.perform(get("/api/v1/me/return-stay-plans/{id}", otherPlan.id()))
                .andExpect(status().isNotFound());
    }

    private ReturnStayPlanCreateRequest validRequest() {
        return new ReturnStayPlanCreateRequest(
                "HOMECOMING", true,
                new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 20), List.of(TEAM_ID));
    }

    private void insertUser(long id, String email, String displayName) {
        jdbc.update("""
                INSERT INTO users
                    (id, email, last_name, first_name, display_name, status,
                     is_searchable, handle_searchable, contact_approval_required,
                     online_visibility, dm_receive_from, encryption_key_version,
                     locale, timezone, reporting_restricted, follow_list_visibility,
                     care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, ?, 'Test', 'User', ?, 'ACTIVE', 1, 1, 1,
                        'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0,
                        NOW(), NOW())
                """, id, email, displayName);
    }

    private void insertTeam(long id, String slug) {
        jdbc.update("""
                INSERT INTO teams
                    (id, name, slug, visibility, supporter_enabled, member_count,
                     version, created_at, updated_at)
                VALUES (?, 'F02.11 team', ?, 'PUBLIC', 1, 0, 0, NOW(), NOW())
                """, id, slug);
    }

    private void insertMembership(long userId, long teamId) {
        jdbc.update("""
                INSERT INTO memberships
                    (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at)
                VALUES (?, 'TEAM', ?, 'MEMBER', NOW(), NOW(), NOW())
                """, userId, teamId);
    }

}
