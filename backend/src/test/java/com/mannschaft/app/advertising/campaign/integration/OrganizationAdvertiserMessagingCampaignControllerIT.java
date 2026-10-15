package com.mannschaft.app.advertising.campaign.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.controller.OrganizationAdvertiserMessagingCampaignController;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.EstimatedReachRangeResponse;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.EstimatedReachRange;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.service.AdMessagingCampaignService;
import com.mannschaft.app.advertising.dto.AdvertiserAccountResponse;
import com.mannschaft.app.advertising.service.AdvertiserAccountService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F09.17 Phase 11-d-2 {@link OrganizationAdvertiserMessagingCampaignController} 結合テスト。
 *
 * <p>新規 scope ベース URL {@code /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging}
 * の動作を検証する。Service 呼び出しが {@code scope_type=ORGANIZATION, scope_id=organizationId} で
 * 行われることを検証する。</p>
 */
@WebMvcTest(OrganizationAdvertiserMessagingCampaignController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("OrganizationAdvertiserMessagingCampaignController 結合テスト (F09.17 Phase 11-d-2)")
class OrganizationAdvertiserMessagingCampaignControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 200L;
    private static final Long ADVERTISER_ACCOUNT_ID = 50L;
    private static final UUID CAMPAIGN_ID = UUID.fromString("01911111-1111-7111-8111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdMessagingCampaignService campaignService;
    @MockitoBean
    private AdvertiserAccountService advertiserAccountService;
    @MockitoBean
    private AccessControlService accessControlService;

    // フィルタ依存
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
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
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateCampaignRequest validCreateRequest() {
        return new CreateCampaignRequest(
                "組織キャンペーン",
                500_000L,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59),
                "Asia/Tokyo",
                5);
    }

    private CampaignDetailResponse stubDetail() {
        return new CampaignDetailResponse(
                CAMPAIGN_ID,
                ADVERTISER_ACCOUNT_ID,
                "組織キャンペーン",
                AdCampaignStatus.DRAFT,
                AdModerationStatus.PENDING,
                null,
                500_000L,
                0L,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59),
                "Asia/Tokyo",
                5,
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of(),
                List.of());
    }

    private CampaignListItemResponse stubListItem() {
        return new CampaignListItemResponse(
                CAMPAIGN_ID,
                "組織キャンペーン",
                AdCampaignStatus.DRAFT,
                AdModerationStatus.PENDING,
                500_000L,
                0L,
                LocalDateTime.of(2026, 6, 1, 0, 0),
                LocalDateTime.of(2026, 6, 30, 23, 59),
                "Asia/Tokyo",
                5,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private AdvertiserAccountResponse stubAdvertiserAccount() {
        return new AdvertiserAccountResponse(
                ADVERTISER_ACCOUNT_ID,
                ScopeType.ORGANIZATION,
                ORG_ID,
                null,
                "組織広告主",
                "org-advertiser@example.com",
                null,
                null,
                null,
                LocalDateTime.now());
    }

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging")
    class CreateCampaign {

        @Test
        @DisplayName("ハッピーパス: ORG ADMIN が DRAFT で作成 → 201、ORGANIZATION スコープで Service 呼び出し")
        void 正常系_201() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(advertiserAccountService.getByScope(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(stubAdvertiserAccount());
            given(campaignService.createCampaign(eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                    eq(ADVERTISER_ACCOUNT_ID), eq(USER_ID), any(CreateCampaignRequest.class)))
                    .willReturn(stubDetail());

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging",
                            ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(CAMPAIGN_ID.toString()));
        }

        @Test
        @DisplayName("権限拒否: ORG ADMIN 未満 → COMMON_002 → 403")
        void 権限拒否_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            mockMvc.perform(post("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging",
                            ORG_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging")
    class ListCampaigns {

        @Test
        @DisplayName("ハッピーパス: 1 件返す → 200、ORGANIZATION スコープで Service 呼び出し")
        void 正常系_200() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            Page<CampaignListItemResponse> page =
                    new PageImpl<>(List.of(stubListItem()), Pageable.unpaged(), 1);
            given(campaignService.listCampaigns(eq(ScopeType.ORGANIZATION), eq(ORG_ID), any(), any()))
                    .willReturn(page);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging",
                            ORG_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.total").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}")
    class GetCampaign {

        @Test
        @DisplayName("ハッピーパス: 詳細取得 → 200")
        void 正常系_200() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(campaignService.getCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(stubDetail());

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}",
                            ORG_ID, CAMPAIGN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(CAMPAIGN_ID.toString()));
        }

        @Test
        @DisplayName("テナント越境 (IDOR): AD_CAMPAIGN_NOT_FOUND → 404")
        void テナント越境_404() throws Exception {
            Long otherOrgId = 999L;
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, otherOrgId, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND))
                    .given(campaignService).getCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, otherOrgId);

            mockMvc.perform(get("/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}",
                            otherOrgId, CAMPAIGN_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}/preview")
    class PreviewReach {

        @Test
        @DisplayName("F09.19.7 AC-7.2: 推定リーチのレンジ/ラベルを返す → 200")
        void 正常系_range_label() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(campaignService.preview(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(EstimatedReachRangeResponse.of(EstimatedReachRange.RANGE_500_1K));

            mockMvc.perform(post(
                            "/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}/preview",
                            ORG_ID, CAMPAIGN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.range").value("RANGE_500_1K"))
                    .andExpect(jsonPath("$.data.label").value(EstimatedReachRange.RANGE_500_1K.getLabel()));
        }

        @Test
        @DisplayName("F09.19.7 AC-7.2: 権限のない scope への preview は 403（checkAdminOrAbove 拒否）")
        void 他scope_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            mockMvc.perform(post(
                            "/api/v1/organizations/{organizationId}/advertiser/campaigns/messaging/{id}/preview",
                            ORG_ID, CAMPAIGN_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }
}
