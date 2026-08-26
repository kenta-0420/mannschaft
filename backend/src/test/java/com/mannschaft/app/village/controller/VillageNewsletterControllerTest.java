package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.NewsletterSettingResponse;
import com.mannschaft.app.village.dto.NewsletterSettingsResponse;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.service.VillageNewsletterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 Phase 3-β-E — VillageNewsletterController 契約テスト（課題D・19 件目の契約不一致）。
 *
 * <p>FE/BE の契約不一致（GET の Select 常時空・PUT 必ず 400）を回帰させないための柵。
 * BE の実契約を **characterization**（現状固定）し、以下を機械的に守る:</p>
 *
 * <ul>
 *   <li>GET は {@code data.settings} 配列を返し、単一の {@code data.frequency} は存在しない</li>
 *   <li>PUT は {@code {frequency, isEnabled}} を受けて単一 setting を 200 で返す</li>
 *   <li>PUT で {@code isEnabled} 欠落 / {@code frequency=DAILY}（enum 外）は 400</li>
 *   <li>権限あり（HEADMAN / ELDER）は 200・権限なし（村人）は 403（MODERATION_FORBIDDEN）</li>
 *   <li>opt-out(POST) / opt-in(DELETE) は 204 No Content（本体なし）</li>
 * </ul>
 *
 * <p>金型: {@code VillageJoinRequestControllerTest}（Bean 直呼び禁止・MockMvc 経由。
 * {@code TEST_CONVENTION.md §3.1.1}）。認可は Service 内にあるため、
 * @WebMvcTest では Service モックの戻り/throw で「許可/拒否」を再現する。</p>
 */
@WebMvcTest(VillageNewsletterController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageNewsletterController 契約テスト")
class VillageNewsletterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageNewsletterService service;

    /** ②-4 で Controller に注入された号 API サービス。設定系テストでは未使用だが context 解決に必要。 */
    @MockitoBean
    private com.mannschaft.app.village.service.VillageNewsletterIssueService issueService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID SETTING_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    private NewsletterSettingResponse weeklyEnabled() {
        return NewsletterSettingResponse.builder()
                .id(SETTING_ID)
                .villageId(VILLAGE_ID)
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .isEnabled(true)
                .lastSentAt(LocalDateTime.now())
                .nextScheduledAt(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .version(1L)
                .build();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/villages/{id}/newsletter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET newsletter — settings 配列を返し、単一 frequency は存在しない")
    void get_returnsSettingsArray() throws Exception {
        NewsletterSettingsResponse response = NewsletterSettingsResponse.builder()
                .villageId(VILLAGE_ID)
                .settings(List.of(weeklyEnabled()))
                .optedOut(false)
                .build();
        given(service.getNewsletterSettings(eq(VILLAGE_ID), eq(USER_ID))).willReturn(response);

        mockMvc.perform(get("/api/v1/villages/{villageId}/newsletter", VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings").isArray())
                .andExpect(jsonPath("$.data.settings[0].frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.data.settings[0].isEnabled").value(true))
                .andExpect(jsonPath("$.data.optedOut").value(false))
                // フラット単一形状ではないこと（旧契約の回帰防止）
                .andExpect(jsonPath("$.data.frequency").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/villages/{id}/newsletter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT newsletter — {frequency, isEnabled} で 200・単一 setting を返す（HEADMAN/ELDER 許可）")
    void put_success() throws Exception {
        given(service.updateNewsletterSettings(eq(VILLAGE_ID), any(), eq(USER_ID)))
                .willReturn(weeklyEnabled());

        String body = """
                {
                  "frequency": "WEEKLY",
                  "isEnabled": true
                }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.data.isEnabled").value(true))
                // 戻りは単一 setting（配列ではない）
                .andExpect(jsonPath("$.data.settings").doesNotExist());
    }

    @Test
    @DisplayName("PUT newsletter — isEnabled 欠落は 400（@NotNull）")
    void put_missingIsEnabled() throws Exception {
        String body = """
                {
                  "frequency": "WEEKLY"
                }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT newsletter — frequency=DAILY（enum 外）は 400")
    void put_invalidFrequency() throws Exception {
        String body = """
                {
                  "frequency": "DAILY",
                  "isEnabled": true
                }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT newsletter — 村人（HEADMAN/ELDER でない）は 403")
    void put_forbiddenForVillager() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(service).updateNewsletterSettings(eq(VILLAGE_ID), any(), eq(USER_ID));

        String body = """
                {
                  "frequency": "WEEKLY",
                  "isEnabled": true
                }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    // ------------------------------------------------------------------
    // POST / DELETE /api/v1/villages/{id}/newsletter/opt-out
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST opt-out — 204 No Content（本体なし）")
    void optOut_noContent() throws Exception {
        mockMvc.perform(post("/api/v1/villages/{villageId}/newsletter/opt-out", VILLAGE_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE opt-out — opt-in 復帰も 204 No Content（本体なし）")
    void optIn_noContent() throws Exception {
        mockMvc.perform(delete("/api/v1/villages/{villageId}/newsletter/opt-out", VILLAGE_ID))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/villages/{id}/newsletter/send-logs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET send-logs — frequency 指定で配信ログ配列を 200 で返す")
    void sendLogs_success() throws Exception {
        given(service.listSendLogs(eq(VILLAGE_ID), eq(VillageNewsletterFrequency.WEEKLY), any()))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/villages/{villageId}/newsletter/send-logs", VILLAGE_ID)
                        .param("frequency", "WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
