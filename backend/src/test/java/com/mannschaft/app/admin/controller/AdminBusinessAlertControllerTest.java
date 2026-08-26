package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AdminBusinessAlertSummaryResponse;
import com.mannschaft.app.admin.security.AdminRoleChecker;
import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link AdminBusinessAlertController} の単体テスト（F10.7）。
 *
 * <p>{@code @WebMvcTest + addFilters=false} でコントローラー層のみをロードし、
 * セキュリティフィルターはオフにした上でセキュリティコンテキストを手動設定してテストする。</p>
 *
 * <p>NOTE: {@code @WebMvcTest + @EnableMethodSecurity} の非互換問題があるため、
 * 401/403 テストは {@link AdminRoleChecker} の返値で制御するのではなく、
 * セキュリティコンテキスト操作で代替している。</p>
 */
@DisplayName("AdminBusinessAlertController 単体テスト")
public class AdminBusinessAlertControllerTest {

    @Nested
    @DisplayName("GET /api/v1/admin/business-alerts/summary")
    @WebMvcTest(AdminBusinessAlertController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class GetSummaryTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AdminBusinessAlertService adminBusinessAlertService;

        @MockitoBean
        private AdminRoleChecker adminRoleChecker;

        @MockitoBean
        private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

        // F11.3: UserLocaleFilter の依存解決用（@WebMvcTest コンテキストで必要）
        @MockitoBean
        private UserLocaleCache userLocaleCache;

        // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
        @MockitoBean
        private ProxyInputConsentRepository proxyInputConsentRepository;
        @MockitoBean
        private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

        private AdminBusinessAlertSummaryResponse buildSummaryResponse(
                int newReservations, int pendingApproval, int unreadInquiries) {
            return AdminBusinessAlertSummaryResponse.builder()
                    .data(AdminBusinessAlertSummaryResponse.Data.builder()
                            .teams(List.of(
                                    AdminBusinessAlertSummaryResponse.TeamAlert.builder()
                                            .teamId(10L)
                                            .teamName("テストチーム")
                                            .reservationModuleEnabled(true)
                                            .alerts(AdminBusinessAlertSummaryResponse.Alerts.builder()
                                                    .newReservations(newReservations)
                                                    .pendingApproval(pendingApproval)
                                                    .unreadInquiries(unreadInquiries)
                                                    .build())
                                            .links(AdminBusinessAlertSummaryResponse.Links.builder()
                                                    .reservationsUrl("/teams/10/reservations")
                                                    .inquiryChannelUrl(null)
                                                    .build())
                                            .build()
                            ))
                            .totalPending(newReservations + pendingApproval + unreadInquiries)
                            .build())
                    .build();
        }

        @BeforeEach
        void clearSecurityContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("未認証ユーザー（セキュリティコンテキストなし）は4xxまたは5xxを返す")
        void getSummary_未認証_4xxまたは5xx() throws Exception {
            // Given: セキュリティコンテキストが空（認証なし）
            // addFilters=false の環境では実際のフィルターチェーンは動作しないため、
            // SecurityUtils.getCurrentUserId() が BusinessException をスローして 4xx/5xx となる。

            // When & Then: セキュリティコンテキストなしでは正常応答（200）にならない
            mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                    .andExpect(status().is4xxClientError()); // SecurityUtils → anonymousUser → 401
        }

        @Test
        @DisplayName("ADMIN権限あり（認証済み）の場合は200を返す")
        void getSummary_ADMIN権限あり_200() throws Exception {
            // Given: 認証済み ADMIN ユーザー
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));

            AdminBusinessAlertSummaryResponse response = buildSummaryResponse(3, 1, 2);
            given(adminRoleChecker.hasAnyAdminRoleInAnyTeam(
                    SecurityContextHolder.getContext().getAuthentication()))
                    .willReturn(true);
            given(adminBusinessAlertService.getSummary(1L)).willReturn(response);

            // When & Then
            // ApiResponse<AdminBusinessAlertSummaryResponse> の JSON 構造は
            // { "data": { "data": { "teams": [...], "totalPending": N } } }
            mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.data.teams[0].teamId").value(10))
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.newReservations").value(3))
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.pendingApproval").value(1))
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.unreadInquiries").value(2))
                    .andExpect(jsonPath("$.data.data.totalPending").value(6));
        }

        @Test
        @DisplayName("レスポンスに newReservations, pendingApproval, unreadInquiries フィールドが存在する")
        void getSummary_レスポンスフィールド存在確認() throws Exception {
            // Given
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));

            AdminBusinessAlertSummaryResponse response = buildSummaryResponse(0, 0, 0);
            given(adminBusinessAlertService.getSummary(anyLong())).willReturn(response);

            // When & Then: 必須フィールドが存在すること
            // ApiResponse<AdminBusinessAlertSummaryResponse> の JSON 構造:
            // { "data": { "data": { "teams": [...], "totalPending": N } } }
            mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").exists())
                    .andExpect(jsonPath("$.data.data.teams").isArray())
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.newReservations").exists())
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.pendingApproval").exists())
                    .andExpect(jsonPath("$.data.data.teams[0].alerts.unreadInquiries").exists())
                    .andExpect(jsonPath("$.data.data.totalPending").exists());
        }
    }
}
