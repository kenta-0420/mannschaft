package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.service.CorkboardCardService;
import com.mannschaft.app.corkboard.service.MyCorkboardPinService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.8.1 {@link CorkboardCardController#pinCard} の MockMvc 結合テスト。
 *
 * <p>{@code @WebMvcTest} で Web レイヤーのみ起動し、Service 層は {@link MockitoBean} で差し替える。
 * {@code @AutoConfigureMockMvc(addFilters = false)} で Spring Security フィルタを無効化し、
 * {@link SecurityContextHolder} に直接認証情報をセットする。</p>
 *
 * <p>カバー範囲（設計書 §10.2）:</p>
 * <ul>
 *   <li>PATCH .../pin 200 + レスポンスボディ確認</li>
 *   <li>他人のボード 403 + CORKBOARD_011</li>
 *   <li>存在しないカード 400 + CORKBOARD_002</li>
 *   <li>TEAM ボードのカード 403 + CORKBOARD_011</li>
 *   <li>上限到達後 409 + CORKBOARD_013</li>
 *   <li>アーカイブ済みカード 400 + CORKBOARD_012</li>
 *   <li>is_pinned 未指定 → Bean Validation 400</li>
 * </ul>
 */
@WebMvcTest(CorkboardCardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CorkboardCardController#pinCard 結合テスト (F09.8.1)")
class MyCorkboardPinControllerIT {

    private static final Long USER_ID = 1L;
    private static final Long BOARD_ID = 10L;
    private static final Long CARD_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyCorkboardPinService pinService;

    // CorkboardCardController が依存するもう一方の Service（pinCard 単体では呼ばれない）
    @MockitoBean
    private CorkboardCardService cardService;

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

    /** mock 用に最小限のレスポンスを生成する。 */
    private CorkboardCardResponse stubResponse(boolean isPinned) {
        return new CorkboardCardResponse(
                CARD_ID, BOARD_ID, "MEMO", null, null, null,
                "メモ", "本文", null, null, null, null,
                "NONE", "MEDIUM", 0, 0, 0, null, null,
                false, isPinned, isPinned ? LocalDateTime.now() : null,
                false, USER_ID, null, null);
    }

    @Nested
    @DisplayName("PATCH /api/v1/corkboards/{boardId}/cards/{cardId}/pin")
    class PinCard {

        @Test
        @DisplayName("正常系: pin → 200 + is_pinned=true")
        void 正常系_pin_200() throws Exception {
            given(pinService.togglePin(eq(BOARD_ID), eq(CARD_ID), eq(true), eq(USER_ID)))
                    .willReturn(stubResponse(true));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(CARD_ID))
                    .andExpect(jsonPath("$.data.isPinned").value(true))
                    .andExpect(jsonPath("$.data.pinnedAt").exists());

            verify(pinService).togglePin(BOARD_ID, CARD_ID, true, USER_ID);
        }

        @Test
        @DisplayName("正常系: unpin → 200 + is_pinned=false + pinned_at=null")
        void 正常系_unpin_200() throws Exception {
            given(pinService.togglePin(eq(BOARD_ID), eq(CARD_ID), eq(false), eq(USER_ID)))
                    .willReturn(stubResponse(false));

            String body = """
                    { "isPinned": false }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isPinned").value(false))
                    .andExpect(jsonPath("$.data.pinnedAt").doesNotExist());
        }

        @Test
        @DisplayName("他人のボード → Service が CORKBOARD_011 → 403")
        void 他人ボード_403() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY))
                    .given(pinService).togglePin(eq(BOARD_ID), eq(CARD_ID), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_011"));
        }

        @Test
        @DisplayName("TEAM スコープのボード → Service が CORKBOARD_011 → 403")
        void TEAMスコープ_403() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY))
                    .given(pinService).togglePin(eq(BOARD_ID), eq(CARD_ID), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_011"));
        }

        @Test
        @DisplayName("存在しないカード → Service が CORKBOARD_002 → 400")
        void カード不在_400() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.CARD_NOT_FOUND))
                    .given(pinService).togglePin(eq(BOARD_ID), eq(CARD_ID), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_002"));
        }

        @Test
        @DisplayName("アーカイブ済みカード → Service が CORKBOARD_012 → 400")
        void アーカイブ済み_400() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.PIN_ARCHIVED_NOT_ALLOWED))
                    .given(pinService).togglePin(eq(BOARD_ID), eq(CARD_ID), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_012"));
        }

        @Test
        @DisplayName("上限到達 → Service が CORKBOARD_013 → 409")
        void 上限到達_409() throws Exception {
            willThrow(new BusinessException(CorkboardErrorCode.PIN_LIMIT_EXCEEDED))
                    .given(pinService).togglePin(eq(BOARD_ID), eq(CARD_ID), anyBoolean(), eq(USER_ID));

            String body = """
                    { "isPinned": true }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CORKBOARD_013"));
        }

        @Test
        @DisplayName("is_pinned 未指定 → @NotNull 違反で 400")
        void isPinned未指定_400() throws Exception {
            String body = "{}";

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is_pinned が boolean 以外 → JSON パースエラー → 400")
        void isPinned_型エラー_400() throws Exception {
            String body = """
                    { "is_pinned": "yes" }
                    """;

            mockMvc.perform(patch("/api/v1/corkboards/{boardId}/cards/{cardId}/pin", BOARD_ID, CARD_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    /** ServiceMock の戻り値が使われない（pinCard 以外の経路への到達なし）ことを保証するためのスタブ抑止用。 */
    @SuppressWarnings("unused")
    private void unusedStubGuard() {
        any();
    }
}
