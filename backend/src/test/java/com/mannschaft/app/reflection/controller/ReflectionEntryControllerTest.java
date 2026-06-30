package com.mannschaft.app.reflection.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionOutlineRevealLevel;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.service.RecallService;
import com.mannschaft.app.reflection.service.ReflectionEntryService;
import com.mannschaft.app.reflection.service.ReflectionMaskedOutlineExtractor;
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

    // ===== Phase 4: 暗記カード（TERM_CARD・§13） =====

    @Test
    @DisplayName("AC-48: 当日（非マスク）upsert で応答 structuredContent に type/cards が反映される")
    void upsert_termCard_typeReflected_200() throws Exception {
        authenticate();
        var content = objectMapper.createObjectNode();
        content.put("main_theme", "英単語");
        var sections = content.putArray("sections");
        var card = sections.addObject();
        card.put("type", "TERM_CARD");
        card.put("heading", "今日の単語");
        var cards = card.putArray("cards");
        cards.addObject().put("term", "abandon").put("meaning", "見捨てる");

        given(reflectionEntryService.upsertEntry(eq(USER_ID), any(UpsertReflectionEntryRequest.class)))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).themeId(THEME_ID.toString())
                        .isMasked(false).structuredContent(content).version(0L).build());

        var node = objectMapper.createObjectNode();
        node.put("themeId", THEME_ID.toString());
        node.put("targetDate", LocalDate.now().toString());
        node.set("structuredContent", content);

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMasked").value(false))
                .andExpect(jsonPath("$.data.structuredContent.sections[0].type").value("TERM_CARD"))
                .andExpect(jsonPath("$.data.structuredContent.sections[0].cards[0].term").value("abandon"));
    }

    @Test
    @DisplayName("AC-54: cards/字数上限超過 upsert は 400＋errorCode=REFLECTION_007")
    void upsert_termCardLimitExceeded_400() throws Exception {
        authenticate();
        given(reflectionEntryService.upsertEntry(eq(USER_ID), any()))
                .willThrow(new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID));

        mockMvc.perform(put("/api/v1/me/reflections/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REFLECTION_007"));
    }

    @Test
    @DisplayName("AC-51: マスク中エントリ詳細に答え側の語が現れない（cue のみ・structuredContent=null）")
    void getEntry_maskedTermCard_noAnswerLeak() throws Exception {
        authenticate();
        var quiz = ReflectionEntryResponse.MaskedCardQuiz.builder()
                .heading("今日の単語")
                .direction(com.mannschaft.app.reflection.RecallDirection.MEANING_TO_TERM)
                .prompts(List.of(ReflectionEntryResponse.MaskedCardPrompt.builder()
                        .promptSide("MEANING").promptText("見捨てる").build()))
                .build();
        given(reflectionEntryService.getEntry(USER_ID, ENTRY_ID))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).isMasked(true).structuredContent(null)
                        .maskedHint(ReflectionEntryResponse.MaskedHint.builder()
                                .themeTitle("英単語").targetDate(LocalDate.now())
                                .recallDirection(com.mannschaft.app.reflection.RecallDirection.MEANING_TO_TERM)
                                .cardQuiz(List.of(quiz)).build())
                        .build());

        String responseBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/me/reflections/entries/{entryId}", ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMasked").value(true))
                .andExpect(jsonPath("$.data.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.data.maskedHint.recallDirection").value("MEANING_TO_TERM"))
                .andExpect(jsonPath("$.data.maskedHint.cardQuiz[0].prompts[0].promptText").value("見捨てる"))
                .andReturn().getResponse().getContentAsString();

        // 答え側（term=abandon）がペイロード文字列に一切含まれない（機械的検証・AC-51）。
        org.assertj.core.api.Assertions.assertThat(responseBody).doesNotContain("abandon");
    }

    // ===== §13-C 増分: OUTLINE 段階式マスク（足場ラダー・AC-89） =====

    @Test
    @DisplayName("AC-89: マスク詳細応答の足場 PARTIAL に detail/supplement・heading 4字目以降が現れない")
    void getEntry_maskedOutlineScaffold_partial_noLeak() throws Exception {
        authenticate();
        // 同義反復回避（誠実な漏洩番人）: 手組みの切り詰め済み値ではなく、実 SECRET を含む full 本文を
        // real extractor で PARTIAL に切り詰めて足場を構築し、シリアライズ経路へ実コンテンツを通す。
        // mapper テスト（masked_outlineScaffold_partial）と同じ本文を流用する。
        JsonNode fullContent = objectMapper.readTree(
                "{\"main_theme\":\"二次関数の最大最小\",\"sections\":["
                        + "{\"type\":\"OUTLINE\",\"heading\":\"今日のポイント\",\"subsections\":["
                        + "{\"sub_heading\":\"頂点SECRET\",\"detail\":\"詳細SECRET\",\"supplement\":\"補足SECRET\"}]}]}");
        var scaffold = new ReflectionMaskedOutlineExtractor()
                .extractScaffold(fullContent, ReflectionOutlineRevealLevel.PARTIAL);
        given(reflectionEntryService.getEntry(USER_ID, ENTRY_ID))
                .willReturn(ReflectionEntryResponse.builder()
                        .id(ENTRY_ID.toString()).isMasked(true).structuredContent(null)
                        .maskedHint(ReflectionEntryResponse.MaskedHint.builder()
                                .themeTitle("数学II").targetDate(LocalDate.now())
                                .outlineScaffold(scaffold).build())
                        .build());

        String responseBody = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/me/reflections/entries/{entryId}", ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isMasked").value(true))
                .andExpect(jsonPath("$.data.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.data.maskedHint.outlineScaffold.level").value("PARTIAL"))
                .andExpect(jsonPath("$.data.maskedHint.outlineScaffold.mainTheme").value("二次関"))
                .andExpect(jsonPath("$.data.maskedHint.outlineScaffold.sections[0].heading").value("今日の"))
                .andReturn().getResponse().getContentAsString();

        // 実データ漏洩番人: full 本文の答え側（小見出し/詳細/補足）と heading 4 字目以降が
        // シリアライズ済みペイロードに一切現れないことを実 SECRET で検証（AC-89）。
        org.assertj.core.api.Assertions.assertThat(responseBody)
                .doesNotContain("頂点SECRET")
                .doesNotContain("詳細SECRET")
                .doesNotContain("補足SECRET")
                .doesNotContain("ポイント");
    }
}
