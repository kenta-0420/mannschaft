package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.CreateAttachmentRequest;
import com.mannschaft.app.timeline.dto.CreatePollRequest;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.entity.TimelinePostAttachmentEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEditEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostAttachmentRepository;
import com.mannschaft.app.timeline.repository.TimelinePostEditRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelinePostService} の単体テスト。
 * 投稿CRUD・フィード取得・検索・ピン留めを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelinePostService 単体テスト")
class TimelinePostServiceTest {

    @Mock
    private TimelinePostRepository postRepository;

    @Mock
    private TimelinePostAttachmentRepository attachmentRepository;

    @Mock
    private TimelinePostEditRepository editRepository;

    @Mock
    private TimelinePostReactionRepository reactionRepository;

    @Mock
    private TimelinePollService pollService;

    @Mock
    private TimelineMapper timelineMapper;

    @InjectMocks
    private TimelinePostService timelinePostService;

    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;

    private TimelinePostEntity createPost() {
        return TimelinePostEntity.builder()
                .scopeType(PostScopeType.PUBLIC)
                .scopeId(0L)
                .userId(USER_ID)
                .postedAsType(PostedAsType.USER)
                .content("テスト投稿")
                .status(PostStatus.PUBLISHED)
                .build();
    }

    private PostResponse createPostResponse() {
        return new PostResponse(POST_ID, "PUBLIC", 0L, USER_ID, null, "USER", null,
                null, "テスト投稿", null, 0, "PUBLISHED", null, false, 0, 0, (short) 0, (short) 0,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // createPost
    // ========================================
    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("正常系: テキスト投稿を作成できる")
        void テキスト投稿を作成できる() {
            // given
            CreatePostRequest req = new CreatePostRequest("テスト投稿", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: 予約投稿の場合はSCHEDULEDステータスで作成される")
        void 予約投稿の場合はSCHEDULEDステータスで作成される() {
            // given
            LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1);
            CreatePostRequest req = new CreatePostRequest("予約投稿", "PUBLIC", 0L,
                    "USER", null, null, null, scheduledAt, null, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.createPost(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("正常系: リプライの場合は親投稿のリプライ数がインクリメントされる")
        void リプライの場合は親投稿のリプライ数がインクリメントされる() {
            // given
            Long parentId = 10L;
            CreatePostRequest req = new CreatePostRequest("リプライ", "PUBLIC", 0L,
                    "USER", null, parentId, null, null, null, null);
            TimelinePostEntity savedPost = createPost();
            TimelinePostEntity parent = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(postRepository.findById(parentId)).willReturn(Optional.of(parent));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(postRepository).findById(parentId);
        }

        @Test
        @DisplayName("正常系: リポストの場合は元投稿のリポスト数がインクリメントされる")
        void リポストの場合は元投稿のリポスト数がインクリメントされる() {
            // given
            Long repostOfId = 20L;
            CreatePostRequest req = new CreatePostRequest(null, "PUBLIC", 0L,
                    "USER", null, null, repostOfId, null, null, null);
            TimelinePostEntity savedPost = createPost();
            TimelinePostEntity original = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(postRepository.findById(repostOfId)).willReturn(Optional.of(original));
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(postRepository).findById(repostOfId);
        }

        @Test
        @DisplayName("正常系: 投票付き投稿を作成できる")
        void 投票付き投稿を作成できる() {
            // given
            CreatePollRequest pollReq = new CreatePollRequest("質問？", List.of("A", "B"), null);
            CreatePostRequest req = new CreatePostRequest("投票付き", "PUBLIC", 0L,
                    "USER", null, null, null, null, pollReq, null);
            TimelinePostEntity savedPost = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(savedPost);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            timelinePostService.createPost(req, USER_ID);

            // then
            verify(pollService).createPoll(any(), eq(pollReq));
        }

        @Test
        @DisplayName("異常系: コンテンツが空でリポストでも投票でもない場合はエラー")
        void コンテンツが空でリポストでも投票でもない場合はエラー() {
            // given
            CreatePostRequest req = new CreatePostRequest("", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.EMPTY_POST_CONTENT));
        }

        @Test
        @DisplayName("異常系: 添付ファイルが10件を超過するとエラー")
        void 添付ファイルが10件を超過するとエラー() {
            // given
            List<CreateAttachmentRequest> attachments = java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> new CreateAttachmentRequest("IMAGE", "key" + i, "file" + i + ".jpg",
                            1024, "image/jpeg", null, null, null, null, null, null, null, null, null, null, null))
                    .toList();
            CreatePostRequest req = new CreatePostRequest("テスト", "PUBLIC", 0L,
                    "USER", null, null, null, null, null, attachments);

            // when & then
            assertThatThrownBy(() -> timelinePostService.createPost(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED));
        }
    }

    // ========================================
    // updatePost
    // ========================================
    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("正常系: 投稿を更新し編集履歴が記録される")
        void 投稿を更新し編集履歴が記録される() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("更新内容");
            PostResponse expected = createPostResponse();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(editRepository.save(any(TimelinePostEditEntity.class)))
                    .willReturn(TimelinePostEditEntity.builder().build());
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.updatePost(POST_ID, req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(editRepository).save(any(TimelinePostEditEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しない投稿を更新しようとするとエラー")
        void 存在しない投稿を更新しようとするとエラー() {
            // given
            UpdatePostRequest req = new UpdatePostRequest("更新");
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 他人の投稿を更新しようとするとエラー")
        void 他人の投稿を更新しようとするとエラー() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("更新");
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }

        @Test
        @DisplayName("異常系: 空コンテンツで更新しようとするとエラー")
        void 空コンテンツで更新しようとするとエラー() {
            // given
            TimelinePostEntity post = createPost();
            UpdatePostRequest req = new UpdatePostRequest("");
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.updatePost(POST_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.EMPTY_POST_CONTENT));
        }
    }

    // ========================================
    // deletePost
    // ========================================
    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("正常系: 投稿を論理削除できる")
        void 投稿を論理削除できる() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);

            // when
            timelinePostService.deletePost(POST_ID, USER_ID);

            // then
            verify(postRepository).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("異常系: 他人の投稿は削除できない")
        void 他人の投稿は削除できない() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));

            // when & then
            assertThatThrownBy(() -> timelinePostService.deletePost(POST_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.NOT_POST_OWNER));
        }
    }

    // ========================================
    // getFeed
    // ========================================
    @Nested
    @DisplayName("getFeed")
    class GetFeed {

        @Test
        @DisplayName("正常系: スコープ別フィードを取得できる")
        void スコープ別フィードを取得できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.findFeedByScopeType(eq("PUBLIC"), eq(0L), any(PageRequest.class)))
                    .willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.getFeed("PUBLIC", 0L, 10);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: sizeが0以下の場合はデフォルトサイズ20件で取得する")
        void sizeが0以下の場合はデフォルトサイズで取得する() {
            // given
            given(postRepository.findFeedByScopeType(eq("PUBLIC"), eq(0L), any(PageRequest.class)))
                    .willReturn(List.of());
            given(timelineMapper.toPostResponseList(any())).willReturn(List.of());

            // when
            timelinePostService.getFeed("PUBLIC", 0L, 0);

            // then
            verify(postRepository).findFeedByScopeType(eq("PUBLIC"), eq(0L), eq(PageRequest.of(0, 20)));
        }
    }

    // ========================================
    // togglePin
    // ========================================
    @Nested
    @DisplayName("togglePin")
    class TogglePin {

        @Test
        @DisplayName("正常系: ピン留め状態を切り替えられる")
        void ピン留め状態を切り替えられる() {
            // given
            TimelinePostEntity post = createPost();
            PostResponse expected = createPostResponse();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(timelineMapper.toPostResponse(any(TimelinePostEntity.class))).willReturn(expected);

            // when
            PostResponse result = timelinePostService.togglePin(POST_ID, true, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }
    }

    // ========================================
    // searchPosts
    // ========================================
    @Nested
    @DisplayName("searchPosts")
    class SearchPosts {

        @Test
        @DisplayName("正常系: キーワードで投稿を検索できる")
        void キーワードで投稿を検索できる() {
            // given
            List<TimelinePostEntity> posts = List.of(createPost());
            List<PostResponse> expected = List.of(createPostResponse());

            given(postRepository.searchByKeyword(eq("テスト"), eq(10))).willReturn(posts);
            given(timelineMapper.toPostResponseList(posts)).willReturn(expected);

            // when
            List<PostResponse> result = timelinePostService.searchPosts("テスト", 10);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
