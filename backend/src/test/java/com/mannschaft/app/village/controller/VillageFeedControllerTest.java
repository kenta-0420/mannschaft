package com.mannschaft.app.village.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.dto.VillageFeedItemResponse;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.dto.VillagePinnedSummaryResponse;
import com.mannschaft.app.village.service.VillageFeedService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link VillageFeedController} の MockMvc 結合テスト（F17.1 Phase 1 B10 §4.13）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>GET /me/village-feed: 200 + フィード + ピン村サマリー</li>
 *   <li>ピン無し → 空配列を返す</li>
 * </ul>
 */
@WebMvcTest(VillageFeedController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageFeedController 結合テスト")
class VillageFeedControllerTest {

    private static final Long USER_ID = 700L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageFeedService feedService;

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
    @DisplayName("GET /me/village-feed: 200 + feed + pinnedVillages")
    void feed_200() throws Exception {
        UUID vId = UUID.fromString("01956c00-0000-7000-8000-000000000020");
        VillageFeedResponse res = VillageFeedResponse.builder()
                .feed(List.of(VillageFeedItemResponse.builder()
                        .type("TIMELINE")
                        .villageId(vId)
                        .villageName("東村")
                        .postId(10L)
                        .snippet("東の投稿")
                        .createdAt(LocalDateTime.now())
                        .build()))
                .pinnedVillages(List.of(VillagePinnedSummaryResponse.builder()
                        .id(vId).name("東村").unreadCount(0L).build()))
                .build();
        given(feedService.build(eq(USER_ID), anyInt())).willReturn(res);

        mockMvc.perform(get("/api/v1/me/village-feed").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feed[0].type").value("TIMELINE"))
                .andExpect(jsonPath("$.data.feed[0].villageName").value("東村"))
                .andExpect(jsonPath("$.data.pinnedVillages[0].name").value("東村"));
    }

    @Test
    @DisplayName("GET /me/village-feed: ピンなし → 空配列")
    void feed_empty() throws Exception {
        given(feedService.build(eq(USER_ID), anyInt()))
                .willReturn(VillageFeedResponse.builder()
                        .feed(List.of()).pinnedVillages(List.of()).build());

        mockMvc.perform(get("/api/v1/me/village-feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feed").isArray())
                .andExpect(jsonPath("$.data.feed").isEmpty())
                .andExpect(jsonPath("$.data.pinnedVillages").isEmpty());
    }
}
