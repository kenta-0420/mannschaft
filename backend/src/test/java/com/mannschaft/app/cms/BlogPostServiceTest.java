package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostService 単体テスト")
class BlogPostServiceTest {

    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private BlogPostTagRepository postTagRepository;
    @Mock
    private BlogPostRevisionRepository revisionRepository;
    @Mock
    private BlogPostShareRepository shareRepository;
    @Mock
    private CmsMapper cmsMapper;

    @InjectMocks
    private BlogPostService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long POST_ID = 10L;

    private BlogPostEntity createPostEntity(PostStatus status) {
        return BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(USER_ID)
                .title("テスト記事")
                .slug("test-article")
                .body("テスト本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.MEMBERS_ONLY)
                .priority(PostPriority.NORMAL)
                .status(status)
                .readingTimeMinutes((short) 1)
                .build();
    }

    private BlogPostResponse createPostResponse() {
        return new BlogPostResponse(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    // ========================================
    // listByTeam
    // ========================================

    @Nested
    @DisplayName("listByTeam")
    class ListByTeam {

        @Test
        @DisplayName("正常系: チーム別記事一覧が返却される")
        void チーム別一覧_正常_一覧返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            // When
            Page<BlogPostResponse> result = service.listByTeam(TEAM_ID, pageable);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getBySlug
    // ========================================

    @Nested
    @DisplayName("getBySlug")
    class GetBySlug {

        @Test
        @DisplayName("正常系: チームスコープでslug検索_記事が返却される")
        void チームスコープ_slug検索_記事返却() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "test-slug")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When
            BlogPostResponse result = service.getBySlug(TEAM_ID, null, null, "test-slug");

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 記事不在でCMS_001例外")
        void 記事不在_例外() {
            // Given
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "no-exist")).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getBySlug(TEAM_ID, null, null, "no-exist"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_001"));
        }
    }

    // ========================================
    // createPost
    // ========================================

    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("正常系: 記事が作成される")
        void 作成_正常_記事保存() {
            // Given
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID, null, null, "新規記事", null, "本文テスト",
                    null, null, null, null, null, null, null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());

            // When
            BlogPostResponse result = service.createPost(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(postRepository).save(any(BlogPostEntity.class));
        }

        @Test
        @DisplayName("正常系: タグ付き記事の作成でタグが紐付けされる")
        void 作成_タグ付き_タグ紐付け() {
            // Given
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID, null, null, "タグ付き記事", null, "本文",
                    null, null, null, null, null, List.of(1L, 2L), null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());

            // When
            service.createPost(USER_ID, request);

            // Then
            verify(postTagRepository, org.mockito.Mockito.times(2)).save(any(BlogPostTagEntity.class));
        }
    }

    // ========================================
    // updatePost
    // ========================================

    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("正常系: 記事が更新される")
        void 更新_正常_記事保存() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "更新タイトル", null, "更新本文", null, null, null, null, null, null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When
            BlogPostResponse result = service.updatePost(POST_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: PUBLISHED記事の更新でリビジョンが保存される")
        void 更新_公開済み_リビジョン保存() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.countByBlogPostId(any())).willReturn(0L);
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "更新タイトル", null, "更新本文", null, null, null, null, null, null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When
            service.updatePost(POST_ID, USER_ID, request);

