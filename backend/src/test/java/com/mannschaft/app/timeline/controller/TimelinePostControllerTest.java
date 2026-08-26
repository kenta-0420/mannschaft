package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineFeedResponse;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.timeline.service.TimelineScopeIdResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelinePostController} の単体テスト。
 *
 * <p>主眼は、FE が {@code scopeId} に slug 文字列を送るケースで、コントローラーが
 * {@link TimelineScopeIdResolver} で内部 Long ID に解決してから
 * {@link TimelinePostService#createPost(CreatePostRequest, Long, Long)} へ渡し、
 * 201 を返すこと（読み取りの feed と対称・400 根治）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelinePostController 単体テスト")
class TimelinePostControllerTest {

    @Mock
    private TimelinePostService postService;

    @Mock
    private TimelineScopeIdResolver scopeIdResolver;

    private TimelinePostController controller;
    private MockedStatic<SecurityUtils> securityUtils;

    private static final Long USER_ID = 100L;
    private static final Long TEAM_INTERNAL_ID = 92L;
    private static final Long ORG_INTERNAL_ID = 20L;

    @BeforeEach
    void setUp() {
        controller = new TimelinePostController(postService, scopeIdResolver);
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    @Test
    @DisplayName("AC-1: TEAM スラッグ scopeId → 内部IDに解決して 201・解決済みIDでサービス委譲")
    void createPost_teamSlug_resolvesAndReturns201() {
        // slug 文字列を scopeId に持つ（@JsonCreator と同じ 12 引数 String コンストラクタ経路）
        CreatePostRequest request = new CreatePostRequest(
                "チームへの投稿", "TEAM", "team-000092", null, null, null, null,
                null, null, null, null, null);
        PostResponse created = PostResponse.builder().id(1L).build();
        given(scopeIdResolver.resolve("TEAM", "team-000092")).willReturn(TEAM_INTERNAL_ID);
        given(postService.createPost(eq(request), eq(TEAM_INTERNAL_ID), eq(USER_ID)))
                .willReturn(created);

        ResponseEntity<ApiResponse<PostResponse>> response = controller.createPost(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(scopeIdResolver).resolve("TEAM", "team-000092");
        verify(postService).createPost(request, TEAM_INTERNAL_ID, USER_ID);
    }

    @Test
    @DisplayName("AC-2: ORGANIZATION スラッグ scopeId → 内部IDに解決して 201")
    void createPost_orgSlug_resolvesAndReturns201() {
        CreatePostRequest request = new CreatePostRequest(
                "組織への投稿", "ORGANIZATION", "org-abc", null, null, null, null,
                null, null, null, null, null);
        given(scopeIdResolver.resolve("ORGANIZATION", "org-abc")).willReturn(ORG_INTERNAL_ID);
        given(postService.createPost(eq(request), eq(ORG_INTERNAL_ID), eq(USER_ID)))
                .willReturn(PostResponse.builder().id(2L).build());

        ResponseEntity<ApiResponse<PostResponse>> response = controller.createPost(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(postService).createPost(request, ORG_INTERNAL_ID, USER_ID);
    }

    @Test
    @DisplayName("AC-3: 数値文字列 scopeId（後方互換）→ そのまま解決して 201")
    void createPost_numericString_resolvesAndReturns201() {
        // String コンストラクタ経由で "92" を渡す（@JsonCreator と同じ経路）
        CreatePostRequest request = new CreatePostRequest(
                "数値ID投稿", "TEAM", "92", null, null, null, null,
                null, null, null, null, null);
        given(scopeIdResolver.resolve("TEAM", "92")).willReturn(92L);
        given(postService.createPost(eq(request), eq(92L), eq(USER_ID)))
                .willReturn(PostResponse.builder().id(3L).build());

        ResponseEntity<ApiResponse<PostResponse>> response = controller.createPost(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(postService).createPost(request, 92L, USER_ID);
    }

    @Test
    @DisplayName("AC-7: getReplies は TimelineFeedResponse 形状（data.pinned=[]・data.posts）で返す")
    void getReplies_returnsTimelineFeedResponseShape() {
        Long postId = 5L;
        PostResponse reply1 = PostResponse.builder().id(101L).build();
        PostResponse reply2 = PostResponse.builder().id(102L).build();
        given(postService.getReplies(eq(postId), any(), eq(20), eq(USER_ID)))
                .willReturn(List.of(reply1, reply2));

        ResponseEntity<TimelineFeedResponse> response = controller.getReplies(postId, null, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TimelineFeedResponse body = response.getBody();
        assertThat(body).isNotNull();
        // FE は res.data.posts / res.meta.nextCursor を読む
        assertThat(body.getData().getPinned()).isEmpty();
        assertThat(body.getData().getPosts()).containsExactly(reply1, reply2);
        verify(postService).getReplies(eq(postId), any(), eq(20), eq(USER_ID));
    }

    @Test
    @DisplayName("AC-8: 返信0件は data.posts 空配列・meta.nextCursor=null・hasNext=false")
    void getReplies_emptyRepliesReturnsEmptyPostsAndNullCursor() {
        Long postId = 5L;
        given(postService.getReplies(eq(postId), any(), eq(20), eq(USER_ID)))
                .willReturn(List.of());

        ResponseEntity<TimelineFeedResponse> response = controller.getReplies(postId, null, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TimelineFeedResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getData().getPosts()).isEmpty();
        assertThat(body.getData().getPinned()).isEmpty();
        assertThat(body.getMeta().getNextCursor()).isNull();
        assertThat(body.getMeta().isHasNext()).isFalse();
    }
}
