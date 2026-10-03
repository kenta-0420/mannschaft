package com.mannschaft.app.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mannschaft.app.common.GlobalExceptionHandler;
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
 * ネスト二重スコープ {@code /organizations/{orgId}/teams/{teamId}} の slug 解決契約テスト（課題 #12・構造的欠陥の根治）。
 *
 * <h3>背景（構造的欠陥）</h3>
 * <p>旧実装 {@link ScopeSlugIdConverter} は {@code Converter<String,Long>} である。Spring MVC は変換対象の
 * 「変数名（orgId/teamId）」も「型」も変換器へ渡さないため、旧コンバータはやむを得ず
 * リクエスト URI の直前セグメント（{@code organizations/{v}} か {@code teams/{v}} か）を見てスコープを推定していた。
 * ところが <b>orgSlug と teamSlug が同一文字列</b>のとき、teamId 変換時にも URI 中で最初に一致する
 * {@code organizations/{v}} 側セグメントを拾ってしまい、<b>teamId が組織 ID へ誤解決</b>された。</p>
 *
 * <p>数値 ID では発生しない（parse 高速パスで URI を見ないため）。既存の
 * {@link ScopeSlugIdConverterContractTest} は AC-1〜4 すべて <b>数値 ID か、単一スコープの slug</b> で
 * 構成されており、この <b>ネスト同一 slug という盲点を素通り</b>していた。本テストがその盲点を埋める。</p>
 *
 * <h3>根治（案A・型付きパス変数）</h3>
 * <p>変換先を org 用 {@link OrgScopeId} / team 用 {@link TeamScopeId} に型分離し、
 * {@link OrgScopeIdConverter}（{@code String→OrgScopeId}）と {@link TeamScopeIdConverter}
 * （{@code String→TeamScopeId}）を登録する。Spring は <b>変換先の型で</b>変換器を一意に選ぶため、
 * 同一 slug でも org は org・team は team として独立に解決され、URI 推定を完全に排する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ネスト二重スコープ slug 解決契約テスト（課題 #12・同一 slug 誤解決の根治）")
class NestedScopeIdConverterContractTest {

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 案A: 型別コンバータを両方登録する（変換先型で一意に選ばれるため競合しない）。
        FormattingConversionService conversionService = new DefaultFormattingConversionService();
        conversionService.addConverter(new OrgScopeIdConverter(organizationService));
        conversionService.addConverter(new TeamScopeIdConverter(teamService));
        MessageSource ms = new StaticMessageSource();
        mockMvc = MockMvcBuilders.standaloneSetup(new NestedProbeController())
                .setConversionService(conversionService)
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
    }

    @Test
    @DisplayName("ネスト同一 slug: orgId=10 / teamId=20 に独立解決される（org へ潰れない）")
    void nestedSameSlug_resolvesOrgAndTeamIndependently() throws Exception {
        // 組織 slug と チーム slug が偶然同一文字列 "dup"。別 ID へ解決されるべき。
        given(organizationService.resolveOrgId("dup")).willReturn(10L);
        given(teamService.resolveTeamId("dup")).willReturn(20L);

        mockMvc.perform(get("/api/v1/organizations/dup/teams/dup/nested-probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("org=10,team=20"));

        // 解決経路が型で分離されていること（org は org・team は team 経由）。
        verify(organizationService).resolveOrgId("dup");
        verify(teamService).resolveTeamId("dup");
    }

    /** ネスト二重スコープの probe。案A の型付きパス変数で受け、両解決値を検証しやすい形で返す。 */
    @RestController
    static class NestedProbeController {

        @GetMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/nested-probe")
        String nested(@PathVariable OrgScopeId organizationId, @PathVariable TeamScopeId teamId) {
            return "org=" + organizationId.value() + ",team=" + teamId.value();
        }
    }
}
