package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.controller.OrganizationTournamentSummaryController;
import com.mannschaft.app.tournament.dto.OrganizationTournamentSummaryResponse;
import com.mannschaft.app.tournament.service.OrganizationTournamentSummaryService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.7.1 / 02 ②: {@link OrganizationTournamentSummaryController} の認可契約テスト。
 *
 * <p>設計書 §6: ORG_TOURNAMENT_SUMMARY は組織 MEMBER 以上。</p>
 *
 * <p>Spring の WebMvc フルコンテキスト（UserLocaleFilter / proxy 系 Bean 等）への依存を避け、
 * {@code MockMvcBuilders.standaloneSetup} ＋ {@link GlobalExceptionHandler}（実 MessageSource 注入）で
 * コントローラ→サービスの認可契約のみを検証する。{@link SecurityUtils} の静的呼び出しをモックして
 * 未認証=401（COMMON_000）/ 非メンバー=403（COMMON_002）/ メンバー=200 を確認する。
 * HTTP マッピングは {@link GlobalExceptionHandler}（COMMON_000=401 / COMMON_002=403）に従う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationTournamentSummaryController 認可契約テスト")
class OrganizationTournamentSummaryControllerSecurityTest {

    private static final String URL = "/api/v1/organizations/100/tournaments/summary";

    @Mock
    private OrganizationTournamentSummaryService summaryService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private OrganizationTournamentSummaryController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    @Test
    @DisplayName("未認証は 401（SecurityUtils.getCurrentUserId が COMMON_000）")
    void unauthenticated_401() throws Exception {
        securityUtils.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("組織メンバーでないユーザーは 403（checkMembership が COMMON_002 を投げる）")
    void notMember_403() throws Exception {
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(anyLong(), anyLong(), anyString());

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("組織メンバーは 200 でサマリを取得できる")
    void member_200() throws Exception {
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        doNothing().when(accessControlService).checkMembership(anyLong(), anyLong(), anyString());
        given(summaryService.getSummary(anyLong()))
                .willReturn(OrganizationTournamentSummaryResponse.builder()
                        .tournaments(List.of())
                        .build());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk());
    }
}
