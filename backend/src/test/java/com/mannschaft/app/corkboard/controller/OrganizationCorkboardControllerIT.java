package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.service.CorkboardService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.8 Phase A2 {@link OrganizationCorkboardController#getBoard} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service 層は {@link MockitoBean} で差し替える。
 * {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security フィルタを無効化し、
 * {@link SecurityContextHolder} に直接認証情報をセットする（既存
 * {@code MyCorkboardPinControllerIT} と同一パターン）。</p>
 *
 * <p>カバー範囲:</p>
 * <ul>
 *   <li>200: 組織所属メンバーがボード詳細を取得</li>
 *   <li>403: Service が CORKBOARD_009 を投げる（非所属）</li>
 *   <li>404: Service が CORKBOARD_001 を投げる（ボード未存在）</li>
 *   <li>401: 未認証（SecurityContext 空）</li>
 * </ul>
 */
@WebMvcTest(OrganizationCorkboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrganizationCorkboardController#getBoard 結合テスト (F09.8 Phase A2)")
class OrganizationCorkboardControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 200L;
    private static final Long BOARD_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CorkboardService corkboardService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** mock 用に最小限の組織ボード詳細レスポンスを生成する。 */
    private CorkboardDetailResponse stubDetail() {
        return new CorkboardDetailResponse(
                BOARD_ID, "ORGANIZATION", ORG_ID, null, "組織ボード",
                "CORK", "ADMIN_ONLY", false, 0L,
                List.of(), List.of(), null, null);
    }

    @Nested
    @DisplayName("GET /api/v1/organizations/{orgId}/corkboards/{boardId}")
    class GetBoard {

        @Test
        @DisplayName("正常系: 組織所属メンバー → 200")
        void 正常系_200() throws Exception {
            given(corkboardService.getOrganizationBoardDetail(eq(ORG_ID), eq(BOARD_ID), eq(USER_ID)))
                    .willReturn(stubDetail());

            mockMvc.perform(get("/api/v1/organizations/{orgId}/corkboards/{boardId}", ORG_ID, BOARD_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(BOARD_ID))
                    .andExpect(jsonPath("$.data.scopeType").value("ORGANIZATION"))
                    .andExpect(jsonPath("$.data.scopeId").value(ORG_ID))
                    .andExpect(jsonPath("$.data.name").value("組織ボード"))
                    .andExpect(jsonPath("$.data.cards").isArray())
                    .andExpect(jsonPath("$.data.groups").isArray());

            verify(corkboardService).getOrganizationBoardDetail(ORG_ID, BOARD_ID, USER_ID);
        }

        @Test
        @DisplayName("非所属: Service が CORKBOARD_009 → 403")
        void 非所属_403() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION))
                    .given(corkboardService).getOrganizationBoardDetail(eq(ORG_ID), eq(BOARD_ID), eq(USER_ID));

            mockMvc.perform(get("/api/v1/organizations/{orgId}/corkboards/{boardId}", ORG_ID, BOARD_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("ボード未存在: Service が CORKBOARD_001 → 404")
        void ボード未存在_404() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND))
                    .given(corkboardService).getOrganizationBoardDetail(eq(ORG_ID), eq(BOARD_ID), eq(USER_ID));

            mockMvc.perform(get("/api/v1/organizations/{orgId}/corkboards/{boardId}", ORG_ID, BOARD_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_001"));
        }

        @Test
        @DisplayName("未認証: SecurityContext 空 → 401")
        void 未認証_401() throws Exception {
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/organizations/{orgId}/corkboards/{boardId}", ORG_ID, BOARD_ID))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("COMMON_000"));
        }
    }
}
