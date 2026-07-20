package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.service.VillageService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17 Phase 2 U7 — VillageMonshoController MockMvc 契約テスト。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>characterization test（現契約の固定）</strong>であり、red → green の red テストではない。
 * BE は既に正しく、初回実行から green になるのが正常である。</p>
 *
 * <p>既存の {@link VillageMonshoControllerIntegrationTest} は Controller Bean を {@code @Autowired}
 * して直接呼ぶ流儀であり、HTTP メソッド（PUT か POST か）を構造的に検証できない。村紋は
 * 「FE が POST を送っていたが BE は PUT のみ」という不一致が起きていた典型例であり、本テストは
 * MockMvc でその契約を固定する。既存 IntegrationTest は挙動の回帰検知として残置。</p>
 *
 * <p>規約: 新規 Controller テストは MockMvc 経由必須（{@code TEST_CONVENTION.md} §Controller テスト）。</p>
 */
@WebMvcTest(VillageMonshoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17 VillageMonshoController MockMvc 契約テスト（現契約の固定）")
class VillageMonshoControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageService villageService;

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
    private static final Long USER_ID = 100L;
    private static final String R2_KEY = "village/monsho/sample.png";

    private static final String BASE = "/api/v1/villages/{villageId}/monsho";

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    private VillageEntity villageWithMonsho(String r2Key) {
        VillageEntity entity = new VillageEntity();
        entity.setId(VILLAGE_ID);
        entity.setMonshoR2Key(r2Key);
        return entity;
    }

    // ==================================================================
    // HTTP メソッド契約
    // ==================================================================

    @Nested
    @DisplayName("HTTP メソッド契約（PUT が正・POST は存在しない）")
    class HttpMethodContract {

        @Test
        @DisplayName("PUT /monsho — JSON {r2Key} を受け 200 + VillageMonshoResponse を返す")
        void put_isCanonical_returns200() throws Exception {
            given(villageService.updateMonsho(VILLAGE_ID, R2_KEY, USER_ID))
                    .willReturn(villageWithMonsho(R2_KEY));

            String body = """
                    { "r2Key": "village/monsho/sample.png" }
                    """;

            mockMvc.perform(put(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    // VillageMonshoResponse = {villageId, monshoR2Key}
                    .andExpect(jsonPath("$.data.villageId").value(VILLAGE_ID.toString()))
                    .andExpect(jsonPath("$.data.monshoR2Key").value(R2_KEY))
                    // リクエストの項目名 r2Key はレスポンスには現れない
                    .andExpect(jsonPath("$.data.r2Key").doesNotExist())
                    .andExpect(jsonPath("$.data.url").doesNotExist());

            verify(villageService).updateMonsho(VILLAGE_ID, R2_KEY, USER_ID);
        }

        @Test
        @DisplayName("POST /monsho は存在しない（405）— FE が誤って叩いていた経路を固定する")
        void post_doesNotExist_returns405() throws Exception {
            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "r2Key": "village/monsho/sample.png" }
                                    """))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.error.code").value("COMMON_004"));

            verify(villageService, never()).updateMonsho(any(), any(), any());
        }

        @Test
        @DisplayName("DELETE /monsho — 200 + monshoR2Key=null を返す")
        void delete_returns200WithNullKey() throws Exception {
            given(villageService.deleteMonsho(VILLAGE_ID, USER_ID))
                    .willReturn(villageWithMonsho(null));

            mockMvc.perform(delete(BASE, VILLAGE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.villageId").value(VILLAGE_ID.toString()))
                    .andExpect(jsonPath("$.data.monshoR2Key").value(org.hamcrest.Matchers.nullValue()));

            verify(villageService).deleteMonsho(VILLAGE_ID, USER_ID);
        }
    }

    // ==================================================================
    // リクエストボディの検証契約
    // ==================================================================

    @Nested
    @DisplayName("r2Key の検証契約")
    class R2KeyValidationContract {

        @Test
        @DisplayName("PUT — r2Key 欠落は 400（@NotBlank）")
        void put_missingR2Key_returns400() throws Exception {
            mockMvc.perform(put(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            verify(villageService, never()).updateMonsho(any(), any(), any());
        }

        @Test
        @DisplayName("PUT — r2Key 空文字は 400（@NotBlank）")
        void put_blankR2Key_returns400() throws Exception {
            mockMvc.perform(put(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "r2Key": "" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(villageService, never()).updateMonsho(any(), any(), any());
        }

        @Test
        @DisplayName("PUT — 別名 monshoR2Key で送っても r2Key が欠けるので 400。FE の誤形状を固定")
        void put_wrongFieldName_returns400() throws Exception {
            mockMvc.perform(put(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "monshoR2Key": "village/monsho/sample.png" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(villageService, never()).updateMonsho(any(), any(), any());
        }
    }

    // ==================================================================
    // #2355 村紋 presign 発行 EP — POST /monsho/upload-url の契約
    // ==================================================================

    @Nested
    @DisplayName("POST /monsho/upload-url — presign 発行 EP の契約（#2355）")
    class UploadUrlContract {

        private static final String UPLOAD_URL_PATH = "/api/v1/villages/{villageId}/monsho/upload-url";
        private static final String VALID_BODY = """
                { "contentType": "image/png", "fileSize": 12345 }
                """;

        @Test
        @DisplayName("AC-1: 正常系は 200 で uploadUrl / r2Key / expiresInSeconds(=600) を返す（r2Key は村スコープ接頭辞）")
        void uploadUrl_success_returns200WithPresignedFields() throws Exception {
            String expectedKey = "village/" + VILLAGE_ID + "/monsho/abcdef.png";
            given(villageService.generateMonshoUploadUrl(
                    eq(VILLAGE_ID), eq("image/png"), eq(12345L), eq(USER_ID)))
                    .willReturn(new com.mannschaft.app.village.dto.MonshoUploadUrlResponse(
                            "https://r2.example.com/put?sig=xyz", expectedKey, 600L));

            mockMvc.perform(post(UPLOAD_URL_PATH, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadUrl").value("https://r2.example.com/put?sig=xyz"))
                    .andExpect(jsonPath("$.data.r2Key").value(expectedKey))
                    .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                    // 生キーは村スコープ接頭辞で始まる（読取用の署名 URL 化はしない）
                    .andExpect(jsonPath("$.data.r2Key").value(org.hamcrest.Matchers.startsWith(
                            "village/" + VILLAGE_ID + "/monsho/")));

            verify(villageService).generateMonshoUploadUrl(VILLAGE_ID, "image/png", 12345L, USER_ID);
        }

        @Test
        @DisplayName("AC-2: 権限不足は 403 VILLAGE_024（MODERATION_FORBIDDEN が透過する）")
        void uploadUrl_forbidden_returns403() throws Exception {
            org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                            com.mannschaft.app.village.VillageErrorCode.MODERATION_FORBIDDEN))
                    .given(villageService).generateMonshoUploadUrl(
                            eq(VILLAGE_ID), any(), org.mockito.ArgumentMatchers.anyLong(), eq(USER_ID));

            mockMvc.perform(post(UPLOAD_URL_PATH, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
        }

        @Test
        @DisplayName("AC-3: 村が存在しないと 404 VILLAGE_001（VILLAGE_NOT_FOUND が透過する）")
        void uploadUrl_villageNotFound_returns404() throws Exception {
            org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                            com.mannschaft.app.village.VillageErrorCode.VILLAGE_NOT_FOUND))
                    .given(villageService).generateMonshoUploadUrl(
                            eq(VILLAGE_ID), any(), org.mockito.ArgumentMatchers.anyLong(), eq(USER_ID));

            mockMvc.perform(post(UPLOAD_URL_PATH, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));
        }

        @Test
        @DisplayName("AC-5(DTO): contentType 欠落は 400（@NotBlank）— Service には到達しない")
        void uploadUrl_missingContentType_returns400() throws Exception {
            mockMvc.perform(post(UPLOAD_URL_PATH, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "fileSize": 12345 }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(villageService, never()).generateMonshoUploadUrl(
                    any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        }

        @Test
        @DisplayName("AC-5(DTO): contentType 空文字は 400（@NotBlank）— Service には到達しない")
        void uploadUrl_blankContentType_returns400() throws Exception {
            mockMvc.perform(post(UPLOAD_URL_PATH, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "contentType": "", "fileSize": 12345 }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(villageService, never()).generateMonshoUploadUrl(
                    any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        }
    }

    // ==================================================================
    // 認可失敗の透過
    // ==================================================================

    @Test
    @DisplayName("PUT — 権限不足は 403 VILLAGE_024（MODERATION_FORBIDDEN が HTTP に透過する）")
    void put_forbidden_returns403() throws Exception {
        org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.village.VillageErrorCode.MODERATION_FORBIDDEN))
                .given(villageService).updateMonsho(eq(VILLAGE_ID), eq(R2_KEY), eq(USER_ID));

        mockMvc.perform(put(BASE, VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "r2Key": "village/monsho/sample.png" }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }
}
