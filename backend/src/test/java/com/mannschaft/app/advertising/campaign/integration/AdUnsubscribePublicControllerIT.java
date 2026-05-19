package com.mannschaft.app.advertising.campaign.integration;

import com.mannschaft.app.advertising.campaign.controller.AdUnsubscribePublicController;
import com.mannschaft.app.advertising.campaign.dto.UnsubscribeResultResponse;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.event.AdOpenPixelTrackingEvent;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.service.AdOpenPixelJwtService;
import com.mannschaft.app.advertising.campaign.service.AdUnsubscribeJwtService;
import com.mannschaft.app.advertising.campaign.service.UserAdPreferenceService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.17 Phase 11-b {@link AdUnsubscribePublicController} の MockMvc 結合テスト。
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} を使い、Spring コンテキストを起動せず
 * Mockito の純粋な mock を直接 inject する。
 * これにより {@code ApplicationEventPublisher} が Spring の {@code ApplicationContext}
 * 自身に解決されてしまい mock 検証が空振る問題を回避する。</p>
 *
 * <p>検証対象:</p>
 * <ul>
 *   <li>{@code GET /api/v1/ads/unsubscribe?token=...} の HTML 応答と Service 呼び出し</li>
 *   <li>期限切れ / version 不一致 / 改竄 各エラーの HttpStatus マッピング</li>
 *   <li>{@code GET /api/v1/ads/pixels/open?token=...} の GIF 応答と Event 発行</li>
 *   <li>JWT 失敗時もピクセルは 200 GIF を返す（メーラー警告回避）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdUnsubscribePublicController 結合テスト (F09.17 Phase 11-b)")
class AdUnsubscribePublicControllerIT {

