package com.mannschaft.app.advertising.campaign.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.controller.AdvertiserMessagingCampaignController;
import com.mannschaft.app.advertising.campaign.dto.AudienceConfigRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentRequest;
import com.mannschaft.app.advertising.campaign.dto.AudienceSegmentResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelRequest;
import com.mannschaft.app.advertising.campaign.dto.CampaignChannelResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignDetailResponse;
import com.mannschaft.app.advertising.campaign.dto.CampaignListItemResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.UpdateCampaignRequest;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.17 Phase 11-a {@link AdvertiserMessagingCampaignController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service / AccessControlService は
 * {@link MockitoBean} で差し替える。HTTP <-> Service の薄いマッピング層
 * （パスバリデーション、組織越境制御、エラー → HttpStatus マッピング）の挙動を検証する。</p>
 *
 * <p>カバー範囲（設計書 §4 Campaign 域）:</p>
 * <ul>
 *   <li>ハッピーパス: create / addChannel / setAudience / get / list / softDelete</li>
 *   <li>権限拒否（ADMIN 未満）: COMMON_002 → 403</li>
 *   <li>DRAFT 以外編集拒否: AD_CAMPAIGN_NOT_EDITABLE → 409</li>
 *   <li>テナント越境 (IDOR): AD_CAMPAIGN_FORBIDDEN_TENANT → 404</li>
 *   <li>チャネル必須/重複: AD_CHANNEL_REQUIRED → 400, AD_CHANNEL_DUPLICATE → 409</li>
 *   <li>ターゲティング不正: AD_AUDIENCE_INVALID → 400</li>
 * </ul>
 */
@WebMvcTest(AdvertiserMessagingCampaignController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdvertiserMessagingCampaignController 結合テスト (F09.17 Phase 11-a)")
class AdvertiserMessagingCampaignControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 200L;
    private static final Long OTHER_ORG_ID = 999L;
    private static final Long ADVERTISER_ACCOUNT_ID = 50L;
    private static final UUID CAMPAIGN_ID = UUID.fromString("01911111-1111-7111-8111-111111111111");
    private static final UUID CHANNEL_ID = UUID.fromString("01922222-2222-7222-8222-222222222222");

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

    private CampaignDetailResponse stubDetail() {
        return new CampaignDetailResponse(
                CAMPAIGN_ID,
                ADVERTISER_ACCOUNT_ID,
                "テストキャンペーン",
                AdCampaignStatus.DRAFT,
                AdModerationStatus.PENDING,
                null,
                100_000L,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now().plusDays(7),
                "Asia/Tokyo",
                3,
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of(),
                List.of()
        );
    }

    private CampaignListItemResponse stubListItem() {
        return new CampaignListItemResponse(
                CAMPAIGN_ID,
                "テストキャンペーン",
                AdCampaignStatus.DRAFT,
                AdModerationStatus.PENDING,
                100_000L,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now().plusDays(7),
                "Asia/Tokyo",
                3,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private CampaignChannelResponse stubChannel() {
        return new CampaignChannelResponse(
                CHANNEL_ID,
                CAMPAIGN_ID,
                AdChannelType.EMAIL,
                "ja",
                "件名",
                "本文",
                null,
                "詳しく見る",
                "https://example.com",
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private CreateCampaignRequest validCreateRequest() {
        return new CreateCampaignRequest(
                "テストキャンペーン",
                100_000L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(7),
                "Asia/Tokyo",
                3);
    }

    private UpdateCampaignRequest validUpdateRequest() {
        return new UpdateCampaignRequest(
                "更新後キャンペーン",
                200_000L,
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(14),
                "Asia/Tokyo",
                5);
    }

    private CampaignChannelRequest validChannelRequest() {
        return new CampaignChannelRequest(
                AdChannelType.EMAIL,
                "ja",
                "件名",
                "本文",
                null,
                "詳しく見る",
                "https://example.com",
                null);
    }

    private AdvertiserAccountResponse stubAdvertiserAccount() {
        return new AdvertiserAccountResponse(
                ADVERTISER_ACCOUNT_ID,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION,
                ORG_ID,
                null,
                "テスト広告主",
                "ad-campaign@example.com",
                null,
                null,
                null,
                LocalDateTime.now());
    }

    // ════════════════════════════════════════════════
    // POST / : create
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/advertiser/campaigns/messaging")
    class CreateCampaign {

        @Test
        @DisplayName("ハッピーパス: ADMIN が DRAFT で作成 → 201")
        void 正常系_201() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(advertiserAccountService.getByScope(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(stubAdvertiserAccount());
            given(campaignService.createCampaign(eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                    eq(ADVERTISER_ACCOUNT_ID), eq(USER_ID), any(CreateCampaignRequest.class)))
                    .willReturn(stubDetail());

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging")
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(CAMPAIGN_ID.toString()))
                    .andExpect(jsonPath("$.data.status").value("DRAFT"));

            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("権限拒否: MANAGE_ADS なし ADMIN → COMMON_002 → 403")
        void 権限拒否_403() throws Exception {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging")
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("Bean Validation: name 空 → 400")
        void name未指定_400() throws Exception {
            CreateCampaignRequest invalid = new CreateCampaignRequest(
                    "",
                    100_000L,
                    LocalDateTime.now().plusDays(1),
                    LocalDateTime.now().plusDays(7),
                    "Asia/Tokyo",
                    3);

            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging")
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // GET / : list, GET /{id} : get
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/advertiser/campaigns/messaging")
    class ListCampaigns {

        @Test
        @DisplayName("ハッピーパス: 1 件返す → 200")
        void 正常系_200() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            Page<CampaignListItemResponse> page =
                    new PageImpl<>(List.of(stubListItem()), Pageable.unpaged(), 1);
            given(campaignService.listCampaigns(eq(ScopeType.ORGANIZATION), eq(ORG_ID), any(), any()))
                    .willReturn(page);

            mockMvc.perform(get("/api/v1/advertiser/campaigns/messaging")
                            .param("organizationId", ORG_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(CAMPAIGN_ID.toString()))
                    .andExpect(jsonPath("$.meta.total").value(1));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/advertiser/campaigns/messaging/{id}")
    class GetCampaign {

        @Test
        @DisplayName("ハッピーパス: 詳細取得 → 200")
        void 正常系_200() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(campaignService.getCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(stubDetail());

            mockMvc.perform(get("/api/v1/advertiser/campaigns/messaging/{id}", CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(CAMPAIGN_ID.toString()));
        }

        @Test
        @DisplayName("テナント越境 (IDOR): AD_CAMPAIGN_FORBIDDEN_TENANT → 404")
        void テナント越境_404() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, OTHER_ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_FORBIDDEN_TENANT))
                    .given(campaignService).getCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, OTHER_ORG_ID);

            mockMvc.perform(get("/api/v1/advertiser/campaigns/messaging/{id}", CAMPAIGN_ID)
                            .param("organizationId", OTHER_ORG_ID.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_FORBIDDEN_TENANT"));
        }

        @Test
        @DisplayName("存在しないキャンペーン: AD_CAMPAIGN_NOT_FOUND → 404")
        void キャンペーン不在_404() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND))
                    .given(campaignService).getCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID);

            mockMvc.perform(get("/api/v1/advertiser/campaigns/messaging/{id}", CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_FOUND"));
        }
    }

    // ════════════════════════════════════════════════
    // PUT /{id} : update
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("PUT /api/v1/advertiser/campaigns/messaging/{id}")
    class UpdateCampaign {

        @Test
        @DisplayName("DRAFT 以外編集拒否: AD_CAMPAIGN_NOT_EDITABLE → 409")
        void DRAFT以外編集_409() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_EDITABLE))
                    .given(campaignService)
                    .updateCampaign(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                            any(UpdateCampaignRequest.class));

            mockMvc.perform(put("/api/v1/advertiser/campaigns/messaging/{id}", CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validUpdateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_EDITABLE"));
        }
    }

    // ════════════════════════════════════════════════
    // DELETE /{id} : softDelete
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/v1/advertiser/campaigns/messaging/{id}")
    class DeleteCampaign {

        @Test
        @DisplayName("ハッピーパス: DRAFT 論理削除 → 204")
        void 正常系_204() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willDoNothing().given(campaignService)
                    .softDeleteCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID);

            mockMvc.perform(delete("/api/v1/advertiser/campaigns/messaging/{id}", CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString()))
                    .andExpect(status().isNoContent());

            verify(campaignService).softDeleteCampaign(CAMPAIGN_ID, ScopeType.ORGANIZATION, ORG_ID);
        }
    }

    // ════════════════════════════════════════════════
    // POST /{id}/channels : addChannel
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/advertiser/campaigns/messaging/{id}/channels")
    class AddChannel {

        @Test
        @DisplayName("ハッピーパス: チャネル追加 → 201")
        void 正常系_201() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            given(campaignService.addChannel(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                    any(CampaignChannelRequest.class)))
                    .willReturn(stubChannel());

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging/{id}/channels",
                            CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validChannelRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(CHANNEL_ID.toString()))
                    .andExpect(jsonPath("$.data.channelType").value("EMAIL"));
        }

        @Test
        @DisplayName("重複: AD_CHANNEL_DUPLICATE → 409")
        void 重複_409() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CHANNEL_DUPLICATE))
                    .given(campaignService)
                    .addChannel(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                            any(CampaignChannelRequest.class));

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging/{id}/channels",
                            CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validChannelRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AD_CHANNEL_DUPLICATE"));
        }

        @Test
        @DisplayName("DRAFT 以外編集拒否: AD_CAMPAIGN_NOT_EDITABLE → 409")
        void DRAFT以外編集_409() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_EDITABLE))
                    .given(campaignService)
                    .addChannel(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                            any(CampaignChannelRequest.class));

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging/{id}/channels",
                            CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validChannelRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_EDITABLE"));
        }
    }

    // ════════════════════════════════════════════════
    // POST /{id}/audience : setAudience
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/advertiser/campaigns/messaging/{id}/audience")
    class SetAudience {

        @Test
        @DisplayName("ハッピーパス: ターゲティング設定 → 200")
        void 正常系_200() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            AudienceSegmentResponse seg = new AudienceSegmentResponse(
                    UUID.randomUUID(),
                    CAMPAIGN_ID,
                    AdSegmentType.AGE_RANGE,
                    Map.of("min", 20, "max", 40),
                    AdSegmentInclusionMode.INCLUDE,
                    LocalDateTime.now());
            given(campaignService.setAudience(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                    any(AudienceConfigRequest.class)))
                    .willReturn(List.of(seg));

            AudienceConfigRequest req = new AudienceConfigRequest(List.of(
                    new AudienceSegmentRequest(
                            AdSegmentType.AGE_RANGE,
                            Map.of("min", 20, "max", 40),
                            AdSegmentInclusionMode.INCLUDE)));

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging/{id}/audience",
                            CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].segmentType").value("AGE_RANGE"));
        }

        @Test
        @DisplayName("不正条件: AD_AUDIENCE_INVALID → 400")
        void 不正条件_400() throws Exception {
            willDoNothing().given(accessControlService)
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_AUDIENCE_INVALID))
                    .given(campaignService)
                    .setAudience(eq(CAMPAIGN_ID), eq(ScopeType.ORGANIZATION), eq(ORG_ID),
                            any(AudienceConfigRequest.class));

            AudienceConfigRequest req = new AudienceConfigRequest(List.of(
                    new AudienceSegmentRequest(
                            AdSegmentType.AGE_RANGE,
                            Map.of("min", 200),
                            AdSegmentInclusionMode.INCLUDE)));

            mockMvc.perform(post("/api/v1/advertiser/campaigns/messaging/{id}/audience",
                            CAMPAIGN_ID)
                            .param("organizationId", ORG_ID.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AD_AUDIENCE_INVALID"));
        }
    }
}
