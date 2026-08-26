package com.mannschaft.app.schedule;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.17 キープ（日付未定の予定） 未ログイン契約テスト（試練 Wave1・AC-16b）。
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §9 AC-16b「未ログインは401」。
 * {@link ScheduleKeepTeamContractIT} は {@code addFilters = false} で Spring Security の
 * フィルタチェーンを外して {@link org.springframework.security.core.context.SecurityContextHolder}
 * を直接差し替える方式のため、未認証時の deny-by-default（401）を検証できない。
 * 本クラスは実フィルタチェーンを通す既定の {@code @AutoConfigureMockMvc} を用い、
 * 認証情報を一切設定しないリクエストが 401 になることのみを検証する
 * （金型: {@code SecurityConfigAuthorizationTest} の deny-by-default 方針）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.17 キープ 未ログイン契約テスト（試練 Wave1・AC-16b）")
class ScheduleKeepUnauthenticatedContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-16b: 未ログインでのチームキープ一覧GETは401")
    void AC16b_未ログインの一覧GETは401() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", "any-team"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-16b: 未ログインでのチームキープ単体GETは401")
    void AC16b_未ログインの単体GETは401() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}",
                        "any-team", "01920c8e-0000-7000-8000-000000000000"))
                .andExpect(status().isUnauthorized());
    }
}
