package com.mannschaft.app.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link ScopeSlugIdConverter} 契約テスト（組織 slug ルーティング 400 バグの根治）。
 *
 * <h3>背景（実機 E2E で観測）</h3>
 * <p>{@code /api/v1/organizations/{organizationId}/...} に slug 文字列を渡すと 400 になっていた。
 * 原因は、グローバル登録された {@code Converter<String,Long>} が slug を <b>team テーブル</b>でしか
 * 解決せず、組織 slug の解決に失敗 → Spring が型変換失敗として 400（MethodArgumentTypeMismatch）へ
 * 落としていたため。String→Long 変換器は 1 つしか選べないため、team/org を統合した本コンバータで
 * URI のスコープ種別を見て解決先を切り替える。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-1: {@code /organizations/{slug}} は organization として解決され 200（バグ根治）</li>
 *   <li>AC-2: {@code /teams/{slug}} は従来どおり team として解決される（回帰防止）</li>
 *   <li>AC-3: 数値 id は team/org とも従来どおりそのまま Long へ（サービス呼び出しなし）</li>
 *   <li>AC-4: 不在 slug は 404（400 ではない）</li>
 * </ul>
 *
 * <p>Docker/Testcontainers 不要の standalone MockMvc スライス。実コンバータ + モック Service を
 * 変換サービスへ組み込み、URI からのスコープ判定と解決経路を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeSlugIdConverter 契約テスト（組織 slug ルーティング 400 根治）")
class ScopeSlugIdConverterContractTest {

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ScopeSlugIdConverter converter = new ScopeSlugIdConverter(teamService, organizationService);
        FormattingConversionService conversionService = new DefaultFormattingConversionService();
        conversionService.addConverter(converter);
        MessageSource ms = new StaticMessageSource();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestScopeController())
                .setConversionService(conversionService)
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
    }

    // ================= AC-1: 組織 slug → 200（根治） =================

    @Test
    @DisplayName("AC-1: 組織エンドポイントに slug → organization として解決され 200")
    void organizationSlug_resolvedViaOrganizationService_returns200() throws Exception {
        given(organizationService.resolveOrgId("my-org")).willReturn(4242L);

        mockMvc.perform(get("/api/v1/organizations/my-org/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("4242"));

        // 組織 slug を team 側で解決してはならない（400 バグの原因）
        verify(teamService, never()).resolveTeamId("my-org");
    }

    // ================= AC-2: チーム slug → 200（回帰防止） =================

    @Test
    @DisplayName("AC-2: チームエンドポイントに slug → team として解決され 200（既存挙動維持）")
    void teamSlug_resolvedViaTeamService_returns200() throws Exception {
        given(teamService.resolveTeamId("my-team")).willReturn(77L);

        mockMvc.perform(get("/api/v1/teams/my-team/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("77"));

        verify(organizationService, never()).resolveOrgId("my-team");
    }

    // ================= AC-3: 数値 id はそのまま =================

    @Test
    @DisplayName("AC-3: 組織エンドポイントに数値 id → サービス呼び出しなしでそのまま Long")
    void organizationNumericId_passesThrough() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/999/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("999"));

        verify(organizationService, never()).resolveOrgId("999");
        verify(teamService, never()).resolveTeamId("999");
    }

    @Test
    @DisplayName("AC-3: チームエンドポイントに数値 id → サービス呼び出しなしでそのまま Long")
    void teamNumericId_passesThrough() throws Exception {
        mockMvc.perform(get("/api/v1/teams/555/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("555"));

        verify(teamService, never()).resolveTeamId("555");
        verify(organizationService, never()).resolveOrgId("555");
    }

    // ================= AC-4: 不在 slug → 404 =================

    @Test
    @DisplayName("AC-4: 不在の組織 slug → 404（400 ではない）")
    void missingOrganizationSlug_returns404() throws Exception {
        given(organizationService.resolveOrgId("ghost-org"))
                .willThrow(new BusinessException(OrgErrorCode.ORG_001));

        mockMvc.perform(get("/api/v1/organizations/ghost-org/probe"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-4: 不在のチーム slug → 404（400 ではない）")
    void missingTeamSlug_returns404() throws Exception {
        given(teamService.resolveTeamId("ghost-team"))
                .willThrow(new IllegalStateException("team not found"));

        mockMvc.perform(get("/api/v1/teams/ghost-team/probe"))
                .andExpect(status().isNotFound());
    }

    /**
     * テスト専用コントローラ。org / team の {@code @PathVariable Long} を実コントローラと同形で受け、
     * 変換後の数値 id をそのまま返す。
     */
    @RestController
    static class TestScopeController {

        @GetMapping("/api/v1/organizations/{organizationId}/probe")
        String org(@PathVariable Long organizationId) {
            return String.valueOf(organizationId);
        }

        @GetMapping("/api/v1/teams/{teamId}/probe")
        String team(@PathVariable Long teamId) {
            return String.valueOf(teamId);
        }
    }
}
