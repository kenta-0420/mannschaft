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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
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
}
