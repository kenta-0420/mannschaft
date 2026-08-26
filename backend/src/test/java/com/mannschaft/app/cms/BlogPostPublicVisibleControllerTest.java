package com.mannschaft.app.cms;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.cms.controller.BlogPostController;
import com.mannschaft.app.cms.service.BlogFeedService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogReactionService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F19.1 Phase 7: BlogPostController の public-visible エンドポイント MockMvc テスト。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.8 Phase 7</p>
 *
 * <p>テストケース:</p>
 * <ul>
 *   <li>BLOG_VIS_001: 本人が publicVisible=true → 204 NoContent</li>
 *   <li>BLOG_VIS_002: 本人が publicVisible=false → 204 NoContent</li>
 *   <li>BLOG_VIS_003: 他人が操作 → 403 Forbidden（PUBLIC_011）</li>
 *   <li>BLOG_VIS_004: 存在しない postId → 404 Not Found（CMS_001）</li>
 *   <li>BLOG_VIS_005: publicVisible 欠落 → 400 Bad Request（バリデーションエラー）</li>
 * </ul>
 */
@WebMvcTest(BlogPostController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("BlogPostController public-visible エンドポイントテスト (F19.1 Phase 7)")
class BlogPostPublicVisibleControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long POST_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BlogPostService postService;

    @MockitoBean
    private BlogFeedService feedService;

    @MockitoBean
    private BlogReactionService reactionService;

    /** @WebMvcTest コンテキスト用: JwtAuthenticationFilter 依存解決 */
    @MockitoBean
    private AuthTokenService authTokenService;

    /** @WebMvcTest コンテキスト用: UserLocaleFilter 依存解決 */
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** @WebMvcTest コンテキスト用: ProxyInputContextFilter 依存解決 */
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
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(USER_ID), null, List.of()));
    }

    @Test
    @DisplayName("BLOG_VIS_001: PATCH /blog/posts/{id}/public-visible: 本人が true → 204")
    void patchPublicVisible_authorSetsTrue_returns204() throws Exception {
        willDoNothing().given(postService).patchPublicVisible(eq(POST_ID), eq(USER_ID), eq(true));

        mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "publicVisible": true }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("BLOG_VIS_002: PATCH /blog/posts/{id}/public-visible: 本人が false → 204")
    void patchPublicVisible_authorSetsFalse_returns204() throws Exception {
        willDoNothing().given(postService).patchPublicVisible(eq(POST_ID), eq(USER_ID), eq(false));

        mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "publicVisible": false }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("BLOG_VIS_003: PATCH /blog/posts/{id}/public-visible: 他人が操作 → 403（PUBLIC_011）")
    void patchPublicVisible_notAuthor_returns403() throws Exception {
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_011))
                .given(postService).patchPublicVisible(eq(POST_ID), eq(USER_ID), anyBoolean());

        mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "publicVisible": true }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("BLOG_VIS_004: PATCH /blog/posts/{id}/public-visible: 存在しない postId → 404（CMS_001）")
    void patchPublicVisible_postNotFound_returns404() throws Exception {
        // 認可根治戦役 Wave3-B7: CMS_001 は GlobalExceptionHandler に IDOR 秘匿のため 404 で
        // 個別マッピングした（Severity.WARN 既定の 400 を上書き。他ドメインの BOLA 存在秘匿と同流儀）。
        willThrow(new BusinessException(com.mannschaft.app.cms.CmsErrorCode.POST_NOT_FOUND))
                .given(postService).patchPublicVisible(eq(POST_ID), eq(USER_ID), anyBoolean());

        mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "publicVisible": true }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("BLOG_VIS_005: PATCH /blog/posts/{id}/public-visible: publicVisible 欠落 → 400")
    void patchPublicVisible_missingPublicVisible_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {}
                                """))
                .andExpect(status().isBadRequest());
    }
}
