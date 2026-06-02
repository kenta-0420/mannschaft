package com.mannschaft.app.village.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PostingIdentityListResponse;
import com.mannschaft.app.village.dto.PostingIdentityResponse;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PostingIdentityController} の MockMvc 結合テスト（F17.1 Phase 1 B9）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>200: 投稿主体一覧の JSON 形状</li>
 *   <li>404: 非村人 → VILLAGE_007</li>
 * </ul>
 */
@WebMvcTest(PostingIdentityController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PostingIdentityController 結合テスト")
class PostingIdentityControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostingIdentityService postingIdentityService;

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
    @DisplayName("GET posting-identities: 200 + USER/TEAM/ORG 全件")
    void list_200() throws Exception {
        PostingIdentityListResponse list = PostingIdentityListResponse.of(List.of(
                PostingIdentityResponse.user(USER_ID, "山田太郎"),
                PostingIdentityResponse.team(567L, "ABC整骨院"),
                PostingIdentityResponse.organization(89L, "ヘルスケア協会")
        ));
        given(postingIdentityService.listIdentities(eq(USER_ID), eq(VILLAGE_ID))).willReturn(list);

        mockMvc.perform(get("/api/v1/me/villages/{vid}/posting-identities", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identities[0].subjectType").value("USER"))
                .andExpect(jsonPath("$.data.identities[0].displayName").value("山田太郎"))
                .andExpect(jsonPath("$.data.identities[1].subjectType").value("TEAM"))
                .andExpect(jsonPath("$.data.identities[1].displayName").value("ABC整骨院"))
                .andExpect(jsonPath("$.data.identities[2].subjectType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.data.identities[2].canPostAs").value(true));
    }

    @Test
    @DisplayName("GET posting-identities: 非村人 → 404 VILLAGE_007")
    void list_notMember_404() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NOT_MEMBER))
                .given(postingIdentityService).listIdentities(eq(USER_ID), eq(VILLAGE_ID));

        mockMvc.perform(get("/api/v1/me/villages/{vid}/posting-identities", VILLAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));
    }
}
