package com.mannschaft.app.tournament;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
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
 *
 * <p>F08.7.1 slug 修正（path 変数 slug 受理）: ダッシュボードのウィジェットは URL に slug
 * （例 {@code org-000001}）を渡す。コントローラは {@code @PathVariable String orgId} を受け、
 * {@link OrganizationService#resolveOrgId(String)} で内部 BIGINT に解決してから認可・サービスへ渡す。
 * 旧実装は {@code @PathVariable Long} で slug を受けると Spring の型変換で 400 になり、
 * ウィジェットが空表示になっていた（survey の流儀へ整合）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationTournamentSummaryController 認可契約テスト")
class OrganizationTournamentSummaryControllerSecurityTest {

    /** ダッシュボードのウィジェットは数値 id ではなく slug を渡す。 */
    private static final String ORG_SLUG = "org-000001";
    private static final String URL = "/api/v1/organizations/" + ORG_SLUG + "/tournaments/summary";
    private static final long RESOLVED_ORG_ID = 100L;

    @Mock
    private OrganizationTournamentSummaryService summaryService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private OrganizationService organizationService;

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
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(RESOLVED_ORG_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(anyLong(), anyLong(), anyString());

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("組織メンバーは 200 でサマリを取得できる（slug を解決して内部 id をサービスへ渡す）")
    void member_200() throws Exception {
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(1L);
        given(organizationService.resolveOrgId(ORG_SLUG)).willReturn(RESOLVED_ORG_ID);
        doNothing().when(accessControlService).checkMembership(anyLong(), anyLong(), anyString());
        given(summaryService.getSummary(anyLong()))
                .willReturn(OrganizationTournamentSummaryResponse.builder()
                        .tournaments(List.of())
                        .build());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk());

        // slug を resolveOrgId で解決し、解決済み id を認可・サービスへ渡すこと
        verify(organizationService).resolveOrgId(ORG_SLUG);
        verify(accessControlService).checkMembership(eq(1L), eq(RESOLVED_ORG_ID), eq("ORGANIZATION"));
        verify(summaryService).getSummary(RESOLVED_ORG_ID);
    }
}
