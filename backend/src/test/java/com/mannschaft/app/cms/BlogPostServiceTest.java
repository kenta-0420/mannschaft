package com.mannschaft.app.cms;

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
import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.cms.service.BlogPostRevisionService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogPostShareService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.test.util.ReflectionTestUtils;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostService}（ファサード）の単体テスト。
 *
 * <p>リファクタリング第10弾以降、リビジョン/共有/プレビュートークン系のテストは
 * {@link BlogPostRevisionServiceTest} / {@link BlogPostShareServiceTest} に分離。
 * 本クラスはファサードの CRUD・ステータス制御・委譲動作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostService 単体テスト")
class BlogPostServiceTest {

    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private BlogPostTagRepository postTagRepository;
    @Mock
    private CmsMapper cmsMapper;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;
    @Mock
    private BlogPostRevisionService revisionService;
    @Mock
    private BlogPostShareService shareService;
    @Mock
    private PostAuthorSnapshotService postAuthorSnapshotService;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private BlogPostService service;

    private static final Long TEAM_ID = 1L;
    private static final String TEAM_ID_STR = TEAM_ID.toString();
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
        return BlogPostResponse.builder()
                .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                .build();
    }

    // ========================================
    // listByTeam
    // ========================================

    @Nested
    @DisplayName("listByTeam")
    class ListByTeam {

        @Test
        @DisplayName("正常系: チーム別記事一覧が返却される（Long文字列）")
        void チーム別一覧_正常_一覧返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            // When: Long文字列で渡す（後方互換）
            Page<BlogPostResponse> result = service.listByTeam(TEAM_ID_STR, pageable);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: UUID文字列でチーム別記事一覧が返却される")
        void チーム別一覧_UUID文字列_正常() {
            // Given
            String teamUuid = "01961234-5678-7000-9abc-def012345678";
            java.util.UUID uuid = java.util.UUID.fromString(teamUuid);
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));

            TeamEntity mockTeam = TeamEntity.builder().build();
            org.springframework.test.util.ReflectionTestUtils.setField(mockTeam, "id", TEAM_ID);
            given(teamRepository.findByPublicId(uuid)).willReturn(java.util.Optional.of(mockTeam));
            given(postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            // When: UUID文字列で渡す
            Page<BlogPostResponse> result = service.listByTeam(teamUuid, pageable);

            // Then
            assertThat(result).hasSize(1);
            verify(teamRepository).findByPublicId(uuid);
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
        @DisplayName("正常系: 記事が作成される（Long文字列teamId）")
        void 作成_正常_記事保存() {
            // Given: Long文字列形式（後方互換）
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID_STR, null, null, "新規記事", null, "本文テスト",
                    null, null, null, null, null, null, null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());
            // accessControlService.checkMembership はモック（void なので stubbing 不要）

            // When
            BlogPostResponse result = service.createPost(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(postRepository).save(any(BlogPostEntity.class));
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: タグ付き記事の作成でタグが紐付けされる")
        void 作成_タグ付き_タグ紐付け() {
            // Given
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID_STR, null, null, "タグ付き記事", null, "本文",
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
        @DisplayName("正常系: PUBLISHED記事の更新で revisionService.saveRevision に委譲される")
        void 更新_公開済み_リビジョン委譲() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "更新タイトル", null, "更新本文", null, null, null, null, null, null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When
            service.updatePost(POST_ID, USER_ID, request);

            // Then
            verify(revisionService).saveRevision(entity, USER_ID);
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
    // listRevisions / restoreRevision / issuePreviewToken / revokePreviewToken
    // sharePost / revokeShare — ファサードの委譲動作のみ確認。
    // 詳細な振る舞いテストは BlogPostRevisionServiceTest / BlogPostShareServiceTest を参照。
    // ========================================

    @Nested
    @DisplayName("リビジョン系委譲")
    class RevisionDelegation {

        @Test
        @DisplayName("listRevisions: revisionService に委譲される")
        void リビジョン一覧_委譲() {
            List<RevisionResponse> expected = List.of();
            given(revisionService.listRevisions(POST_ID)).willReturn(expected);

            List<RevisionResponse> result = service.listRevisions(POST_ID);

            assertThat(result).isSameAs(expected);
            verify(revisionService).listRevisions(POST_ID);
        }

        @Test
        @DisplayName("restoreRevision: revisionService に委譲される")
        void 復元_委譲() {
            BlogPostResponse expected = createPostResponse();
            given(revisionService.restoreRevision(POST_ID, 5L, USER_ID)).willReturn(expected);

            BlogPostResponse result = service.restoreRevision(POST_ID, 5L, USER_ID);

            assertThat(result).isSameAs(expected);
            verify(revisionService).restoreRevision(POST_ID, 5L, USER_ID);
        }
    }

    @Nested
    @DisplayName("共有・プレビュー系委譲")
    class ShareDelegation {

        @Test
        @DisplayName("issuePreviewToken: shareService に委譲される")
        void プレビュートークン発行_委譲() {
            BlogPostResponse expected = createPostResponse();
            given(shareService.issuePreviewToken(POST_ID)).willReturn(expected);

            BlogPostResponse result = service.issuePreviewToken(POST_ID);

            assertThat(result).isSameAs(expected);
            verify(shareService).issuePreviewToken(POST_ID);
        }

        @Test
        @DisplayName("revokePreviewToken: shareService に委譲される")
        void プレビュートークン無効化_委譲() {
            service.revokePreviewToken(POST_ID);

            verify(shareService).revokePreviewToken(POST_ID);
        }

        @Test
        @DisplayName("sharePost: shareService に委譲される")
        void 共有_委譲() {
            SharePostRequest req = new SharePostRequest(2L, null);
            SharePostResponse expected = new SharePostResponse(1L, POST_ID, 2L, null);
            given(shareService.sharePost(POST_ID, USER_ID, req)).willReturn(expected);

            SharePostResponse result = service.sharePost(POST_ID, USER_ID, req);

            assertThat(result).isSameAs(expected);
            verify(shareService).sharePost(POST_ID, USER_ID, req);
        }

        @Test
        @DisplayName("revokeShare: shareService に委譲される")
        void 共有取消_委譲() {
            service.revokeShare(POST_ID, 5L);

            verify(shareService).revokeShare(POST_ID, 5L);
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

    // ========================================
    // listPublicPostsForFeed
    // ========================================

    @Nested
    @DisplayName("listPublicPostsForFeed")
    class ListPublicPostsForFeed {

        @Test
        @DisplayName("正常系: ContentVisibilityChecker が通過した記事のみ返る")
        void フィード用記事取得_Checker通過のみ返却() {
            // Given
            BlogPostEntity pub = createPostEntity(PostStatus.PUBLISHED);
            BlogPostEntity priv = createPostEntity(PostStatus.PUBLISHED);
            ReflectionTestUtils.setField(pub, "id", 1L);
            ReflectionTestUtils.setField(priv, "id", 2L);

            given(postRepository.findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
                    TEAM_ID, PostStatus.PUBLISHED)).willReturn(List.of(pub, priv));
            // Checker: id=1 のみ通過（PUBLIC）、id=2 は拒否（MEMBERS_ONLY 等）
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.BLOG_POST, Set.of(1L, 2L), null)).willReturn(Set.of(1L));
            given(cmsMapper.toBlogPostResponseList(List.of(pub))).willReturn(List.of(createPostResponse()));

            // When
            List<BlogPostResponse> result = service.listPublicPostsForFeed(TEAM_ID, null);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("空ページ: リポジトリが空ならCheckerを呼ばず空リストを返す")
        void フィード用記事取得_空_Checker不呼び出し() {
            // Given
            given(postRepository.findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
                    TEAM_ID, PostStatus.PUBLISHED)).willReturn(List.of());

            // When
            List<BlogPostResponse> result = service.listPublicPostsForFeed(TEAM_ID, null);

            // Then
            assertThat(result).isEmpty();
            verify(contentVisibilityChecker, never()).filterAccessible(any(), any(), any());
        }
    }
}
