package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.MembershipListResponse;
import com.mannschaft.app.village.dto.MembershipResponse;
import com.mannschaft.app.village.dto.RoleChangeRequest;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.VillageMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link VillageMembershipController} の MockMvc 結合テスト（F17.1 Phase 1 B3）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>各エンドポイントの HTTP ステータス + JSON 形状</li>
 *   <li>VILLAGE_006 ALREADY_MEMBER → 409 / VILLAGE_019 → 409 / VILLAGE_024 → 403</li>
 *   <li>BAN・ロール変更の HEADMAN 限定</li>
 * </ul>
 */
@WebMvcTest(VillageMembershipController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageMembershipController 結合テスト")
class VillageMembershipControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000aa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VillageMembershipService membershipService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private MembershipResponse sampleResponse() {
        return new MembershipResponse(
                MEMBERSHIP_ID,
                VillageSubjectType.USER,
                USER_ID,
                null,
                VillageRole.VILLAGER,
                LocalDateTime.of(2026, 5, 14, 10, 0),
                false,
                false);
    }

    // ------------------------------------------------------------------
    // POST /memberships
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /memberships: 参加成功で 201 + メンバーシップ JSON 返却")
    void join_201() throws Exception {
        given(membershipService.join(eq(VILLAGE_ID), eq(USER_ID), any(MembershipJoinRequest.class)))
                .willReturn(sampleResponse());

        String body = objectMapper.writeValueAsString(
                new MembershipJoinRequest(VillageSubjectType.USER, USER_ID));

        mockMvc.perform(post("/api/v1/villages/{vid}/memberships", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(MEMBERSHIP_ID.toString()))
                .andExpect(jsonPath("$.data.role").value("VILLAGER"));
    }

    @Test
    @DisplayName("POST /memberships: APPROVAL 村への直接参加で 409 VILLAGE_019")
    void join_approvalDenied_409() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_JOIN_REQUIRES_APPROVAL))
                .given(membershipService).join(eq(VILLAGE_ID), eq(USER_ID), any(MembershipJoinRequest.class));

        String body = objectMapper.writeValueAsString(
                new MembershipJoinRequest(VillageSubjectType.USER, USER_ID));

        mockMvc.perform(post("/api/v1/villages/{vid}/memberships", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_019"));
    }

    // ------------------------------------------------------------------
    // DELETE /memberships/{id}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /memberships/{id}: 退出成功で 204")
    void leave_204() throws Exception {
        mockMvc.perform(delete("/api/v1/villages/{vid}/memberships/{mid}", VILLAGE_ID, MEMBERSHIP_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /memberships/{id}: 既退村は 404 VILLAGE_007 (IDOR)")
    void leave_alreadyLeft_404() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(membershipService).leave(eq(VILLAGE_ID), eq(MEMBERSHIP_ID), eq(USER_ID));

        mockMvc.perform(delete("/api/v1/villages/{vid}/memberships/{mid}", VILLAGE_ID, MEMBERSHIP_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }

    // ------------------------------------------------------------------
    // GET /memberships
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /memberships: 一覧で 200")
    void list_200() throws Exception {
        MembershipListResponse list = MembershipListResponse.of(List.of(sampleResponse()), 0, 50, 1);
        given(membershipService.listMembers(eq(VILLAGE_ID), eq(USER_ID), eq(0), eq(50)))
                .willReturn(list);

        mockMvc.perform(get("/api/v1/villages/{vid}/memberships", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(MEMBERSHIP_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /memberships: 非村人で 404 VILLAGE_007 (IDOR)")
    void list_notMember_404() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(membershipService).listMembers(eq(VILLAGE_ID), eq(USER_ID), eq(0), eq(50));

        mockMvc.perform(get("/api/v1/villages/{vid}/memberships", VILLAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }

    // ------------------------------------------------------------------
    // PATCH /memberships/{id}/role
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /memberships/{id}/role: HEADMAN がロール変更で 200")
    void changeRole_200() throws Exception {
        MembershipResponse elder = new MembershipResponse(
                MEMBERSHIP_ID, VillageSubjectType.USER, 200L, null,
                VillageRole.ELDER, LocalDateTime.of(2026, 5, 1, 0, 0), false, false);
        given(membershipService.changeRole(eq(VILLAGE_ID), eq(MEMBERSHIP_ID), eq(USER_ID), any()))
                .willReturn(elder);

        String body = objectMapper.writeValueAsString(new RoleChangeRequest(VillageRole.ELDER));

        mockMvc.perform(patch("/api/v1/villages/{vid}/memberships/{mid}/role", VILLAGE_ID, MEMBERSHIP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ELDER"));
    }

    @Test
    @DisplayName("PATCH /memberships/{id}/role: HEADMAN 以外は 403 VILLAGE_024")
    void changeRole_notHeadman_403() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(membershipService).changeRole(eq(VILLAGE_ID), eq(MEMBERSHIP_ID), eq(USER_ID), any());

        String body = objectMapper.writeValueAsString(new RoleChangeRequest(VillageRole.ELDER));

        mockMvc.perform(patch("/api/v1/villages/{vid}/memberships/{mid}/role", VILLAGE_ID, MEMBERSHIP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    // ------------------------------------------------------------------
    // POST /memberships/{id}/ban
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /memberships/{id}/ban: HEADMAN が BAN で 200")
    void ban_200() throws Exception {
        MembershipResponse banned = new MembershipResponse(
                MEMBERSHIP_ID, VillageSubjectType.USER, 200L, null,
                VillageRole.VILLAGER, LocalDateTime.of(2026, 5, 1, 0, 0), true, false);
        given(membershipService.ban(eq(VILLAGE_ID), eq(MEMBERSHIP_ID), eq(USER_ID), any()))
                .willReturn(banned);

        String body = objectMapper.writeValueAsString(new MembershipBanRequest("spam"));

        mockMvc.perform(post("/api/v1/villages/{vid}/memberships/{mid}/ban", VILLAGE_ID, MEMBERSHIP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isBanned").value(true));
    }

    @Test
    @DisplayName("POST /memberships/{id}/ban: HEADMAN 以外は 403 VILLAGE_024")
    void ban_notHeadman_403() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(membershipService).ban(eq(VILLAGE_ID), eq(MEMBERSHIP_ID), eq(USER_ID), any());

        String body = objectMapper.writeValueAsString(new MembershipBanRequest("spam"));

        mockMvc.perform(post("/api/v1/villages/{vid}/memberships/{mid}/ban", VILLAGE_ID, MEMBERSHIP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }
}
