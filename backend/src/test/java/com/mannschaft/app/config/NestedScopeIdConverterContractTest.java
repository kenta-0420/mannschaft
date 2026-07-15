package com.mannschaft.app.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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
 * <p>{@link ScopeSlugIdConverter} は {@code Converter<String,Long>} である。Spring MVC は変換対象の
 * 「変数名（orgId/teamId）」も「型」も変換器へ渡さないため、本コンバータはやむを得ず
 * リクエスト URI の直前セグメント（{@code organizations/{v}} か {@code teams/{v}} か）を見てスコープを推定する。
 * ところが <b>orgSlug と teamSlug が同一文字列</b>のとき、teamId 変換時にも URI 中で最初に一致する
 * {@code organizations/{v}} 側セグメントを拾ってしまい、<b>teamId が組織 ID へ誤解決</b>される。</p>
 *
 * <p>数値 ID では発生しない（parse 高速パスで URI を見ないため）。既存の
 * {@link ScopeSlugIdConverterContractTest} は AC-1〜4 すべて <b>数値 ID か、単一スコープの slug</b> で
 * 構成されており、この <b>ネスト同一 slug という盲点を素通り</b>していた。本テストがその盲点を埋める。</p>
 *
 * <p>本テストは案A（型付きパス変数 {@code OrgScopeId}/{@code TeamScopeId} ＋ 型別コンバータ）で green 化する。
 * red 段階では既存の単一 {@code Converter<String,Long>} を用い、同一 slug で teamId が org へ誤解決される
 * ことを実証して失敗する。</p>
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
        // red 段階: 既存の単一 String→Long コンバータのみを登録する（構造的欠陥を実証するため）。
        ScopeSlugIdConverter converter = new ScopeSlugIdConverter(teamService, organizationService);
        FormattingConversionService conversionService = new DefaultFormattingConversionService();
        conversionService.addConverter(converter);
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
        lenient().when(organizationService.resolveOrgId("dup")).thenReturn(10L);
        lenient().when(teamService.resolveTeamId("dup")).thenReturn(20L);

        mockMvc.perform(get("/api/v1/organizations/dup/teams/dup/nested-probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("org=10,team=20"));
    }

    /** ネスト二重スコープの probe。org / team を受け、両解決値を検証しやすい形で返す。 */
    @RestController
    static class NestedProbeController {

        @GetMapping("/api/v1/organizations/{organizationId}/teams/{teamId}/nested-probe")
        String nested(@PathVariable Long organizationId, @PathVariable Long teamId) {
            return "org=" + organizationId + ",team=" + teamId;
        }
    }
}