    @Mock
    private AdUnsubscribeJwtService unsubscribeJwtService;
    @Mock
    private AdOpenPixelJwtService openPixelJwtService;
    @Mock
    private UserAdPreferenceService preferenceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdUnsubscribePublicController controller = new AdUnsubscribePublicController(
                unsubscribeJwtService, openPixelJwtService, preferenceService, eventPublisher);
        // GlobalExceptionHandler を紐付けて BusinessException → HttpStatus マッピングを反映する
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(new StaticMessageSource());
        // @Valid (Jakarta Bean Validation) を standalone setup でも有効化する
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .setValidator(validator)
                .build();
    }

    // ─────────────────────────────────────
    // GET /api/v1/ads/unsubscribe
    // ─────────────────────────────────────

    @Test
    @DisplayName("正常な token で unsubscribe → 200 + HTML + preferenceService 呼び出し")
    void unsubscribeSuccess() throws Exception {
        given(unsubscribeJwtService.verify("good-token"))
                .willReturn(new AdUnsubscribeJwtService.UnsubscribeTokenClaims(42L, 1, "EMAIL"));

        MvcResult result = mockMvc.perform(get("/api/v1/ads/unsubscribe").param("token", "good-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();

        // UTF-8 で本文を読み直し、文字化けなく日本語が含まれること
        String body = new String(
                result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("広告メール配信を停止しました");
        assertThat(body).contains("EMAIL");

        verify(preferenceService).unsubscribe(42L, "EMAIL", 1);
    }

    @Test
    @DisplayName("期限切れ token → 410 GONE (AD_UNSUBSCRIBE_TOKEN_EXPIRED)")
    void unsubscribeExpired() throws Exception {
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_EXPIRED))
                .given(unsubscribeJwtService).verify("expired");

        mockMvc.perform(get("/api/v1/ads/unsubscribe").param("token", "expired"))
                .andExpect(status().isGone());

        verify(preferenceService, never()).unsubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("改竄 token → 400 BAD REQUEST (AD_UNSUBSCRIBE_TOKEN_INVALID)")
    void unsubscribeInvalid() throws Exception {
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID))
                .given(unsubscribeJwtService).verify("bogus");

        mockMvc.perform(get("/api/v1/ads/unsubscribe").param("token", "bogus"))
                .andExpect(status().isBadRequest());

        verify(preferenceService, never()).unsubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("token_version 不一致 → 410 GONE (AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH)")
    void unsubscribeVersionMismatch() throws Exception {
        given(unsubscribeJwtService.verify("old-token"))
                .willReturn(new AdUnsubscribeJwtService.UnsubscribeTokenClaims(42L, 1, "EMAIL"));
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH))
                .given(preferenceService).unsubscribe(eq(42L), eq("EMAIL"), eq(1));

        mockMvc.perform(get("/api/v1/ads/unsubscribe").param("token", "old-token"))
                .andExpect(status().isGone());
    }

    // ─────────────────────────────────────
    // POST /api/v1/ads/unsubscribe (F09.17 残課題 4 SPA 経路)
    // ─────────────────────────────────────

    @Test
    @DisplayName("POST 正常: JWT 有効 + channels=[EMAIL,PUSH] → 200 + result JSON + 2 channel OFF")
    void unsubscribePostSuccess() throws Exception {
        given(unsubscribeJwtService.verify("good-token"))
                .willReturn(new AdUnsubscribeJwtService.UnsubscribeTokenClaims(42L, 1, "EMAIL"));
        given(preferenceService.applyChannelUnsubscribe(
                eq(42L),
                eq(List.of(AdChannelType.EMAIL, AdChannelType.PUSH)),
                eq(1)))
                .willReturn(new UnsubscribeResultResponse(
                        List.of(AdChannelType.EMAIL, AdChannelType.PUSH),
                        List.of(AdChannelType.ANNOUNCEMENT, AdChannelType.BANNER),
                        "advertising.unsubscribe_spa.success_message"));

        String requestBody = """
                {"token":"good-token","channels":["EMAIL","PUSH"]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.disabledChannels[0]").value("EMAIL"))
                .andExpect(jsonPath("$.disabledChannels[1]").value("PUSH"))
                .andExpect(jsonPath("$.remainingActiveChannels[0]").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.remainingActiveChannels[1]").value("BANNER"))
                .andExpect(jsonPath("$.messageKey").value("advertising.unsubscribe_spa.success_message"));

        verify(preferenceService).applyChannelUnsubscribe(
                eq(42L), eq(List.of(AdChannelType.EMAIL, AdChannelType.PUSH)), eq(1));
    }

    @Test
    @DisplayName("POST 期限切れ JWT → 410 GONE")
    void unsubscribePostExpired() throws Exception {
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_EXPIRED))
                .given(unsubscribeJwtService).verify("expired");

        String requestBody = """
                {"token":"expired","channels":["EMAIL"]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isGone());

        verify(preferenceService, never()).applyChannelUnsubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("POST token_version 不一致 → 410 GONE")
    void unsubscribePostVersionMismatch() throws Exception {
        given(unsubscribeJwtService.verify("old-token"))
                .willReturn(new AdUnsubscribeJwtService.UnsubscribeTokenClaims(42L, 1, "EMAIL"));
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH))
                .given(preferenceService)
                .applyChannelUnsubscribe(eq(42L), eq(List.of(AdChannelType.EMAIL)), eq(1));

        String requestBody = """
                {"token":"old-token","channels":["EMAIL"]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("POST 改竄 JWT → 400 BAD REQUEST")
    void unsubscribePostInvalid() throws Exception {
        willThrow(new BusinessException(AdCampaignErrorCode.AD_UNSUBSCRIBE_TOKEN_INVALID))
                .given(unsubscribeJwtService).verify("bogus");

        String requestBody = """
                {"token":"bogus","channels":["EMAIL"]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(preferenceService, never()).applyChannelUnsubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("POST channels 空配列 → 400 (Bean Validation @NotEmpty)")
    void unsubscribePostEmptyChannels() throws Exception {
        String requestBody = """
                {"token":"good-token","channels":[]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(preferenceService, never()).applyChannelUnsubscribe(any(), any(), any());
    }

    @Test
    @DisplayName("POST token 空文字 → 400 (Bean Validation @NotBlank)")
    void unsubscribePostBlankToken() throws Exception {
        String requestBody = """
                {"token":"","channels":["EMAIL"]}
                """;

        mockMvc.perform(post("/api/v1/ads/unsubscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(preferenceService, never()).applyChannelUnsubscribe(any(), any(), any());
    }

    // ─────────────────────────────────────
    // GET /api/v1/ads/pixels/open
    // ─────────────────────────────────────

    @Test
    @DisplayName("正常な token で 200 + 1x1 GIF + AdOpenPixelTrackingEvent 発行")
    void openPixelSuccess() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        given(openPixelJwtService.verify("good-pixel-token"))
                .willReturn(new AdOpenPixelJwtService.OpenPixelClaims(deliveryId, "EMAIL"));

        MvcResult result = mockMvc.perform(
                        get("/api/v1/ads/pixels/open").param("token", "good-pixel-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        // GIF89a シグネチャ
        assertThat(body[0]).isEqualTo((byte) 0x47);
        assertThat(body[1]).isEqualTo((byte) 0x49);
        assertThat(body[2]).isEqualTo((byte) 0x46);
        // 末尾 ; (0x3B)
        assertThat(body[body.length - 1]).isEqualTo((byte) 0x3B);

        ArgumentCaptor<AdOpenPixelTrackingEvent> eventCaptor =
                ArgumentCaptor.forClass(AdOpenPixelTrackingEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AdOpenPixelTrackingEvent ev = eventCaptor.getValue();
        assertThat(ev.deliveryId()).isEqualTo(deliveryId);
        assertThat(ev.channelType()).isEqualTo("EMAIL");
        assertThat(ev.openedAt()).isNotNull();
    }

    @Test
    @DisplayName("JWT 不正でも 200 + 1x1 GIF を返し、Event は発行しない")
    void openPixelInvalidTokenStillReturns200() throws Exception {
        willThrow(new BusinessException(AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID))
                .given(openPixelJwtService).verify("bogus");

        MvcResult result = mockMvc.perform(
                        get("/api/v1/ads/pixels/open").param("token", "bogus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).hasSizeGreaterThan(10);
        verify(eventPublisher, never()).publishEvent(any(AdOpenPixelTrackingEvent.class));
    }

    @Test
    @DisplayName("予期せぬ例外でも 200 + 1x1 GIF（メーラーに警告を出さない）")
    void openPixelUnexpectedExceptionStillReturns200() throws Exception {
        given(openPixelJwtService.verify("crash")).willThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get("/api/v1/ads/pixels/open").param("token", "crash"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_GIF));

        verify(eventPublisher, never()).publishEvent(any(AdOpenPixelTrackingEvent.class));
    }
}
