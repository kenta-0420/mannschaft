package com.mannschaft.app.village.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.DailyThreadListResponse;
import com.mannschaft.app.village.dto.DailyThreadResponse;
import com.mannschaft.app.village.dto.LobbyChannelResponse;
import com.mannschaft.app.village.service.VillageLobbyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link VillageLobbyController} の MockMvc 結合テスト（F17.1 Phase 1 B9）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>GET /lobby: 200 + chatChannelId + todayThreadId</li>
 *   <li>GET /lobby/daily: 200 + 配列</li>
 *   <li>GET /lobby/daily/{date}: 200 + 該当日が無ければ 404 VILLAGE_041</li>
 *   <li>非村人 → 404 VILLAGE_007</li>
 * </ul>
 */
@WebMvcTest(VillageLobbyController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageLobbyController 結合テスト")
class VillageLobbyControllerTest {

    private static final Long USER_ID = 300L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000010");
    private static final UUID THREAD_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000aa");
    private static final Long CHANNEL_ID = 9999L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageLobbyService lobbyService;

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

    @Test
    @DisplayName("GET /lobby: 200 + chatChannelId / todayThreadId")
    void getLobby_200() throws Exception {
        LobbyChannelResponse res = new LobbyChannelResponse(
                CHANNEL_ID, "VILLAGE_LOBBY", VILLAGE_ID,
                LocalDate.now(), THREAD_ID);
        given(lobbyService.getLobbyChannel(eq(VILLAGE_ID), eq(USER_ID))).willReturn(res);

        mockMvc.perform(get("/api/v1/villages/{vid}/lobby", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatChannelId").value(CHANNEL_ID))
                .andExpect(jsonPath("$.data.channelType").value("VILLAGE_LOBBY"))
                .andExpect(jsonPath("$.data.todayThreadId").value(THREAD_ID.toString()));
    }

    @Test
    @DisplayName("GET /lobby: 非村人 → 404 VILLAGE_007")
    void getLobby_notMember_404() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(lobbyService).getLobbyChannel(eq(VILLAGE_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/villages/{vid}/lobby", VILLAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }

    @Test
    @DisplayName("GET /lobby/daily: 200 + 配列")
    void listDaily_200() throws Exception {
        DailyThreadResponse t1 = new DailyThreadResponse(
                THREAD_ID, VILLAGE_ID, LocalDate.now(), CHANNEL_ID, 5L, null, LocalDateTime.now());
        given(lobbyService.listDailyThreads(eq(VILLAGE_ID), eq(USER_ID), anyInt()))
                .willReturn(DailyThreadListResponse.of(List.of(t1)));

        mockMvc.perform(get("/api/v1/villages/{vid}/lobby/daily", VILLAGE_ID)
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threads[0].id").value(THREAD_ID.toString()))
                .andExpect(jsonPath("$.data.threads[0].messageCount").value(5));
    }

    @Test
    @DisplayName("GET /lobby/daily/{date}: 該当日なし → 404 VILLAGE_049")
    void getDaily_missing_404() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 1);
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_LOBBY_NOT_FOUND))
                .given(lobbyService).getDailyThread(eq(VILLAGE_ID), eq(USER_ID), any(LocalDate.class));

        mockMvc.perform(get("/api/v1/villages/{vid}/lobby/daily/{date}", VILLAGE_ID, date))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_049"));
    }
}
