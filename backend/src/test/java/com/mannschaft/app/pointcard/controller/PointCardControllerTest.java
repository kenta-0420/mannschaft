package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.CreateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.ShareTokenResponse;
import com.mannschaft.app.pointcard.dto.UpdateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UserPointCardDetailResponse;
import com.mannschaft.app.pointcard.dto.UserPointCardListItemResponse;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.PointCardService;
import com.mannschaft.app.pointcard.service.PointCardShareTokenService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PointCardController} の MockMvc 結合テスト（F18 第二陣 2B）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>各エンドポイントの HTTP ステータス + JSON 形状</li>
 *   <li>POST 作成は 201、DELETE / POST /used は 204</li>
 *   <li>IDOR — 他人のカードへの GET/PATCH/DELETE は 404</li>
 *   <li>WALLET_NOT_ENABLED は 403</li>
 *   <li>CARD_LIMIT_EXCEEDED は 409（設計書 §6.3 整合）</li>
 * </ul>
 */
@WebMvcTest(PointCardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PointCardController 結合テスト")
class PointCardControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID CARD_ID = UUID.fromString("01956c00-0000-7000-8000-000000000aaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PointCardService pointCardService;

    @MockitoBean
    private PointCardShareTokenService shareTokenService;

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
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private UserPointCardDetailResponse sampleDetail() {
        return new UserPointCardDetailResponse(
                CARD_ID, null, null, null, null, null, false,
                "東急ポイント", null, "1234567890123", BarcodeFormat.CODE128,
                "0123", null, false, 0, null,
                OffsetDateTime.parse("2026-05-14T10:00:00Z"),
                OffsetDateTime.parse("2026-05-14T10:00:00Z"),
                null, null, null, null);
    }

    private UserPointCardListItemResponse sampleListItem() {
        return new UserPointCardListItemResponse(
                CARD_ID, null, null, null, null, null,
                "東急ポイント", "0123", BarcodeFormat.CODE128,
                false, 0, null,
                OffsetDateTime.parse("2026-05-14T10:00:00Z"),
                null, null, null, null);
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/point-cards
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/point-cards: 一覧で 200 + barcode_value は返さない")
    void list_200() throws Exception {
        given(pointCardService.listMyCards(eq(USER_ID)))
                .willReturn(List.of(sampleListItem()));

        mockMvc.perform(get("/api/v1/point-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.data[0].displayName").value("東急ポイント"))
                .andExpect(jsonPath("$.data[0].last4").value("0123"))
                .andExpect(jsonPath("$.data[0].barcodeValue").doesNotExist());
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/point-cards
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/point-cards: 作成成功で 201 + 復号値を含む詳細 JSON")
    void create_201() throws Exception {
        given(pointCardService.createCard(eq(USER_ID), any(CreateUserPointCardRequest.class)))
                .willReturn(sampleDetail());

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "東急ポイント", "1234567890123", BarcodeFormat.CODE128, null, null, null);

        mockMvc.perform(post("/api/v1/point-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(CARD_ID.toString()))
                .andExpect(jsonPath("$.data.barcodeValue").value("1234567890123"))
                .andExpect(jsonPath("$.data.last4").value("0123"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards: WALLET_NOT_ENABLED は 403")
    void create_walletNotEnabled_403() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED))
                .given(pointCardService).createCard(eq(USER_ID), any(CreateUserPointCardRequest.class));

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "X", "12345", BarcodeFormat.CODE128, null, null, null);

        mockMvc.perform(post("/api/v1/point-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_001"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards: CARD_LIMIT_EXCEEDED は 409 POINT_CARD_003（設計書 §6.3 整合）")
    void create_cardLimitExceeded_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_LIMIT_EXCEEDED))
                .given(pointCardService).createCard(eq(USER_ID), any(CreateUserPointCardRequest.class));

        CreateUserPointCardRequest req = new CreateUserPointCardRequest(
                "X", "12345", BarcodeFormat.CODE128, null, null, null);

        mockMvc.perform(post("/api/v1/point-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_003"));
    }

    @Test
    @DisplayName("POST /api/v1/point-cards: 必須欠落（displayName 空）は 400 バリデーションエラー")
    void create_validation_400() throws Exception {
        String body = "{\"displayName\":\"\",\"barcodeValue\":\"1234\",\"barcodeFormat\":\"CODE128\"}";

        mockMvc.perform(post("/api/v1/point-cards")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────
    // GET /api/v1/point-cards/{id}
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/point-cards/{id}: 200 + barcodeValue 復号値含む")
    void getDetail_200() throws Exception {
        given(pointCardService.getCard(eq(CARD_ID), eq(USER_ID))).willReturn(sampleDetail());

        mockMvc.perform(get("/api/v1/point-cards/{id}", CARD_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.barcodeValue").value("1234567890123"));
    }

    @Test
    @DisplayName("GET /api/v1/point-cards/{id}: 他人カード → 404 POINT_CARD_006（IDOR 防止 — S3 整合）")
    void getDetail_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(pointCardService).getCard(eq(CARD_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/point-cards/{id}", CARD_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }

    // ──────────────────────────────────────────────
    // PATCH /api/v1/point-cards/{id}
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /api/v1/point-cards/{id}: 200 で更新後の DTO を返す")
    void patch_200() throws Exception {
        given(pointCardService.updateCard(eq(CARD_ID), eq(USER_ID),
                any(UpdateUserPointCardRequest.class))).willReturn(sampleDetail());

        UpdateUserPointCardRequest req =
                new UpdateUserPointCardRequest(null, "新名", null, Boolean.TRUE, 1);

        mockMvc.perform(patch("/api/v1/point-cards/{id}", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(CARD_ID.toString()));
    }

    @Test
    @DisplayName("PATCH /api/v1/point-cards/{id}: 他人カード → 404 POINT_CARD_006")
    void patch_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(pointCardService).updateCard(eq(CARD_ID), eq(USER_ID),
                        any(UpdateUserPointCardRequest.class));

        UpdateUserPointCardRequest req =
                new UpdateUserPointCardRequest(null, "x", null, null, null);

        mockMvc.perform(patch("/api/v1/point-cards/{id}", CARD_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }

    // ──────────────────────────────────────────────
    // DELETE /api/v1/point-cards/{id}
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/point-cards/{id}: 削除成功で 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/point-cards/{id}", CARD_ID))
                .andExpect(status().isNoContent());
        verify(pointCardService).deleteCard(eq(CARD_ID), eq(USER_ID));
    }

    @Test
    @DisplayName("DELETE /api/v1/point-cards/{id}: 他人カード → 404 POINT_CARD_006")
    void delete_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(pointCardService).deleteCard(eq(CARD_ID), eq(USER_ID));

        mockMvc.perform(delete("/api/v1/point-cards/{id}", CARD_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/point-cards/{id}/used
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/point-cards/{id}/used: 204")
    void recordUsed_204() throws Exception {
        mockMvc.perform(post("/api/v1/point-cards/{id}/used", CARD_ID))
                .andExpect(status().isNoContent());
        verify(pointCardService).recordUsed(eq(CARD_ID), eq(USER_ID));
    }

    // ──────────────────────────────────────────────
    // POST /api/v1/point-cards/{cardId}/share-tokens（Phase 3 第二陣 2A）
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/point-cards/{cardId}/share-tokens: 201 + token + deepLinkUrl")
    void createShareToken_201() throws Exception {
        ShareTokenResponse stub = new ShareTokenResponse(
                "01234567-89ab-4cde-8fed-cba987654321",
                OffsetDateTime.parse("2026-05-14T10:05:00Z"),
                "mannschaft://wallet/share?token=01234567-89ab-4cde-8fed-cba987654321");
        given(shareTokenService.generate(eq(USER_ID), eq(CARD_ID))).willReturn(stub);

        mockMvc.perform(post("/api/v1/point-cards/{cardId}/share-tokens", CARD_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").value("01234567-89ab-4cde-8fed-cba987654321"))
                .andExpect(jsonPath("$.data.deepLinkUrl")
                        .value("mannschaft://wallet/share?token=01234567-89ab-4cde-8fed-cba987654321"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    @DisplayName("POST /api/v1/point-cards/{cardId}/share-tokens: 他人カード → 404 POINT_CARD_006")
    void createShareToken_otherUser_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(shareTokenService).generate(eq(USER_ID), eq(CARD_ID));

        mockMvc.perform(post("/api/v1/point-cards/{cardId}/share-tokens", CARD_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }
}
