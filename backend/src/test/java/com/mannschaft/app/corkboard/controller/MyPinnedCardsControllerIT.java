package com.mannschaft.app.corkboard.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.corkboard.dto.PinnedCardListResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardReferenceResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardResponse;
import com.mannschaft.app.corkboard.service.MyPinnedCardsService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.hamcrest.Matchers;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F09.8.1 Phase 3 {@link MyPinnedCardsController} の MockMvc 結合テスト。
 *
 * <p>カバー範囲（設計書 §10.2）:</p>
 * <ul>
 *   <li>GET 200 + items 構造確認（camelCase JSON）</li>
 *   <li>空リスト → items=[]、nextCursor=null、totalCount=0</li>
 *   <li>limit/cursor パラメタの伝達</li>
 *   <li>limit 省略時のデフォルト挙動</li>
 * </ul>
 */
@WebMvcTest(MyPinnedCardsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MyPinnedCardsController 結合テスト (F09.8.1 Phase 3)")
class MyPinnedCardsControllerIT {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyPinnedCardsService pinnedCardsService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

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

    @Test
    @DisplayName("正常系: 1 件返却 + JSON フィールド名 camelCase で構造を確認")
    void 正常系_1件() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 14, 23, 0);
        PinnedCardReferenceResponse ref = new PinnedCardReferenceResponse(
                "TIMELINE_POST", 9876L, "10月の活動報告", "10月の活動を以下にまとめました...",
                Boolean.TRUE, Boolean.FALSE,
                "/timeline/posts/9876", null, null, null);
        PinnedCardResponse item = new PinnedCardResponse(
                345L, 12L, "仕事メモ", "REFERENCE", "YELLOW",
                null, null, "重要！来週までに対応", null, now, ref);
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(item), null, 1L);

        given(pinnedCardsService.list(eq(USER_ID), eq(20), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].cardId").value(345))
                .andExpect(jsonPath("$.data.items[0].corkboardId").value(12))
                .andExpect(jsonPath("$.data.items[0].corkboardName").value("仕事メモ"))
                .andExpect(jsonPath("$.data.items[0].cardType").value("REFERENCE"))
                .andExpect(jsonPath("$.data.items[0].colorLabel").value("YELLOW"))
                .andExpect(jsonPath("$.data.items[0].userNote").value("重要！来週までに対応"))
                .andExpect(jsonPath("$.data.items[0].pinnedAt").exists())
                .andExpect(jsonPath("$.data.items[0].reference.type").value("TIMELINE_POST"))
                .andExpect(jsonPath("$.data.items[0].reference.id").value(9876))
                .andExpect(jsonPath("$.data.items[0].reference.snapshotTitle").value("10月の活動報告"))
                .andExpect(jsonPath("$.data.items[0].reference.isAccessible").value(true))
                .andExpect(jsonPath("$.data.items[0].reference.isDeleted").value(false))
                .andExpect(jsonPath("$.data.items[0].reference.navigateTo").value("/timeline/posts/9876"))
                .andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.totalCount").value(1));

        verify(pinnedCardsService).list(USER_ID, 20, null);
    }

    @Test
    @DisplayName("空リスト → items=[]、nextCursor=null、totalCount=0")
    void 空リスト() throws Exception {
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(), null, 0L);
        given(pinnedCardsService.list(eq(USER_ID), eq(null), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("limit/cursor パラメタが Service へ正しく伝達される")
    void パラメタ伝達() throws Exception {
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(), "next-cursor-stub", 0L);
        given(pinnedCardsService.list(eq(USER_ID), eq(50), eq("MY_CURSOR"))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards")
                        .param("limit", "50")
                        .param("cursor", "MY_CURSOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor-stub"));

        verify(pinnedCardsService).list(USER_ID, 50, "MY_CURSOR");
    }

    @Test
    @DisplayName("URL カードレスポンス: navigate_to = url 値で返る")
    void URLカードレスポンス() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        PinnedCardReferenceResponse ref = new PinnedCardReferenceResponse(
                "URL", null, null, null,
                Boolean.TRUE, Boolean.FALSE,
                "https://booking.example.com/rooms",
                "https://booking.example.com/rooms",
                "会議室予約 - Example Corp",
                "https://booking.example.com/og.png");
        PinnedCardResponse item = new PinnedCardResponse(
                312L, 12L, "仕事メモ", "URL", "BLUE",
                "会議室予約システム", null, null, null, now, ref);
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(item), null, 1L);

        given(pinnedCardsService.list(eq(USER_ID), eq(null), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reference.type").value("URL"))
                .andExpect(jsonPath("$.data.items[0].reference.url").value("https://booking.example.com/rooms"))
                .andExpect(jsonPath("$.data.items[0].reference.navigateTo").value("https://booking.example.com/rooms"))
                .andExpect(jsonPath("$.data.items[0].reference.ogTitle").value("会議室予約 - Example Corp"))
                .andExpect(jsonPath("$.data.items[0].reference.ogImageUrl").value("https://booking.example.com/og.png"));
    }

    @Test
    @DisplayName("MEMO カードは reference を含まない")
    void MEMOカード_referenceなし() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        PinnedCardResponse item = new PinnedCardResponse(
                270L, 13L, "プロジェクト資料", "MEMO", "GREEN",
                "買い出しリスト", "- マーカー\n- 付箋", null, null, now, null);
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(item), null, 1L);

        given(pinnedCardsService.list(eq(USER_ID), eq(null), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cardType").value("MEMO"))
                .andExpect(jsonPath("$.data.items[0].title").value("買い出しリスト"))
                .andExpect(jsonPath("$.data.items[0].reference").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("CHAT_MESSAGE カード: navigate_to=/chat/channels/{channelId}?messageId={id} 形式で返る")
    void CHAT_MESSAGE_navigate_to() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        PinnedCardReferenceResponse ref = new PinnedCardReferenceResponse(
                "CHAT_MESSAGE", 5555L, "重要メッセージ", "プロジェクトの方針について...",
                Boolean.TRUE, Boolean.FALSE,
                "/chat/channels/42?messageId=5555", null, null, null);
        PinnedCardResponse item = new PinnedCardResponse(
                900L, 12L, "仕事メモ", "REFERENCE", "BLUE",
                null, null, "あとで確認", null, now, ref);
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(item), null, 1L);

        given(pinnedCardsService.list(eq(USER_ID), eq(null), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reference.type").value("CHAT_MESSAGE"))
                .andExpect(jsonPath("$.data.items[0].reference.id").value(5555))
                .andExpect(jsonPath("$.data.items[0].reference.navigateTo").value("/chat/channels/42?messageId=5555"))
                .andExpect(jsonPath("$.data.items[0].reference.isAccessible").value(true));
    }

    @Test
    @DisplayName("権限喪失参照先: isAccessible=false、navigateTo=null で返る")
    void 権限喪失レスポンス() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        PinnedCardReferenceResponse ref = new PinnedCardReferenceResponse(
                "TIMELINE_POST", 9000L, "（スナップショット）月次振り返り", "9月の活動を...",
                Boolean.FALSE, Boolean.FALSE,
                null, null, null, null);
        PinnedCardResponse item = new PinnedCardResponse(
                251L, 12L, "仕事メモ", "REFERENCE", "RED",
                null, null, "退会したメンバーの投稿", null, now, ref);
        PinnedCardListResponse stub = new PinnedCardListResponse(List.of(item), null, 1L);

        given(pinnedCardsService.list(eq(USER_ID), eq(null), eq(null))).willReturn(stub);

        mockMvc.perform(get("/api/v1/users/me/corkboards/pinned-cards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].reference.isAccessible").value(false))
                .andExpect(jsonPath("$.data.items[0].reference.navigateTo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].reference.snapshotTitle").value("（スナップショット）月次振り返り"));
    }
}
