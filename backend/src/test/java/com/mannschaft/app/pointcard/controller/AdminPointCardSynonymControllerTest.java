package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.pointcard.dto.CreateSynonymRequest;
import com.mannschaft.app.pointcard.dto.SynonymResponse;
import com.mannschaft.app.pointcard.dto.UpdateSynonymRequest;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.service.AdminPointCardSynonymService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link AdminPointCardSynonymController} の MockMvc 結合テスト（F18 Phase 4 第三陣 S3）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>各エンドポイント HTTP ステータス + JSON 形状（GET 200 / POST 201 / DELETE 204）</li>
 *   <li>POINT_CARD_021 (SYNONYM_DUPLICATE) は 409</li>
 *   <li>POINT_CARD_007 (PROVIDER_NOT_FOUND) は 404</li>
 *   <li>COMMON_002 権限不足は 403（SystemAdmin でない）</li>
 *   <li>新規登録のバリデーション（synonymDisplay 空・providerId 欠落）は 400</li>
 * </ul>
 */
@WebMvcTest(AdminPointCardSynonymController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AdminPointCardSynonymController 結合テスト")
class AdminPointCardSynonymControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID SYNONYM_ID =
            UUID.fromString("01956c00-0000-7000-8000-00000000aaaa");
    private static final UUID PROVIDER_ID =
            UUID.fromString("01956c00-0000-7000-8000-00000000bbbb");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminPointCardSynonymService service;

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

    private SynonymResponse sampleResponse() {
        LocalDateTime now = LocalDateTime.now();
        return new SynonymResponse(
                SYNONYM_ID,
                PROVIDER_ID,
                "dポイント",
                "ドコモポイント",
                "とこもほいんと",
                "旧称",
                now,
                now
        );
    }

    // ─────────────────────────────────────────────
    // GET
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /synonyms: 200 で一覧を返す（providerId 無指定）")
    void list_200_all() throws Exception {
        given(service.listAll(eq(USER_ID), eq((UUID) null)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/admin/point-cards/synonyms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(SYNONYM_ID.toString()))
                .andExpect(jsonPath("$.data[0].providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.data[0].synonymDisplay").value("ドコモポイント"));
    }

    @Test
    @DisplayName("GET /synonyms?providerId=...: 絞り込みが Service に伝わる")
    void list_200_filtered() throws Exception {
        given(service.listAll(eq(USER_ID), eq(PROVIDER_ID)))
                .willReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/admin/point-cards/synonyms")
                        .param("providerId", PROVIDER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].providerId").value(PROVIDER_ID.toString()));
    }

    @Test
    @DisplayName("GET /synonyms: SystemAdmin でない → 403 COMMON_002")
    void list_forbidden_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service).listAll(eq(USER_ID), any());

        mockMvc.perform(get("/api/v1/admin/point-cards/synonyms"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ─────────────────────────────────────────────
    // POST
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /synonyms: 201 でレスポンスを返す")
    void create_201() throws Exception {
        given(service.create(eq(USER_ID), any(CreateSynonymRequest.class)))
                .willReturn(sampleResponse());

        CreateSynonymRequest req =
                new CreateSynonymRequest(PROVIDER_ID, "ドコモポイント", "旧称");
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(SYNONYM_ID.toString()))
                .andExpect(jsonPath("$.data.synonymDisplay").value("ドコモポイント"));
    }

    @Test
    @DisplayName("POST /synonyms: 重複 → 409 POINT_CARD_021")
    void create_duplicate_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE))
                .given(service).create(eq(USER_ID), any(CreateSynonymRequest.class));

        CreateSynonymRequest req =
                new CreateSynonymRequest(PROVIDER_ID, "ドコモポイント", null);
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_021"));
    }

    @Test
    @DisplayName("POST /synonyms: provider 不在 → 404 POINT_CARD_007")
    void create_providerNotFound_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND))
                .given(service).create(eq(USER_ID), any(CreateSynonymRequest.class));

        CreateSynonymRequest req =
                new CreateSynonymRequest(PROVIDER_ID, "ドコモポイント", null);
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_007"));
    }

    @Test
    @DisplayName("POST /synonyms: synonymDisplay 空はバリデーション 400")
    void create_blankDisplay_400() throws Exception {
        String body = "{\"providerId\":\"" + PROVIDER_ID + "\",\"synonymDisplay\":\"\"}";
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /synonyms: providerId 欠落はバリデーション 400")
    void create_missingProviderId_400() throws Exception {
        String body = "{\"synonymDisplay\":\"X\"}";
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /synonyms: SystemAdmin でない → 403 COMMON_002")
    void create_forbidden_403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(service).create(eq(USER_ID), any(CreateSynonymRequest.class));

        CreateSynonymRequest req =
                new CreateSynonymRequest(PROVIDER_ID, "ドコモポイント", null);
        mockMvc.perform(post("/api/v1/admin/point-cards/synonyms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ─────────────────────────────────────────────
    // PATCH
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /synonyms/{id}: 200 で更新後を返す")
    void update_200() throws Exception {
        given(service.update(eq(USER_ID), eq(SYNONYM_ID), any(UpdateSynonymRequest.class)))
                .willReturn(sampleResponse());

        UpdateSynonymRequest req = new UpdateSynonymRequest("新しい表示", "メモ");
        mockMvc.perform(patch("/api/v1/admin/point-cards/synonyms/{id}", SYNONYM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(SYNONYM_ID.toString()));
    }

    @Test
    @DisplayName("PATCH /synonyms/{id}: 重複 → 409 POINT_CARD_021")
    void update_duplicate_409() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE))
                .given(service).update(eq(USER_ID), eq(SYNONYM_ID),
                        any(UpdateSynonymRequest.class));

        UpdateSynonymRequest req = new UpdateSynonymRequest("被る", null);
        mockMvc.perform(patch("/api/v1/admin/point-cards/synonyms/{id}", SYNONYM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_021"));
    }

    // ─────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /synonyms/{id}: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/point-cards/synonyms/{id}", SYNONYM_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /synonyms/{id}: 不存在 → 404")
    void delete_notFound_404() throws Exception {
        willThrow(new BusinessException(PointCardErrorCode.CARD_NOT_FOUND))
                .given(service).delete(eq(USER_ID), eq(SYNONYM_ID));

        mockMvc.perform(delete("/api/v1/admin/point-cards/synonyms/{id}", SYNONYM_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POINT_CARD_006"));
    }
}
