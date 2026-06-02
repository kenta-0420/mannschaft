package com.mannschaft.app.advertising.campaign.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.controller.UserAdPreferencesController;
import com.mannschaft.app.advertising.campaign.dto.UpdateUserAdPreferencesRequest;
import com.mannschaft.app.advertising.campaign.dto.UserAdPreferenceResponse;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.service.UserAdPreferenceService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F09.17 Phase 11-a {@link UserAdPreferencesController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service は {@link MockitoBean} で差し替える。
 * 設計書「Preferences 域」§4 GET/PUT のレスポンス整合性と上限超過時のエラーマッピングを検証する。</p>
 *
 * <p>カバー範囲:</p>
 * <ul>
 *   <li>GET 初回: Service がデフォルト行を生成して返す → 200</li>
 *   <li>PUT: 反映 → 200, {@code consented_at} 設定</li>
 *   <li>PUT: blocked 100 件超過 → AD_PREFERENCES_BLOCKED_LIMIT → 400</li>
 * </ul>
 */
@WebMvcTest(UserAdPreferencesController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("UserAdPreferencesController 結合テスト (F09.17 Phase 11-a)")
class UserAdPreferencesControllerIT {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserAdPreferenceService preferenceService;

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

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

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

    private UserAdPreferenceResponse defaultPreferenceResponse() {
        return new UserAdPreferenceResponse(
                UUID.fromString("01933333-3333-7333-8333-333333333333"),
                true,
                true,
                true,
                true,
                List.of(),
                null,
                0,
                LocalDateTime.now());
    }

    private UserAdPreferenceResponse consentedPreferenceResponse() {
        return new UserAdPreferenceResponse(
                UUID.fromString("01933333-3333-7333-8333-333333333333"),
                false,
                true,
                true,
                true,
                List.of(100L, 101L),
                LocalDateTime.now(),
                0,
                LocalDateTime.now());
    }

    @Nested
    @DisplayName("GET /api/v1/me/ad-preferences")
    class GetPreferences {

        @Test
        @DisplayName("初回アクセス: Service がデフォルト行を返す → 200")
        void 初回アクセス_デフォルト返却_200() throws Exception {
            given(preferenceService.getOrCreateForUser(USER_ID))
                    .willReturn(defaultPreferenceResponse());

            mockMvc.perform(get("/api/v1/me/ad-preferences"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.acceptAnnouncementAds").value(true))
                    .andExpect(jsonPath("$.data.acceptEmailAds").value(true))
                    .andExpect(jsonPath("$.data.consentedAt").doesNotExist())
                    .andExpect(jsonPath("$.data.unsubscribeTokenVersion").value(0));

            verify(preferenceService).getOrCreateForUser(USER_ID);
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/me/ad-preferences")
    class UpdatePreferences {

        @Test
        @DisplayName("ハッピーパス: 反映 → 200, consented_at 設定")
        void 正常系_consented_at反映_200() throws Exception {
            UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                    false,
                    true,
                    true,
                    true,
                    List.of(100L, 101L),
                    false);

            given(preferenceService.updateForUser(eq(USER_ID),
                    any(UpdateUserAdPreferencesRequest.class)))
                    .willReturn(consentedPreferenceResponse());

            mockMvc.perform(put("/api/v1/me/ad-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.acceptAnnouncementAds").value(false))
                    .andExpect(jsonPath("$.data.consentedAt").exists())
                    .andExpect(jsonPath("$.data.blockedAdvertiserAccountIds[0]").value(100));

            verify(preferenceService).updateForUser(eq(USER_ID),
                    any(UpdateUserAdPreferencesRequest.class));
        }

        @Test
        @DisplayName("blocked 100件超過: AD_PREFERENCES_BLOCKED_LIMIT → 400")
        void 上限超過_400() throws Exception {
            List<Long> tooMany = LongStream.rangeClosed(1L, 101L).boxed().toList();
            UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                    null, null, null, null, tooMany, null);

            willThrow(new BusinessException(AdCampaignErrorCode.AD_PREFERENCES_BLOCKED_LIMIT))
                    .given(preferenceService)
                    .updateForUser(eq(USER_ID), any(UpdateUserAdPreferencesRequest.class));

            mockMvc.perform(put("/api/v1/me/ad-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AD_PREFERENCES_BLOCKED_LIMIT"));
        }

        @Test
        @DisplayName("rotateUnsubscribeTokens=true: Service に伝搬する")
        void トークンローテ_200() throws Exception {
            UpdateUserAdPreferencesRequest req = new UpdateUserAdPreferencesRequest(
                    null, null, null, null, null, true);

            UserAdPreferenceResponse rotated = new UserAdPreferenceResponse(
                    UUID.fromString("01933333-3333-7333-8333-333333333333"),
                    true, true, true, true, List.of(), LocalDateTime.now(), 1, LocalDateTime.now());
            given(preferenceService.updateForUser(eq(USER_ID),
                    any(UpdateUserAdPreferencesRequest.class)))
                    .willReturn(rotated);

            mockMvc.perform(put("/api/v1/me/ad-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unsubscribeTokenVersion").value(1));
        }
    }
}
