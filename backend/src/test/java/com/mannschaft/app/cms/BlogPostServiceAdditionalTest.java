package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostService} 追加単体テスト。未テストのブランチをカバーする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostService 追加単体テスト")
class BlogPostServiceAdditionalTest {

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
    private static final Long ORG_ID = 2L;
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
                null, null, null, null, null, null, null, false, 0);
    }

    // ========================================
    // listByOrganization
    // ========================================

    @Nested
    @DisplayName("listByOrganization")
    class ListByOrganization {

        @Test
        @DisplayName("正常系: 組織別記事一覧が返却される")
        void 組織別一覧_正常_一覧返却() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(ORG_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            Page<BlogPostResponse> result = service.listByOrganization(ORG_ID, pageable);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listByUser
    // ========================================

    @Nested
    @DisplayName("listByUser")
    class ListByUser {

        @Test
        @DisplayName("正常系: ユーザー別記事一覧が返却される")
        void ユーザー別一覧_正常_一覧返却() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            Page<BlogPostResponse> result = service.listByUser(USER_ID, pageable);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getBySlug - organization scope
    // ========================================

    @Nested
    @DisplayName("getBySlug 追加ブランチ")
    class GetBySlugAdditional {

        @Test
        @DisplayName("正常系: 組織スコープでslug検索_記事が返却される")
        void 組織スコープ_slug検索_記事返却() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findByOrganizationIdAndSlug(ORG_ID, "test-slug")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.getBySlug(null, ORG_ID, null, "test-slug");

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: ユーザースコープでslug検索_記事が返却される")
        void ユーザースコープ_slug検索_記事返却() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findByUserIdAndSlug(USER_ID, "my-post")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.getBySlug(null, null, USER_ID, "my-post");

            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // getById
    // ========================================

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("正常系: IDで記事詳細が返却される")
        void IDで記事取得_正常_返却() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.getById(POST_ID);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 記事不在でCMS_001例外")
        void IDで記事取得_不在_例外() {
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(POST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_001"));
        }
    }

    // ========================================
    // createPost - slug from title (Japanese)
    // ========================================

    @Nested
    @DisplayName("createPost 追加ブランチ")
    class CreatePostAdditional {

        @Test
        @DisplayName("正常系: 日本語タイトルからslugが自動生成される（UUID利用）")
        void 作成_日本語タイトル_slugUUID生成() {
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID, null, null, "日本語記事タイトル", null, "本文",
                    null, null, null, null, null, null, null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());

            BlogPostResponse result = service.createPost(USER_ID, request);

            assertThat(result).isNotNull();
            verify(postRepository).save(any(BlogPostEntity.class));
        }

        @Test
        @DisplayName("正常系: postType/visibility/priorityを指定して記事が作成される")
        void 作成_全パラメータ指定_記事保存() {
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    null, ORG_ID, null, "組織記事", "custom-slug", "長い本文".repeat(100),
                    "excerpt", "url", "ANNOUNCEMENT", "PUBLIC", "IMPORTANT",
                    null, null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());

            BlogPostResponse result = service.createPost(USER_ID, request);

            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updatePost - tag re-linking
    // ========================================

    @Nested
    @DisplayName("updatePost 追加ブランチ")
    class UpdatePostAdditional {

        @Test
        @DisplayName("正常系: タグ指定の更新でタグが再紐付けされる")
        void 更新_タグ指定_タグ再紐付け() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            com.mannschaft.app.cms.dto.UpdateBlogPostRequest request =
                    new com.mannschaft.app.cms.dto.UpdateBlogPostRequest(
                            "更新タイトル", null, "更新本文", null, null, null, null,
                            List.of(3L, 4L), null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.updatePost(POST_ID, USER_ID, request);

            verify(postTagRepository).deleteByBlogPostId(POST_ID);
            verify(postTagRepository, org.mockito.Mockito.times(2)).save(any(BlogPostTagEntity.class));
        }

        @Test
        @DisplayName("正常系: PUBLISHED記事の更新でリビジョン数が10件以上の場合最古が削除される")
        void 更新_公開済み_リビジョン10件超_最古削除() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.countByBlogPostId(any())).willReturn(10L);
            BlogPostRevisionEntity oldest = BlogPostRevisionEntity.builder()
                    .blogPostId(POST_ID).revisionNumber(1).title("旧").body("旧本文").build();
            given(revisionRepository.findFirstByBlogPostIdOrderByRevisionNumberAsc(any()))
                    .willReturn(Optional.of(oldest));
            com.mannschaft.app.cms.dto.UpdateBlogPostRequest request =
                    new com.mannschaft.app.cms.dto.UpdateBlogPostRequest(
                            "更新タイトル", null, "更新本文", null, null, null, null, null, null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.updatePost(POST_ID, USER_ID, request);

            verify(revisionRepository).delete(oldest);
        }
    }

    // ========================================
    // changeStatus - additional branches
    // ========================================

    @Nested
    @DisplayName("changeStatus 追加ブランチ")
    class ChangeStatusAdditional {

        @Test
        @DisplayName("正常系: 却下理由あり_記事が却下される")
        void ステータス変更_却下_理由あり_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            PublishRequest request = new PublishRequest("REJECTED", null, "内容不備のため");
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.changeStatus(POST_ID, request);

            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(PostStatus.REJECTED);
        }

        @Test
        @DisplayName("正常系: ARCHIVED → DRAFT への変更")
        void ステータス変更_DRAFT_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.ARCHIVED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            PublishRequest request = new PublishRequest("DRAFT", null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.changeStatus(POST_ID, request);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: publishedAt を指定して公開する")
        void ステータス変更_公開日時指定_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            LocalDateTime publishAt = LocalDateTime.of(2026, 4, 1, 9, 0);
            PublishRequest request = new PublishRequest("PUBLISHED", publishAt, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.changeStatus(POST_ID, request);

            assertThat(entity.getPublishedAt()).isEqualTo(publishAt);
        }
    }

    // ========================================
    // issuePreviewToken - success path
    // ========================================

    @Nested
    @DisplayName("issuePreviewToken 追加")
    class IssuePreviewTokenAdditional {

        @Test
        @DisplayName("正常系: ドラフト記事にプレビュートークンが発行される")
        void プレビュートークン_ドラフト_正常発行() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.issuePreviewToken(POST_ID);

            assertThat(result).isNotNull();
            assertThat(entity.getPreviewToken()).isNotNull();
        }
    }

    // ========================================
    // revokePreviewToken
    // ========================================

    @Nested
    @DisplayName("revokePreviewToken")
    class RevokePreviewToken {

        @Test
        @DisplayName("正常系: プレビュートークンが無効化される")
        void プレビュートークン無効化_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            service.revokePreviewToken(POST_ID);

            verify(postRepository).save(entity);
        }
    }

    // ========================================
    // autoSave - branches
    // ========================================

    @Nested
    @DisplayName("autoSave")
    class AutoSave {

        @Test
        @DisplayName("正常系: タイトルとbody両方指定の自動保存")
        void 自動保存_タイトルbody両方_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            AutoSaveRequest request = new AutoSaveRequest("新タイトル", "新本文", "新抜粋", null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.autoSave(POST_ID, USER_ID, request);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: bodyのみ指定の自動保存")
        void 自動保存_bodyのみ_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            AutoSaveRequest request = new AutoSaveRequest(null, "新本文のみ", null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.autoSave(POST_ID, USER_ID, request);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: タイトルもbodyもnullの自動保存（何もしない）")
        void 自動保存_全null_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            AutoSaveRequest request = new AutoSaveRequest(null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.autoSave(POST_ID, USER_ID, request);

            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // bulkAction - additional branches
    // ========================================

    @Nested
    @DisplayName("bulkAction 追加ブランチ")
    class BulkActionAdditional {

        @Test
        @DisplayName("正常系: ARCHIVE操作でPUBLISHED記事がARCHIVEDになる")
        void 一括操作_ARCHIVE_正常() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "ARCHIVE");
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getProcessedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: ARCHIVE操作でDRAFT記事はスキップされる")
        void 一括操作_ARCHIVE_DRAFT_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "ARCHIVE");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getProcessedCount()).isEqualTo(0);
            assertThat(result.getSkippedIds()).contains(1L);
        }

        @Test
        @DisplayName("正常系: PUBLISH操作でDRAFT記事が公開される")
        void 一括操作_PUBLISH_正常() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "PUBLISH");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getProcessedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: PUBLISH操作でPUBLISHED記事はスキップされる")
        void 一括操作_PUBLISH_既公開_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "PUBLISH");
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getSkippedIds()).contains(1L);
        }

        @Test
        @DisplayName("正常系: 存在しないIDがスキップされる")
        void 一括操作_存在しないID_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(99L), "DELETE");
            given(postRepository.findById(99L)).willReturn(Optional.empty());

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getProcessedCount()).isEqualTo(0);
            assertThat(result.getSkippedIds()).contains(99L);
        }

        @Test
        @DisplayName("正常系: 不明なアクションがスキップされる")
        void 一括操作_不明アクション_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "UNKNOWN");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request);

            assertThat(result.getSkippedIds()).contains(1L);
        }
    }

    // ========================================
    // listPublicPostsForFeed
    // ========================================

    @Nested
    @DisplayName("listPublicPostsForFeed")
    class ListPublicPostsForFeed {

        @Test
        @DisplayName("正常系: チームIDで公開記事が取得される")
        void フィード用記事取得_チームID_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findTop20ByTeamIdAndStatusAndVisibilityOrderByPublishedAtDesc(
                    TEAM_ID, PostStatus.PUBLISHED, Visibility.PUBLIC)).willReturn(List.of(entity));
            given(cmsMapper.toBlogPostResponseList(any())).willReturn(List.of(createPostResponse()));

            List<BlogPostResponse> result = service.listPublicPostsForFeed(TEAM_ID, null);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 組織IDで公開記事が取得される")
        void フィード用記事取得_組織ID_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findTop20ByOrganizationIdAndStatusAndVisibilityOrderByPublishedAtDesc(
                    ORG_ID, PostStatus.PUBLISHED, Visibility.PUBLIC)).willReturn(List.of(entity));
            given(cmsMapper.toBlogPostResponseList(any())).willReturn(List.of(createPostResponse()));

            List<BlogPostResponse> result = service.listPublicPostsForFeed(null, ORG_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // sharePost - organization scope
    // ========================================

    @Nested
    @DisplayName("sharePost 追加ブランチ")
    class SharePostAdditional {

        @Test
        @DisplayName("正常系: 組織スコープで記事が共有される")
        void 共有_組織スコープ_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(shareRepository.findByBlogPostIdAndOrganizationId(POST_ID, ORG_ID))
                    .willReturn(Optional.empty());
            BlogPostShareEntity share = BlogPostShareEntity.builder()
                    .blogPostId(POST_ID).organizationId(ORG_ID).sharedBy(USER_ID).build();
            given(shareRepository.save(any())).willReturn(share);

            SharePostRequest request = new SharePostRequest(null, ORG_ID);
            SharePostResponse result = service.sharePost(POST_ID, USER_ID, request);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 組織スコープで重複共有でCMS_013例外")
        void 共有_組織スコープ_重複_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(shareRepository.findByBlogPostIdAndOrganizationId(POST_ID, ORG_ID))
                    .willReturn(Optional.of(BlogPostShareEntity.builder().build()));

            SharePostRequest request = new SharePostRequest(null, ORG_ID);

            assertThatThrownBy(() -> service.sharePost(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_013"));
        }
    }

    // ========================================
    // revokeShare - wrong post
    // ========================================

    @Nested
    @DisplayName("revokeShare 追加ブランチ")
    class RevokeShareAdditional {

        @Test
        @DisplayName("異常系: 共有の記事IDが不一致でCMS_019例外")
        void 共有取消_記事ID不一致_例外() {
            // share.blogPostId = 99 ≠ POST_ID = 10
            BlogPostShareEntity share = BlogPostShareEntity.builder()
                    .blogPostId(99L).teamId(1L).sharedBy(USER_ID).build();
            given(shareRepository.findById(5L)).willReturn(Optional.of(share));

            assertThatThrownBy(() -> service.revokeShare(POST_ID, 5L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_019"));
        }

        @Test
        @DisplayName("正常系: 共有が取り消される")
        void 共有取消_正常_削除実行() {
            BlogPostShareEntity share = BlogPostShareEntity.builder()
                    .blogPostId(POST_ID).teamId(1L).sharedBy(USER_ID).build();
            given(shareRepository.findById(5L)).willReturn(Optional.of(share));

            service.revokeShare(POST_ID, 5L);

            verify(shareRepository).delete(share);
        }
    }

    // ========================================
    // selfReview - branches
    // ========================================

    @Nested
    @DisplayName("selfReview 追加ブランチ")
    class SelfReviewAdditional {

        @Test
        @DisplayName("正常系: PUBLISH アクションで公開される")
        void セルフレビュー_PUBLISH_正常() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(TEAM_ID).authorId(USER_ID).title("記事").slug("art").body("本文")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PENDING_SELF_REVIEW)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.selfReview(POST_ID, USER_ID, new SelfReviewRequest("PUBLISH"));

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: DRAFT アクションでドラフトに戻る")
        void セルフレビュー_DRAFT_正常() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(TEAM_ID).authorId(USER_ID).title("記事").slug("art").body("本文")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PENDING_SELF_REVIEW)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.selfReview(POST_ID, USER_ID, new SelfReviewRequest("DRAFT"));

            assertThat(entity.getStatus()).isEqualTo(PostStatus.DRAFT);
        }

        @Test
        @DisplayName("正常系: DELETE アクションで論理削除される")
        void セルフレビュー_DELETE_正常() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(TEAM_ID).authorId(USER_ID).title("記事").slug("art").body("本文")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PENDING_SELF_REVIEW)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.selfReview(POST_ID, USER_ID, new SelfReviewRequest("DELETE"));

            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 不明アクションでCMS_008例外")
        void セルフレビュー_不明アクション_例外() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .teamId(TEAM_ID).authorId(USER_ID).title("記事").slug("art").body("本文")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PENDING_SELF_REVIEW)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.selfReview(POST_ID, USER_ID, new SelfReviewRequest("UNKNOWN")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_008"));
        }
    }

    // ========================================
    // restoreRevision - success path
    // ========================================

    @Nested
    @DisplayName("restoreRevision 追加ブランチ")
    class RestoreRevisionAdditional {

        @Test
        @DisplayName("正常系: リビジョンから記事が復元される")
        void 復元_正常_DRAFT化() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            BlogPostRevisionEntity revision = BlogPostRevisionEntity.builder()
                    .blogPostId(POST_ID).revisionNumber(2).title("旧タイトル").body("旧本文").editorId(USER_ID).build();
            given(revisionRepository.findById(5L)).willReturn(Optional.of(revision));
            given(revisionRepository.countByBlogPostId(any())).willReturn(1L);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.restoreRevision(POST_ID, 5L, USER_ID);

            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(PostStatus.DRAFT);
        }
    }

    // ========================================
    // duplicatePost - with tags
    // ========================================

    @Nested
    @DisplayName("duplicatePost 追加ブランチ")
    class DuplicatePostAdditional {

        @Test
        @DisplayName("正常系: タグ付き記事の複製でタグもコピーされる")
        void 複製_タグあり_タグコピー() {
            BlogPostEntity original = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(original));
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(original);
            BlogPostTagEntity tag = new BlogPostTagEntity(POST_ID, 5L);
            given(postTagRepository.findByBlogPostId(POST_ID)).willReturn(List.of(tag));
            given(cmsMapper.toBlogPostResponse(any())).willReturn(createPostResponse());

            service.duplicatePost(POST_ID, USER_ID);

            verify(postTagRepository, org.mockito.Mockito.atLeastOnce()).save(any(BlogPostTagEntity.class));
        }
    }

    // ========================================
    // getBySlugWithPreviewToken
    // ========================================

    @Nested
    @DisplayName("getBySlugWithPreviewToken")
    class GetBySlugWithPreviewToken {

        @Test
        @DisplayName("正常系: プレビュートークン付きでslug検索_記事が返却される")
        void プレビュートークン付きslug検索_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "preview-post")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.getBySlugWithPreviewToken(TEAM_ID, null, null, "preview-post", "token123");

            assertThat(result).isNotNull();
        }
    }
}
