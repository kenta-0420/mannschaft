package com.mannschaft.app.advertising.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.dto.RecordAdClickRequest;
import com.mannschaft.app.advertising.service.AdClickService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F09.7 {@link AdClickController} の MockMvc テスト。
 *
 * <p>カバー範囲:</p>
 * <ul>
 *   <li>正常系: userId あり → 201 Created</li>
 *   <li>正常系: userId=null（未ログイン） → 201 Created</li>
 *   <li>異常系: campaignId=null → 400 Bad Request（Bean Validation）</li>
 *   <li>異常系: adId 不正で Service が例外 → 404 Not Found</li>
 * </ul>
 */
@WebMvcTest(AdClickController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdClickController MockMvc テスト (F09.7)")
class AdClickControllerTest {

    private static final Long AD_ID = 1L;
    private static final Long CAMPAIGN_ID = 10L;
    private static final Long IMPRESSION_ID = 100L;
    private static final Long USER_ID = 42L;
    private static final Long CLICK_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdClickService adClickService;

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

    @Nested
    @DisplayName("POST /api/v1/ads/{adId}/click")
    class RecordClick {

        @Test
        @DisplayName("正常系: userId あり → 201 Created, id と occurredAt が返る")
        void 正常系_userId_あり_201() throws Exception {
            RecordAdClickRequest req = new RecordAdClickRequest(CAMPAIGN_ID, IMPRESSION_ID, USER_ID);
            given(adClickService.record(eq(AD_ID), eq(CAMPAIGN_ID), eq(IMPRESSION_ID), eq(USER_ID)))
                    .willReturn(CLICK_ID);

            mockMvc.perform(post("/api/v1/ads/{adId}/click", AD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(CLICK_ID))
                    .andExpect(jsonPath("$.data.occurredAt").exists());
        }

        @Test
        @DisplayName("正常系: userId=null（未ログイン） → 201 Created")
        void 正常系_userId_null_未ログイン_201() throws Exception {
            RecordAdClickRequest req = new RecordAdClickRequest(CAMPAIGN_ID, null, null);
            given(adClickService.record(eq(AD_ID), eq(CAMPAIGN_ID), eq(null), eq(null)))
                    .willReturn(CLICK_ID);

            mockMvc.perform(post("/api/v1/ads/{adId}/click", AD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(CLICK_ID));
        }

        @Test
        @DisplayName("異常系: campaignId=null → 400 Bad Request（Bean Validation）")
        void 異常系_campaignId_null_400() throws Exception {
            RecordAdClickRequest req = new RecordAdClickRequest(null, IMPRESSION_ID, USER_ID);

            mockMvc.perform(post("/api/v1/ads/{adId}/click", AD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: adId が存在しない（Service が AD_CAMPAIGN_NOT_FOUND 例外）→ 404 Not Found")
        void 異常系_adId_不正_404() throws Exception {
            Long unknownAdId = 99999L;
            RecordAdClickRequest req = new RecordAdClickRequest(CAMPAIGN_ID, null, null);

            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND))
                    .given(adClickService)
                    .record(eq(unknownAdId), any(), any(), any());

            mockMvc.perform(post("/api/v1/ads/{adId}/click", unknownAdId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_FOUND"));
        }
    }
}
