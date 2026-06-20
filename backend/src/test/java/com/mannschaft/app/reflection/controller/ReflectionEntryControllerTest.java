package com.mannschaft.app.reflection.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.service.RecallService;
import com.mannschaft.app.reflection.service.ReflectionEntryService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReflectionEntryController} API 契約テスト（F06.5・§7 #7, #8, #10）。
 *
 * <p>カバー AC: AC-1（未認証 401）/ AC-2（他人所有 404）/ AC-3 相当（バリデーション 400）/
 * AC-18（version 不一致 409・マスク中 PUT 409）/ AC-4（upsert）/ AC-8（マスク応答 本文 null）/ AC-7（recall 開示）。</p>
 */
@WebMvcTest(ReflectionEntryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReflectionEntryController 契約テスト")
class ReflectionEntryControllerTest {

    private static final Long USER_ID = 100L;
    private static final UUID THEME_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReflectionEntryService reflectionEntryService;
    @MockitoBean private RecallService recallService;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;
    @MockitoBean private AccessGuard accessGuard;

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String upsertBody(Long expectedVersion) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("themeId", THEME_ID.toString());
        node.put("targetDate", LocalDate.now().toString());
        node.set("structuredContent", objectMapper.createObjectNode().put("main_theme", "二次関数"));
        if (expectedVersion != null) {
            node.put("expectedVersion", expectedVersion);
        }
        return objectMapper.writeValueAsString(node);
    }

    @Test
    @DisplayName("AC-1: 未認証で upsert すると 401")
    void upsert_unauthenticated_401() throws Exception {
        // 認証なし → SecurityUtils.getCurrentUserId() が COMMON_000 を投げ 401。
        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-3 相当: themeId 欠落（バリデーション）で 400")
    void upsert_missingThemeId_400() throws Exception {
        authenticate();
        var node = objectMapper.createObjectNode();
        node.put("targetDate", LocalDate.now().toString());
        node.set("structuredContent", objectMapper.createObjectNode().put("main_theme", "x"));

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-2: 他人所有テーマへの upsert は 404")
    void upsert_notOwned_404() throws Exception {
        authenticate();
        given(reflectionEntryService.upsertEntry(eq(USER_ID), any()))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-18: version 不一致で 409")
    void upsert_versionConflict_409() throws Exception {
        authenticate();
        given(reflectionEntryService.upsertEntry(eq(USER_ID), any()))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_VERSION_CONFLICT));

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(2L)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-18: マスク中エントリの直接 PUT は 409")
    void upsert_masked_409() throws Exception {
        authenticate();
        given(reflectionEntryService.upsertEntry(eq(USER_ID), any()))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_ENTRY_MASKED));

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(0L)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-4: upsert 成功で 200＋エントリ応答")
    void upsert_success_200() throws Exception {
        authenticate();
        given(reflectionEntryService.upsertEntry(eq(USER_ID), any(UpsertReflectionEntryRequest.class)))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).themeId(THEME_ID.toString())
                        .isMasked(false).version(0L).build());

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.data.isMasked").value(false));
    }

    @Test
    @DisplayName("AC-8: マスク中エントリ詳細は structuredContent=null かつ isMasked=true")
    void getEntry_masked_bodyNull() throws Exception {
        authenticate();
        given(reflectionEntryService.getEntry(USER_ID, ENTRY_ID))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).isMasked(true).structuredContent(null)
                        .maskedHint(ReflectionEntryResponse.MaskedHint.builder()
                                .themeTitle("数学II").targetDate(LocalDate.now()).build())
                        .build());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/me/reflections/entries/{entryId}", ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMasked").value(true))
                .andExpect(jsonPath("$.data.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.data.maskedHint.themeTitle").value("数学II"));
    }

    @Test
    @DisplayName("AC-7: recall 保存で 200＋開示応答（isMasked=false・本文あり）")
    void recall_discloses_200() throws Exception {
        authenticate();
        given(recallService.recordRecall(eq(USER_ID), eq(ENTRY_ID), any()))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).isMasked(false)
                        .structuredContent(objectMapper.createObjectNode().put("main_theme", "開示本文"))
                        .build());
        var body = objectMapper.createObjectNode();
        body.set("recalledContent", objectMapper.createObjectNode().put("note", "思い出した"));
        body.put("selfRating", "REMEMBERED");

        mockMvc.perform(post("/api/v1/me/reflections/entries/{entryId}/recall", ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMasked").value(false))
                .andExpect(jsonPath("$.data.structuredContent.main_theme").value("開示本文"));
    }
}