            // Then
            verify(revisionRepository).save(any(BlogPostRevisionEntity.class));
        }
    }

    // ========================================
    // changeStatus
    // ========================================

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系: 記事が公開される")
        void ステータス変更_公開_正常() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            PublishRequest request = new PublishRequest("PUBLISHED", null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When
            BlogPostResponse result = service.changeStatus(POST_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 却下理由なしでCMS_014例外")
        void ステータス変更_却下_理由なし_例外() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            PublishRequest request = new PublishRequest("REJECTED", null, null);

            // When / Then
            assertThatThrownBy(() -> service.changeStatus(POST_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_014"));
        }
    }

    // ========================================
    // deletePost
    // ========================================

    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("正常系: 記事が論理削除される")
        void 削除_正常_論理削除() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            // When
            service.deletePost(POST_ID);

            // Then
            verify(postRepository).save(entity);
        }
    }

    // ========================================
    // duplicatePost
    // ========================================

    @Nested
    @DisplayName("duplicatePost")
    class DuplicatePost {

        @Test
        @DisplayName("正常系: 記事が複製される")
        void 複製_正常_新記事作成() {
            // Given
            BlogPostEntity original = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(original));
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(original);
            given(postTagRepository.findByBlogPostId(POST_ID)).willReturn(List.of());
            given(cmsMapper.toBlogPostResponse(any())).willReturn(createPostResponse());

            // When
            BlogPostResponse result = service.duplicatePost(POST_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // listRevisions
    // ========================================

    @Nested
    @DisplayName("listRevisions")
    class ListRevisions {

        @Test
        @DisplayName("正常系: リビジョン一覧が返却される")
        void リビジョン一覧_正常_返却() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.findByBlogPostIdOrderByCreatedAtDesc(POST_ID)).willReturn(List.of());
            given(cmsMapper.toRevisionResponseList(any())).willReturn(List.of());

            // When
            List<RevisionResponse> result = service.listRevisions(POST_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // restoreRevision
    // ========================================

    @Nested
    @DisplayName("restoreRevision")
    class RestoreRevision {

        @Test
        @DisplayName("異常系: リビジョン不在でCMS_004例外")
        void 復元_リビジョン不在_例外() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.findById(99L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.restoreRevision(POST_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_004"));
        }
    }

    // ========================================
    // issuePreviewToken
    // ========================================

    @Nested
    @DisplayName("issuePreviewToken")
    class IssuePreviewToken {

        @Test
        @DisplayName("異常系: 公開済み記事でCMS_010例外")
        void プレビュートークン_公開済み_例外() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.issuePreviewToken(POST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_010"));
        }
    }

    // ========================================
    // bulkAction
    // ========================================

    @Nested
    @DisplayName("bulkAction")
    class BulkAction {

        @Test
        @DisplayName("異常系: 50件超でCMS_016例外")
        void 一括操作_上限超過_例外() {
            // Given
            List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList();
            BulkActionRequest request = new BulkActionRequest(ids, null);

            // When / Then
            assertThatThrownBy(() -> service.bulkAction(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_016"));
        }

        @Test
        @DisplayName("正常系: DELETE操作で記事が論理削除される")
        void 一括操作_DELETE_論理削除() {
            // Given
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "DELETE");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            // When
            BulkActionResponse result = service.bulkAction(request);

            // Then
            assertThat(result.getProcessedCount()).isEqualTo(1);
        }
    }

    // ========================================
    // sharePost
    // ========================================

    @Nested
    @DisplayName("sharePost")
    class SharePost {

        @Test
        @DisplayName("異常系: ソーシャルプロフィール記事の共有でCMS_012例外")
        void 共有_ソーシャルプロフィール_例外() {
            // Given
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(TEAM_ID)
                    .authorId(USER_ID)
                    .title("テスト記事")
                    .slug("test")
                    .body("本文")
                    .socialProfileId(5L)
                    .postType(PostType.BLOG)
                    .visibility(Visibility.MEMBERS_ONLY)
                    .status(PostStatus.PUBLISHED)
                    .readingTimeMinutes((short) 1)
                    .build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            SharePostRequest request = new SharePostRequest(2L, null);

            // When / Then
            assertThatThrownBy(() -> service.sharePost(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_012"));
        }

        @Test
        @DisplayName("異常系: 重複共有でCMS_013例外")
        void 共有_重複_例外() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            SharePostRequest request = new SharePostRequest(2L, null);
            given(shareRepository.findByBlogPostIdAndTeamId(POST_ID, 2L))
                    .willReturn(Optional.of(BlogPostShareEntity.builder().build()));

            // When / Then
            assertThatThrownBy(() -> service.sharePost(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_013"));
        }
    }

    // ========================================
    // revokeShare
    // ========================================

    @Nested
    @DisplayName("revokeShare")
    class RevokeShare {

        @Test
        @DisplayName("異常系: 共有不在でCMS_019例外")
        void 共有取消_不在_例外() {
            // Given
            given(shareRepository.findById(99L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.revokeShare(POST_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_019"));
        }
    }

    // ========================================
    // selfReview
    // ========================================

    @Nested
    @DisplayName("selfReview")
    class SelfReview {

        @Test
        @DisplayName("異常系: PENDING_SELF_REVIEW以外のステータスでCMS_008例外")
        void セルフレビュー_不正ステータス_例外() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            SelfReviewRequest request = new SelfReviewRequest("PUBLISH");

            // When / Then
            assertThatThrownBy(() -> service.selfReview(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_008"));
        }
    }
}
