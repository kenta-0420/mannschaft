package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.timeline.service.TimelineScopeIdResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TimelineFeedController} の単体テスト。
 *
 * <p>slug/Long 文字列 → 内部 Long ID の解決は {@link TimelineScopeIdResolver} に委譲したため、
 * 本テストはコントローラーがリゾルバの解決結果でサービスを呼ぶ「委譲」を検証する。
 * 解決ロジック自体（slug/Long/未存在/フォールバック）は
 * {@code TimelineScopeIdResolverTest} で検証する（書き込み経路と共有のため）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineFeedController 単体テスト")
class TimelineFeedControllerTest {

    @Mock
    private TimelinePostService postService;

    @Mock
    private TimelineScopeIdResolver scopeIdResolver;

    @InjectMocks
    private TimelineFeedController controller;

    private static final Long TEAM_INTERNAL_ID = 10L;
    private static final String TEAM_SLUG = "test-team";
    private static final Long USER_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtils;
    private MockMvc mockMvc;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("getFeedPage - cursor/limit HTTP契約")
    class GetFeedPageHttpContract {

        @Test
        @DisplayName("limitをsizeより優先し、次ページではpinnedを再取得しない")
        void limitWinsOverSizeAndCursorSkipsPinnedLookup() throws Exception {
            given(scopeIdResolver.resolve("TEAM", TEAM_SLUG)).willReturn(TEAM_INTERNAL_ID);
            given(postService.getFeedPage("TEAM", TEAM_INTERNAL_ID, null, 42L, 3, USER_ID))
                    .willReturn(List.of());

            mockMvc.perform(get("/api/v1/timeline/feed")
                            .param("scopeType", "TEAM")
                            .param("scopeId", TEAM_SLUG)
                            .param("cursor", "42")
                            .param("limit", "3")
                            .param("size", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.pinned").isEmpty())
                    .andExpect(jsonPath("$.meta.limit").value(3))
                    .andExpect(jsonPath("$.meta.hasNext").value(false));

            verify(postService).getFeedPage("TEAM", TEAM_INTERNAL_ID, null, 42L, 3, USER_ID);
            verify(postService, never()).getPinnedPosts("TEAM", TEAM_INTERNAL_ID, null, USER_ID);
        }

        @Test
        @DisplayName("size別名を使い、上限50へ制限する")
        void sizeAliasIsCappedAtFifty() throws Exception {
            given(scopeIdResolver.resolve("PUBLIC", "0")).willReturn(0L);
            given(postService.getFeedPage("PUBLIC", 0L, null, null, 50, USER_ID))
                    .willReturn(List.of());
            given(postService.getPinnedPosts("PUBLIC", 0L, null, USER_ID)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/timeline/feed").param("size", "80"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.limit").value(50));

            verify(postService).getFeedPage("PUBLIC", 0L, null, null, 50, USER_ID);
            verify(postService).getPinnedPosts("PUBLIC", 0L, null, USER_ID);
        }

        @Test
        @DisplayName("0以下のlimitは既定20へ正規化する")
        void nonPositiveLimitUsesDefault() throws Exception {
            given(scopeIdResolver.resolve("PUBLIC", "0")).willReturn(0L);
            given(postService.getFeedPage("PUBLIC", 0L, null, null, 20, USER_ID))
                    .willReturn(List.of());
            given(postService.getPinnedPosts("PUBLIC", 0L, null, USER_ID)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/timeline/feed").param("limit", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.limit").value(20));

            verify(postService).getFeedPage("PUBLIC", 0L, null, null, 20, USER_ID);
        }
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Nested
    @DisplayName("getFeed - リゾルバ委譲")
    class GetFeedResolverDelegation {

        @Test
        @DisplayName("リゾルバで解決した内部ID で getFeed / getPinnedPosts を呼ぶ")
        void resolvesViaResolverThenDelegates() {
            given(scopeIdResolver.resolve("TEAM", TEAM_SLUG)).willReturn(TEAM_INTERNAL_ID);
            given(postService.getFeed(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), anyInt(), eq(USER_ID)))
                    .willReturn(List.of());
            given(postService.getPinnedPosts(eq("TEAM"), eq(TEAM_INTERNAL_ID), any(), eq(USER_ID)))
                    .willReturn(List.of());

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("TEAM", TEAM_SLUG, null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(scopeIdResolver).resolve("TEAM", TEAM_SLUG);
            verify(postService).getFeed("TEAM", TEAM_INTERNAL_ID, null, 20, USER_ID);
            verify(postService).getPinnedPosts("TEAM", TEAM_INTERNAL_ID, null, USER_ID);
        }

        @Test
        @DisplayName("getFeed レスポンスは pinned と posts を含む TimelineFeedResponse を返す")
        void getFeed_returnsTimelineFeedResponseWithPinnedAndPosts() {
            PostResponse pinnedPost = PostResponse.builder().id(1L).build();
            PostResponse normalPost = PostResponse.builder().id(2L).build();
            given(scopeIdResolver.resolve("PUBLIC", "0")).willReturn(0L);
            given(postService.getFeed(eq("PUBLIC"), eq(0L), any(), anyInt(), eq(USER_ID)))
                    .willReturn(List.of(normalPost));
            given(postService.getPinnedPosts(eq("PUBLIC"), eq(0L), any(), eq(USER_ID)))
                    .willReturn(List.of(pinnedPost));

            ResponseEntity<TimelineFeedResponse> response =
                    controller.getFeed("PUBLIC", "0", null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            TimelineFeedResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getData().getPinned()).containsExactly(pinnedPost);
            assertThat(body.getData().getPosts()).containsExactly(normalPost);
            assertThat(body.getMeta().getLimit()).isEqualTo(20);
        }
    }

    /**
     * TimelineFeedController#getMyFeed の自己スコープ性を固定する契約テスト。
     * {@code postService.getMyFeed} は {@code SecurityUtils.getCurrentUserId()} のみを
     * 検索条件に束縛する（cursor/limit は非識別子パラメータ）ため、
     * URL・クエリに他人の識別子を指定する余地が構造的に無い。
     */
    @Nested
    @DisplayName("getMyFeed - 自己スコープ")
    class GetMyFeedSelfScope {

        @Test
        @DisplayName("SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
        void getMyFeed_boundToCurrentUserOnly() {
            PostResponse post = PostResponse.builder().id(1L).build();
            given(postService.getMyFeed(eq(USER_ID), any(), anyInt())).willReturn(List.of(post));

            ResponseEntity<TimelineFeedResponse> response = controller.getMyFeed(null, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // 他人の userId をクエリに渡す経路が存在しないことの裏取り: 呼び出しは常に USER_ID のみ。
            verify(postService).getMyFeed(USER_ID, null, 20);
        }
    }
}
