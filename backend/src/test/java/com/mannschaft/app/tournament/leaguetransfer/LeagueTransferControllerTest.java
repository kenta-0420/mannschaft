package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.TournamentErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LeagueTransferController} の HTTP 契約テスト。
 *
 * <p>検分指摘🟡-1 の根治確認: {@code direction} クエリパラメータに不正値が来たとき、従来は
 * Service 層の {@code LeagueTransferDirection.valueOf(...)} が {@link IllegalArgumentException} を投げ
 * **500** になっていた。{@code @RequestParam LeagueTransferDirection} の enum 直バインドへ切り替えたことで
 * Spring が {@code MethodArgumentTypeMismatchException}（→ {@link GlobalExceptionHandler} で 400 / COMMON_001）
 * に倒すことを検証する。あわせて、TOUR_038 が HTTP レイヤを通しても 404 になること（🔴-1）も確認する。</p>
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} ＋ 実 {@link GlobalExceptionHandler} で、フルコンテキスト依存を
 * 避けてコントローラ→アドバイスのステータス契約のみを検証する（既存
 * {@code OrganizationTournamentSummaryControllerSecurityTest} と同方式）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeagueTransferController HTTP 契約テスト")
class LeagueTransferControllerTest {

    @Mock
    private LeagueTransferService transferService;

    @InjectMocks
    private LeagueTransferController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // ========================================================================
    // 🟡-1: direction 不正値 → 400（旧: 500）
    // ========================================================================

    @Test
    @DisplayName("transfer-candidates: direction 不正値は 400（型変換エラー → COMMON_001）")
    void candidates_invalidDirection_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/100/tournaments/200/transfer-candidates")
                        .param("direction", "SIDEWAYS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("transfer-candidates: direction 正常値（PROMOTION）は 200")
    void candidates_validDirection_200() throws Exception {
        given(transferService.getTransferCandidates(
                eq(100L), eq(200L), eq(LeagueTransferDirection.PROMOTION), anyLong()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/organizations/100/tournaments/200/transfer-candidates")
                        .param("direction", "PROMOTION"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("inbound-transfers: direction 不正値は 400（型変換エラー → COMMON_001）")
    void inbound_invalidDirection_400() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/100/inbound-transfers")
                        .param("direction", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("inbound-transfers: direction 省略は 200（required=false）")
    void inbound_noDirection_200() throws Exception {
        given(transferService.listInbound(eq(100L), eq(null), anyLong()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/organizations/100/inbound-transfers"))
                .andExpect(status().isOk());
    }

    // ========================================================================
    // 🔴-1: TOUR_038 が HTTP レイヤを通しても 404（IDOR 隠蔽）
    // ========================================================================

    @Test
    @DisplayName("inbound-transfers: Service が TOUR_038 を投げると HTTP 404 になる")
    void inbound_notFound_404() throws Exception {
        given(transferService.listInbound(anyLong(), any(), anyLong()))
                .willThrow(new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/organizations/100/inbound-transfers")
                        .param("direction", "PROMOTION"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("transfer-candidates: Service が TOUR_039 を投げると HTTP 403 になる")
    void candidates_forbidden_403() throws Exception {
        given(transferService.getTransferCandidates(anyLong(), anyLong(), any(), anyLong()))
                .willThrow(new BusinessException(TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN));

        mockMvc.perform(get("/api/v1/organizations/100/tournaments/200/transfer-candidates")
                        .param("direction", "PROMOTION"))
                .andExpect(status().isForbidden());
    }
}
