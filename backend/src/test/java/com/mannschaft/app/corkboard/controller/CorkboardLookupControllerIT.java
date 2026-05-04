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
 * F09.8 Phase A2 {@link CorkboardLookupController#getBoard} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service 層は {@link MockitoBean} で差し替える。
 * {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security フィルタを無効化し、
 * {@link SecurityContextHolder} に直接認証情報をセットする（既存
 * {@code MyCorkboardPinControllerIT} と同一パターン）。</p>
 *
 * <p>カバー範囲:</p>
 * <ul>
 *   <li>200: 任意の scope のボード詳細取得</li>
 *   <li>403: Service が CORKBOARD_009 を投げた場合</li>
 *   <li>404: Service が CORKBOARD_001 を投げた場合</li>
 *   <li>401: 未認証（SecurityContextHolder 空）</li>
 * </ul>
 */
@WebMvcTest(CorkboardLookupController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CorkboardLookupController#getBoard 結合テスト (F09.8 Phase A2)")
class CorkboardLookupControllerIT {

    private static final Long USER_ID = 1L;
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

    /** mock 用に最小限の詳細レスポンスを生成する。 */
    private CorkboardDetailResponse stubDetail(String scopeType) {
        return new CorkboardDetailResponse(
                BOARD_ID, scopeType, null, USER_ID, "テストボード",
                "CORK", "ADMIN_ONLY", false, 0L,
                List.of(), List.of(), null, null);
    }

    @Nested
    @DisplayName("GET /api/v1/corkboards/{boardId}")
    class GetBoard {

        @Test
        @DisplayName("正常系: 200 + camelCase で詳細を返す")
        void 正常系_200() throws Exception {
            given(corkboardService.getBoardDetailByIdOnly(eq(BOARD_ID), eq(USER_ID)))
                    .willReturn(stubDetail("PERSONAL"));

            mockMvc.perform(get("/api/v1/corkboards/{boardId}", BOARD_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(BOARD_ID))
                    .andExpect(jsonPath("$.data.scopeType").value("PERSONAL"))
                    .andExpect(jsonPath("$.data.name").value("テストボード"))
                    .andExpect(jsonPath("$.data.backgroundStyle").value("CORK"))
                    .andExpect(jsonPath("$.data.editPolicy").value("ADMIN_ONLY"))
                    .andExpect(jsonPath("$.data.cards").isArray())
                    .andExpect(jsonPath("$.data.groups").isArray());

            verify(corkboardService).getBoardDetailByIdOnly(BOARD_ID, USER_ID);
        }

        @Test
        @DisplayName("権限不足: Service が CORKBOARD_009 を投げる → 403")
        void 権限不足_403() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.INSUFFICIENT_PERMISSION))
                    .given(corkboardService).getBoardDetailByIdOnly(eq(BOARD_ID), eq(USER_ID));

            mockMvc.perform(get("/api/v1/corkboards/{boardId}", BOARD_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_009"));
        }

        @Test
        @DisplayName("ボード未存在: Service が CORKBOARD_001 を投げる → 404")
        void ボード未存在_404() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND))
                    .given(corkboardService).getBoardDetailByIdOnly(eq(BOARD_ID), eq(USER_ID));

            mockMvc.perform(get("/api/v1/corkboards/{boardId}", BOARD_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_001"));
        }

        @Test
        @DisplayName("未認証: SecurityContext 空 → 401")
        void 未認証_401() throws Exception {
            // SecurityUtils.getCurrentUserId() が COMMON_000 を投げる経路
            SecurityContextHolder.clearContext();

            mockMvc.perform(get("/api/v1/corkboards/{boardId}", BOARD_ID))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("COMMON_000"));
        }
    }
}
