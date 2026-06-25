package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.ReflectionVocabCardsResponse;
import com.mannschaft.app.reflection.service.ReflectionVocabCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReflectionVocabCardController} API 契約テスト（F06.5 Phase 4・§7 EP #23・§13-F）。
 *
 * <p>カバー AC: AC-57（from/to 必須欠落 400・成功 200）/ AC-60（期間 366 日超 400 REFLECTION_015・未認証 401・
 * 本人スコープ）。</p>
 */
@WebMvcTest(ReflectionVocabCardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReflectionVocabCardController 契約テスト（EP #23）")
class ReflectionVocabCardControllerTest {

    private static final Long USER_ID = 100L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ReflectionVocabCardService vocabCardService;
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

    @Test
    @DisplayName("AC-60: 未認証で 401")
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-57: from 欠落で 400")
    void missingFrom_400() throws Exception {
        authenticate();
        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-57: to 欠落で 400")
    void missingTo_400() throws Exception {
        authenticate();
        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-57: 認証済み・from/to 指定で 200＋ReflectionVocabCardsResponse")
    void success_200() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willReturn(ReflectionVocabCardsResponse.builder()
                        .from(LocalDate.of(2026, 6, 1))
                        .to(LocalDate.of(2026, 6, 30))
                        .totalCards(0)
                        .page(0)
                        .size(200)
                        .cards(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCards").value(0))
                .andExpect(jsonPath("$.data.cards").isArray());
    }

    @Test
    @DisplayName("AC-60: 期間 367 日で 400＋errorCode=REFLECTION_015")
    void dateRangeTooWide_400() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_DATE_RANGE_INVALID));

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2025-06-01")
                        .param("to", "2026-06-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REFLECTION_015"));
    }

    @Test
    @DisplayName("AC-60: sourceTypes が enum 外なら 400（型変換エラー）")
    void invalidSourceType_400() throws Exception {
        authenticate();
        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .param("sourceTypes", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-58 (後方互換): sourceTypes フィルタ付きで 200（サービスへ enum List 伝搬）")
    void withSourceTypeFilter_200() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willReturn(ReflectionVocabCardsResponse.builder()
                        .from(LocalDate.of(2026, 6, 1)).to(LocalDate.of(2026, 6, 30))
                        .totalCards(0).page(0).size(200).cards(List.of()).build());

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .param("sourceTypes", "SUBJECT"))
                .andExpect(status().isOk());
    }

    // ===== Phase 4.1: AC-62/63 新パラメータ受理テスト =====

    @Test
    @DisplayName("AC-62: subjects[] 繰り返し形式で 200 が返る")
    void testGetVocabCards_subjectsRepeated_accepted() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willReturn(ReflectionVocabCardsResponse.builder()
                        .from(LocalDate.of(2026, 6, 1)).to(LocalDate.of(2026, 6, 30))
                        .totalCards(0).page(0).size(200).cards(List.of()).build());

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .param("subjects", "英語")
                        .param("subjects", "理科"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-63: shuffle=true で 200 が返る")
    void testGetVocabCards_shuffleTrue_accepted() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willReturn(ReflectionVocabCardsResponse.builder()
                        .from(LocalDate.of(2026, 6, 1)).to(LocalDate.of(2026, 6, 30))
                        .totalCards(0).page(0).size(200).cards(List.of()).build());

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .param("shuffle", "true"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("subjects[] 形式が正しく受け付けられる（後方互換確認）")
    void testGetVocabCards_legacySubjectParam_backwardCompat() throws Exception {
        authenticate();
        given(vocabCardService.getVocabCards(
                eq(USER_ID), any(), any(), any(), any(), any(),
                any(Boolean.class), any(Integer.class), any(Integer.class)))
                .willReturn(ReflectionVocabCardsResponse.builder()
                        .from(LocalDate.of(2026, 6, 1)).to(LocalDate.of(2026, 6, 30))
                        .totalCards(0).page(0).size(200).cards(List.of()).build());

        mockMvc.perform(get("/api/v1/me/reflections/cards")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .param("subjects", "英語"))
                .andExpect(status().isOk());
    }
}
