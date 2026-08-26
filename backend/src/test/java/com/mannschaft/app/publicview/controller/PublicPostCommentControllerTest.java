package com.mannschaft.app.publicview.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.dto.PublicPostCommentRequest;
import com.mannschaft.app.publicview.dto.PublicPostCommentResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.service.PublicPostCommentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link PublicPostCommentController} の MockMvc 結合テスト（F19.1 Phase 6-B）。
 *
 * <p>設計書 §6.7 のステータスコード網羅:</p>
 * <ul>
 *   <li>COMM_001: GET comments 未ログイン → 200（空リスト）</li>
 *   <li>COMM_002: GET comments 未ログイン → 200（コメントあり）</li>
 *   <li>COMM_003: POST comment ログイン済み → 201</li>
 *   <li>COMM_004: POST comment 未ログイン → 401</li>
 *   <li>COMM_005: POST comment 存在しない postId → 404（PUBLIC_008）</li>
 *   <li>COMM_006: DELETE 自分のコメント → 204</li>
 *   <li>COMM_007: DELETE 他人のコメント（非ADMIN）→ 403（PUBLIC_010）</li>
 *   <li>COMM_008: DELETE 存在しないコメント → 404（PUBLIC_009）</li>
 * </ul>
 */
@WebMvcTest(PublicPostCommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PublicPostCommentController 結合テスト (F19.1 Phase 6-B)")
class PublicPostCommentControllerTest {

    private static final Long POST_ID = 10L;
    private static final Long USER_ID = 101L;
    private static final UUID COMMENT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PublicPostCommentService commentService;

    // WebMvcTest が要求する依存の最小モック注入
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
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContextAfter() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────
    // GET /api/v1/public/blog-posts/{postId}/comments
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COMM_001: GET comments 未ログイン → 200（空リスト）")
    void getComments_anonymous_emptyList_returns200() throws Exception {
        Page<PublicPostCommentResponse> emptyPage = new PageImpl<>(
                List.of(), PageRequest.of(0, 20), 0);
        given(commentService.getComments(eq(POST_ID), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/public/blog-posts/{postId}/comments", POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("COMM_002: GET comments 未ログイン → 200（コメントあり）")
    void getComments_anonymous_withComments_returns200() throws Exception {
        Page<PublicPostCommentResponse> page = new PageImpl<>(
                List.of(
                        sampleCommentResponse(COMMENT_ID, "テストコメント1"),
                        sampleCommentResponse(UUID.randomUUID(), "テストコメント2")
                ),
                PageRequest.of(0, 20),
                2
        );
        given(commentService.getComments(eq(POST_ID), any(Pageable.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/public/blog-posts/{postId}/comments", POST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].commentId").value(COMMENT_ID.toString()))
                .andExpect(jsonPath("$.content[0].content").value("テストコメント1"))
                .andExpect(jsonPath("$.content[0].authorDisplayName").value("テストユーザー"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ─────────────────────────────────────────────────────────────
    // POST /api/v1/public/blog-posts/{postId}/comments
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COMM_003: POST comment ログイン済み → 201 Created")
    void postComment_authenticated_returns201() throws Exception {
        // ログイン状態をセット
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        PublicPostCommentRequest request = new PublicPostCommentRequest("新しいコメントです");
        PublicPostCommentResponse response = sampleCommentResponse(COMMENT_ID, "新しいコメントです");

        given(commentService.postComment(eq(POST_ID), eq(USER_ID), any(PublicPostCommentRequest.class)))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/public/blog-posts/{postId}/comments", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commentId").value(COMMENT_ID.toString()))
                .andExpect(jsonPath("$.content").value("新しいコメントです"));
    }

    @Test
    @DisplayName("COMM_004: POST comment 未ログイン → 401 Unauthorized")
    void postComment_anonymous_returns401() throws Exception {
        // SecurityContextHolder をクリア（未ログイン状態）
        SecurityContextHolder.clearContext();

        PublicPostCommentRequest request = new PublicPostCommentRequest("コメント");

        mockMvc.perform(post("/api/v1/public/blog-posts/{postId}/comments", POST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("COMM_005: POST comment 存在しない postId → 404（PUBLIC_008）")
    void postComment_nonExistentPost_returns404() throws Exception {
        // ログイン状態をセット
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        PublicPostCommentRequest request = new PublicPostCommentRequest("コメント");

        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_008))
                .given(commentService).postComment(eq(999L), any(), any());

        mockMvc.perform(post("/api/v1/public/blog-posts/{postId}/comments", 999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────
    // DELETE /api/v1/public/blog-posts/{postId}/comments/{commentId}
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COMM_006: DELETE 自分のコメント → 204 No Content")
    void deleteComment_ownComment_returns204() throws Exception {
        // ログイン状態をセット
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        willDoNothing().given(commentService).deleteComment(eq(COMMENT_ID), eq(USER_ID), eq(false));

        mockMvc.perform(delete("/api/v1/public/blog-posts/{postId}/comments/{commentId}",
                        POST_ID, COMMENT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("COMM_007: DELETE 他人のコメント（非ADMIN）→ 403（PUBLIC_010）")
    void deleteComment_otherUserComment_notAdmin_returns403() throws Exception {
        // ログイン状態をセット（非ADMIN）
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_010))
                .given(commentService).deleteComment(eq(COMMENT_ID), eq(USER_ID), eq(false));

        mockMvc.perform(delete("/api/v1/public/blog-posts/{postId}/comments/{commentId}",
                        POST_ID, COMMENT_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("COMM_008: DELETE 存在しないコメント → 404（PUBLIC_009）")
    void deleteComment_nonExistentComment_returns404() throws Exception {
        // ログイン状態をセット
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        UUID nonExistentId = UUID.randomUUID();
        willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_009))
                .given(commentService).deleteComment(eq(nonExistentId), eq(USER_ID), eq(false));

        mockMvc.perform(delete("/api/v1/public/blog-posts/{postId}/comments/{commentId}",
                        POST_ID, nonExistentId))
                .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────
    // テストヘルパー
    // ─────────────────────────────────────────────────────────────

    private PublicPostCommentResponse sampleCommentResponse(UUID commentId, String content) {
        return new PublicPostCommentResponse(
                commentId.toString(),
                USER_ID,
                "テストユーザー",
                content,
                OffsetDateTime.now()
        );
    }
}
