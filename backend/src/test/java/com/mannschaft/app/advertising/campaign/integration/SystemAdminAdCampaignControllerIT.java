package com.mannschaft.app.advertising.campaign.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.controller.SystemAdminAdCampaignController;
import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.ReviewQueueItemResponse;
import com.mannschaft.app.advertising.campaign.dto.UnblockCampaignRequest;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.service.AdCampaignModerationService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F09.17 Phase 11-a {@link SystemAdminAdCampaignController} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service は {@link MockitoBean} で差し替える。
 * SYSTEM_ADMIN ロール限定の制約はクラスレベル {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} を
 * リフレクションで検証する（既存 {@code AdminActionMemoControllerTest} と同パターン）。
 * HTTP レイヤでは {@code addFilters = false} としているため、ハッピーパスを Service 呼び出しまで通す。</p>
 *
 * <p>カバー範囲:</p>
 * <ul>
 *   <li>クラスレベル {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} 注釈の存在検証</li>
 *   <li>GET /review-queue ハッピーパス + ページング</li>
 *   <li>POST /{id}/approve ハッピーパス → 204 + Service に moderatorUserId 伝搬</li>
 *   <li>POST /{id}/block ハッピーパス → 204 + ModerationLog 行生成は Service 責務</li>
 *   <li>POST /{id}/approve 審査対象外: AD_CAMPAIGN_NOT_REVIEWABLE → 400</li>
 *   <li>POST /{id}/block 重複: AD_CAMPAIGN_ALREADY_BLOCKED → 409</li>
 *   <li>POST /{id}/block reason 空 → Bean Validation 400</li>
 * </ul>
 */
