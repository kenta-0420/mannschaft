package com.mannschaft.app.village.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageInternalSearchItemResponse;
import com.mannschaft.app.village.dto.VillageInternalSearchResponse;
import com.mannschaft.app.village.service.VillageSearchService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link VillageSearchController} の MockMvc 結合テスト（F17.1 Phase 1 B10 §4.12）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>GET /search: 200 + items 配列</li>
 *   <li>非村人 → 404 VILLAGE_007</li>
 *   <li>q が短すぎ → 422 VILLAGE_051</li>
 * </ul>
 */
@WebMvcTest(VillageSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("VillageSearchController 結合テスト")
class VillageSearchControllerTest {

    private static final Long USER_ID = 300L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000010");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageSearchService searchService;

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
    @DisplayName("GET /search: 200 + 検索結果配列")
    void search_200() throws Exception {
        VillageInternalSearchItemResponse item = VillageInternalSearchItemResponse.builder()
                .type("POST")
                .postKind("BULLETIN_THREAD")
                .id("1")
                .title("整骨タイトル")
                .snippet("本文抜粋")
                .createdAt(LocalDateTime.now())
                .build();
        VillageInternalSearchResponse res = VillageInternalSearchResponse.builder()
                .items(List.of(item)).page(0).size(20).total(1L).build();
        given(searchService.search(eq(VILLAGE_ID), anyString(), anyString(), anyInt(), anyInt(), eq(USER_ID)))
                .willReturn(res);

        mockMvc.perform(get("/api/v1/villages/{vid}/search", VILLAGE_ID)
                        .param("q", "整骨")
                        .param("type", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("POST"))
                .andExpect(jsonPath("$.data.items[0].id").value("1"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("GET /search: 非村人 → 404 VILLAGE_007")
    void search_notMember_404() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(searchService)
                .search(eq(VILLAGE_ID), anyString(), anyString(), anyInt(), anyInt(), eq(USER_ID));

        mockMvc.perform(get("/api/v1/villages/{vid}/search", VILLAGE_ID)
                        .param("q", "整骨")
                        .param("type", "ALL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }

    @Test
    @DisplayName("GET /search: q が短すぎ → 422 VILLAGE_051")
    void search_shortQuery_422() throws Exception {
        // type を渡さない場合は 3 引数目が null になるため anyString() ではマッチしない。
        // any() で null 含む全引数を許容する。
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY))
                .given(searchService)
                .search(eq(VILLAGE_ID), anyString(), any(), anyInt(), anyInt(), eq(USER_ID));

        mockMvc.perform(get("/api/v1/villages/{vid}/search", VILLAGE_ID)
                        .param("q", "a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_051"));
    }
}
