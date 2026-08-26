package com.mannschaft.app.chat.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.service.ChatBookmarkService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.util.List;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F04.2 Phase 11 第一陣 A 分類: チャットブックマーク削除 API の WebMvc テスト。
 */
@WebMvcTest(ChatBookmarkController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ChatBookmarkController WebMvc テスト")
class ChatBookmarkControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long MESSAGE_ID = 501L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ChatBookmarkService bookmarkService;

    // F11.3 / F14.1 共通フィルター・コンテキストの依存補完
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("DELETE ブックマーク削除 正常系: 204 + Service 呼び出し")
    void removeBookmark_204() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/bookmarks/{messageId}", MESSAGE_ID))
                .andExpect(status().isNoContent());

        then(bookmarkService).should(times(1)).removeBookmark(MESSAGE_ID, USER_ID);
    }

    @Test
    @DisplayName("DELETE ブックマーク削除 冪等性: 存在しない messageId でも 204（Repository 側で no-op）")
    void removeBookmark_idempotent() throws Exception {
        // Service が例外を出さない設計（deleteByUserIdAndMessageId は no-op で 0 件削除）
        mockMvc.perform(delete("/api/v1/chat/bookmarks/{messageId}", 99999L))
                .andExpect(status().isNoContent());

        then(bookmarkService).should(times(1)).removeBookmark(99999L, USER_ID);
    }
}