@WebMvcTest(SystemAdminAdCampaignController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SystemAdminAdCampaignController 結合テスト (F09.17 Phase 11-a)")
class SystemAdminAdCampaignControllerIT {

    private static final Long MODERATOR_USER_ID = 999L;
    private static final UUID CAMPAIGN_ID = UUID.fromString("01944444-4444-7444-8444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdCampaignModerationService moderationService;

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
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                MODERATOR_USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════
    // クラスレベル @PreAuthorize 注釈の存在検証
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("@PreAuthorize('hasRole(SYSTEM_ADMIN)') がクラスレベルで付与されている")
    class AuthorizationAnnotation {

        @Test
        @DisplayName("ADMIN は叩けず SYSTEM_ADMIN のみアクセス可（注釈で保証）")
        void クラスレベルPreAuthorizeがSYSTEM_ADMINに制限する() {
            PreAuthorize annotation = SystemAdminAdCampaignController.class
                    .getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("@PreAuthorize 未付与だと全ユーザーが審査 API を叩けてしまう")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("SYSTEM_ADMIN 以外を拒否する式でなければならない")
                    .isEqualTo("hasRole('SYSTEM_ADMIN')");
        }
    }

    // ════════════════════════════════════════════════
    // GET /review-queue
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/system-admin/ad-campaigns/review-queue")
    class GetReviewQueue {

        @Test
        @DisplayName("ハッピーパス: PENDING/AUTO_FLAGGED 一覧を返す → 200")
        void 正常系_200() throws Exception {
            ReviewQueueItemResponse item = ReviewQueueItemResponse.builder()
                    .campaignId(CAMPAIGN_ID)
                    .advertiserAccountId(50L)
                    .name("審査待ちキャンペーン")
                    .status(AdCampaignStatus.DRAFT)
                    .moderationStatus(AdModerationStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();
            Page<ReviewQueueItemResponse> page =
                    new PageImpl<>(List.of(item), Pageable.ofSize(20), 1);
            given(moderationService.getReviewQueue(0, 20)).willReturn(page);

            mockMvc.perform(get("/api/v1/system-admin/ad-campaigns/review-queue")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].campaignId").value(CAMPAIGN_ID.toString()))
                    .andExpect(jsonPath("$.data[0].moderationStatus").value("PENDING"))
                    .andExpect(jsonPath("$.meta.total").value(1));
        }

        @Test
        @DisplayName("ページングパラメータのデフォルト: page=0, size=20")
        void デフォルトページング() throws Exception {
            Page<ReviewQueueItemResponse> empty =
                    new PageImpl<>(List.of(), Pageable.ofSize(20), 0);
            given(moderationService.getReviewQueue(0, 20)).willReturn(empty);

            mockMvc.perform(get("/api/v1/system-admin/ad-campaigns/review-queue"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.total").value(0));

            verify(moderationService).getReviewQueue(0, 20);
        }
    }

    // ════════════════════════════════════════════════
    // POST /{id}/approve
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/system-admin/ad-campaigns/{id}/approve")
    class ApproveCampaign {

        @Test
        @DisplayName("ハッピーパス: 承認 → 204, Service に moderatorUserId 伝搬")
        void 正常系_204() throws Exception {
            willDoNothing().given(moderationService).approve(CAMPAIGN_ID, MODERATOR_USER_ID);

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/approve", CAMPAIGN_ID))
                    .andExpect(status().isNoContent());

            verify(moderationService).approve(CAMPAIGN_ID, MODERATOR_USER_ID);
        }

        @Test
        @DisplayName("審査対象外: AD_CAMPAIGN_NOT_REVIEWABLE → 400")
        void 審査対象外_400() throws Exception {
            willThrow(new BusinessException(AdCampaignErrorCode.NOT_REVIEWABLE))
                    .given(moderationService).approve(CAMPAIGN_ID, MODERATOR_USER_ID);

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/approve", CAMPAIGN_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_REVIEWABLE"));
        }

        @Test
        @DisplayName("不在: AD_CAMPAIGN_NOT_FOUND → 404")
        void 不在_404() throws Exception {
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND))
                    .given(moderationService).approve(CAMPAIGN_ID, MODERATOR_USER_ID);

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/approve", CAMPAIGN_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_FOUND"));
        }
    }

    // ════════════════════════════════════════════════
    // POST /{id}/block
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/system-admin/ad-campaigns/{id}/block")
    class BlockCampaign {

        @Test
        @DisplayName("ハッピーパス: ブロック → 204")
        void 正常系_204() throws Exception {
            BlockCampaignRequest req = new BlockCampaignRequest("ガイドライン違反");
            willDoNothing().given(moderationService)
                    .block(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(BlockCampaignRequest.class));

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/block", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNoContent());

            verify(moderationService)
                    .block(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(BlockCampaignRequest.class));
        }

        @Test
        @DisplayName("重複ブロック: AD_CAMPAIGN_ALREADY_BLOCKED → 409")
        void 重複ブロック_409() throws Exception {
            BlockCampaignRequest req = new BlockCampaignRequest("ガイドライン違反");
            willThrow(new BusinessException(AdCampaignErrorCode.ALREADY_BLOCKED))
                    .given(moderationService)
                    .block(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(BlockCampaignRequest.class));

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/block", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_ALREADY_BLOCKED"));
        }

        @Test
        @DisplayName("reason 空 → Bean Validation 400")
        void reason空_400() throws Exception {
            BlockCampaignRequest invalid = new BlockCampaignRequest("");

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/block", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════
    // POST /{id}/unblock — F09.17 残課題 3
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/system-admin/ad-campaigns/{id}/unblock")
    class UnblockCampaign {

        @Test
        @DisplayName("ハッピーパス: UNBLOCK → 204 + Service に moderatorUserId 伝搬")
        void 正常系_204() throws Exception {
            UnblockCampaignRequest req = new UnblockCampaignRequest("誤判定のため取消");
            willDoNothing().given(moderationService)
                    .unblock(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(UnblockCampaignRequest.class));

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/unblock", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNoContent());

            verify(moderationService)
                    .unblock(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(UnblockCampaignRequest.class));
        }

        @Test
        @DisplayName("BLOCKED 以外 → AD_CAMPAIGN_NOT_UNBLOCKABLE 400")
        void UNBLOCK不可状態_400() throws Exception {
            UnblockCampaignRequest req = new UnblockCampaignRequest("試行");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_UNBLOCKABLE))
                    .given(moderationService)
                    .unblock(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(UnblockCampaignRequest.class));

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/unblock", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_UNBLOCKABLE"));
        }

        @Test
        @DisplayName("不在: AD_CAMPAIGN_NOT_FOUND → 404")
        void 不在_404() throws Exception {
            UnblockCampaignRequest req = new UnblockCampaignRequest("試行");
            willThrow(new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND))
                    .given(moderationService)
                    .unblock(eq(CAMPAIGN_ID), eq(MODERATOR_USER_ID), any(UnblockCampaignRequest.class));

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/unblock", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("AD_CAMPAIGN_NOT_FOUND"));
        }

        @Test
        @DisplayName("reason 空 → Bean Validation 400")
        void reason空_400() throws Exception {
            UnblockCampaignRequest invalid = new UnblockCampaignRequest("");

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/unblock", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("reason 500 文字超過 → Bean Validation 400")
        void reason超過_400() throws Exception {
            String tooLong = "a".repeat(501);
            UnblockCampaignRequest invalid = new UnblockCampaignRequest(tooLong);

            mockMvc.perform(post("/api/v1/system-admin/ad-campaigns/{id}/unblock", CAMPAIGN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }
    }
}
