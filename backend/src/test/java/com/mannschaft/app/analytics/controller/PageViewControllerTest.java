package com.mannschaft.app.analytics.controller;

import com.mannschaft.app.analytics.service.PageViewRecordingService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.ResponseCookie;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PageViewController} の API 契約テスト。
 *
 * <p>{@code @WebMvcTest} でコントローラー層のみをロードし、
 * {@link PageViewRecordingService} は {@code @MockitoBean} で差し替える。</p>
 *
 * <p>テスト対象 AC:</p>
 * <ul>
 *   <li>AC-01: 認証済みメンバーが有効 body を送ると 202</li>
 *   <li>AC-02: 未認証ユーザーが有効 body を送ると 202（フィルターを off にして検証）</li>
 *   <li>AC-03: cookie 未発行 → レスポンスに Set-Cookie が付く</li>
 *   <li>AC-04: ENUM 外の scope / contentType → 400</li>
 *   <li>AC-22: url が絶対 URL → 400</li>
 *   <li>AC-23: title が 255 文字超 → 400</li>
 * </ul>
 */
@WebMvcTest(PageViewController.class)
@AutoConfigureMockMvc(addFilters = false)
class PageViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PageViewRecordingService pageViewRecordingService;

    // @WebMvcTest コンテキスト共通の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    private static final String VALID_BODY = """
            {
              "scope": "TEAM",
              "scopeId": 1,
              "contentType": "ARTICLE",
              "contentId": 100,
              "url": "/teams/my-team/articles/100",
              "title": "春合宿のお知らせ"
            }
            """;

    // ─── AC-01: 認証済み 202 ─────────────────────────────────────────

    @Test
    @DisplayName("AC-01: 有効な body を POST すると 202 Accepted")
    void post_validBody_returns202() throws Exception {
        given(pageViewRecordingService.resolveVisitorId(any())).willReturn("test-visitor-id");
        given(pageViewRecordingService.isNewVisitor(any())).willReturn(false);

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted());

        verify(pageViewRecordingService).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── AC-02: 未認証でも 202（フィルター off で simulateゲスト）───────

    @Test
    @DisplayName("AC-02: フィルター off 環境で未認証リクエストでも 202（ゲスト計測）")
    void post_unauthenticated_returns202() throws Exception {
        given(pageViewRecordingService.resolveVisitorId(any())).willReturn("guest-visitor-id");
        given(pageViewRecordingService.isNewVisitor(any())).willReturn(false);

        // addFilters=false のためフィルター層の 401 は発生しない → ゲスト (userId=null) として通過
        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted());
    }

    // ─── AC-03: cookie 未発行 → Set-Cookie ──────────────────────────

    @Test
    @DisplayName("AC-03: mnsft_vid cookie 未発行のリクエストにはレスポンスで Set-Cookie が付く")
    void post_newVisitor_setsVisitorCookie() throws Exception {
        String newVisitorId = "new-uuid-value";
        given(pageViewRecordingService.resolveVisitorId(isNull())).willReturn(newVisitorId);
        given(pageViewRecordingService.isNewVisitor(isNull())).willReturn(true);
        given(pageViewRecordingService.buildVisitorCookie(newVisitorId))
                .willReturn(ResponseCookie.from("mnsft_vid", newVisitorId)
                        .path("/")
                        .httpOnly(true)
                        .sameSite("Lax")
                        .build());

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Set-Cookie"));
    }

    // ─── AC-04: ENUM 外 → 400 ────────────────────────────────────────

    @Test
    @DisplayName("AC-04: scope が ENUM 外の値なら 400")
    void post_invalidScope_returns400() throws Exception {
        String body = """
                {
                  "scope": "UNKNOWN_SCOPE",
                  "scopeId": 1,
                  "contentType": "ARTICLE",
                  "contentId": 100,
                  "url": "/teams/my-team/articles/100",
                  "title": "春合宿のお知らせ"
                }
                """;

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-04: contentType が ENUM 外の値なら 400")
    void post_invalidContentType_returns400() throws Exception {
        String body = """
                {
                  "scope": "TEAM",
                  "scopeId": 1,
                  "contentType": "UNKNOWN_CONTENT",
                  "contentId": 100,
                  "url": "/teams/my-team/articles/100",
                  "title": "春合宿のお知らせ"
                }
                """;

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ─── AC-22: url が絶対 URL → 400 ─────────────────────────────────

    @Test
    @DisplayName("AC-22: url が http:// 始まりの絶対 URL なら 400")
    void post_absoluteUrl_returns400() throws Exception {
        String body = """
                {
                  "scope": "TEAM",
                  "scopeId": 1,
                  "contentType": "ARTICLE",
                  "contentId": 100,
                  "url": "http://example.com/evil",
                  "title": "春合宿のお知らせ"
                }
                """;

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-22: url が // 始まりのプロトコル相対 URL なら 400")
    void post_protocolRelativeUrl_returns400() throws Exception {
        String body = """
                {
                  "scope": "TEAM",
                  "scopeId": 1,
                  "contentType": "ARTICLE",
                  "contentId": 100,
                  "url": "//example.com/evil",
                  "title": "春合宿のお知らせ"
                }
                """;

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-22: / 始まりの相対パスは正常に 202")
    void post_relativePath_returns202() throws Exception {
        given(pageViewRecordingService.resolveVisitorId(any())).willReturn("visitor-id");
        given(pageViewRecordingService.isNewVisitor(any())).willReturn(false);

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted());
    }

    // ─── AC-23: title 255 文字超 → 400 ──────────────────────────────

    @Test
    @DisplayName("AC-23: title が 256 文字以上なら 400")
    void post_titleTooLong_returns400() throws Exception {
        String longTitle = "あ".repeat(256);
        String body = """
                {
                  "scope": "TEAM",
                  "scopeId": 1,
                  "contentType": "ARTICLE",
                  "contentId": 100,
                  "url": "/teams/my-team/articles/100",
                  "title": "%s"
                }
                """.formatted(longTitle);

        mockMvc.perform(post("/api/v1/page-views")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
