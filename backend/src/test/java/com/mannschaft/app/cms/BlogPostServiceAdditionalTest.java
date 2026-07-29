package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
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
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostService}（ファサード）追加単体テスト。未テストのブランチをカバーする。
 *
 * <p>リファクタリング第10弾以降、リビジョン/共有/プレビュー詳細は
 * {@link BlogPostRevisionServiceTest} / {@link BlogPostShareServiceTest} に分離済み。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostService 追加単体テスト")
class BlogPostServiceAdditionalTest {

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
    private static final Long ORG_ID = 2L;
    private static final String ORG_ID_STR = ORG_ID.toString();
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
    // listByOrganization
    // ========================================

    @Nested
    @DisplayName("listByOrganization")
    class ListByOrganization {

        @Test
        @DisplayName("正常系: 組織別記事一覧が返却される（Long文字列）")
        void 組織別一覧_正常_一覧返却() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(ORG_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            // Long文字列で渡す（後方互換）
            Page<BlogPostResponse> result;
            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> su =
                    Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                su.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                result = service.listByOrganization(ORG_ID_STR, pageable);
            }

            assertThat(result).hasSize(1);
            verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }
    }

    // ========================================
    // listByUser
    // ========================================

    @Nested
    @DisplayName("listByUser")
    class ListByUser {

        @Test
        @DisplayName("正常系: ユーザー別記事一覧が返却される（可視性フィルタ通過分）")
        void ユーザー別一覧_正常_一覧返却() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", POST_ID);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());
            given(contentVisibilityChecker.filterAccessible(ReferenceType.BLOG_POST, Set.of(POST_ID), USER_ID))
                    .willReturn(Set.of(POST_ID));

            Page<BlogPostResponse> result;
            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> su =
                    Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                su.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);
                result = service.listByUser(USER_ID, pageable);
            }

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("認可: 可視性フィルタで除外された記事は一覧に出ない（下書き漏洩根治）")
        void ユーザー別一覧_非公開記事は除外() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", POST_ID);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable)).willReturn(page);
            given(contentVisibilityChecker.filterAccessible(ReferenceType.BLOG_POST, Set.of(POST_ID), 999L))
                    .willReturn(Set.of());

            Page<BlogPostResponse> result;
            try (org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> su =
                    Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class)) {
                su.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserIdOrNull).thenReturn(999L);
                result = service.listByUser(USER_ID, pageable);
            }

            assertThat(result.getContent()).isEmpty();
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
        @DisplayName("正常系: ContentVisibilityChecker 通過後IDで記事詳細が返却される")
        void IDで記事取得_正常_返却() {
            // F00 Phase B: assertCanView は通る (void なので何もしない = pass)
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.getById(POST_ID);

            assertThat(result).isNotNull();
            verify(contentVisibilityChecker).assertCanView(
                    org.mockito.ArgumentMatchers.eq(ReferenceType.BLOG_POST),
                    org.mockito.ArgumentMatchers.eq(POST_ID),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("異常系: ContentVisibilityChecker が NOT_FOUND を投げると VISIBILITY_004")
        void IDで記事取得_不在_VISIBILITY004() {
            // F00 Phase B: 実存確認は ContentVisibilityChecker が担う。
            //   不在 → assertCanView 内で VisibilityErrorCode.VISIBILITY_004 (404) をスロー。
            Mockito.doThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_004))
                    .when(contentVisibilityChecker).assertCanView(
                            org.mockito.ArgumentMatchers.eq(ReferenceType.BLOG_POST),
                            org.mockito.ArgumentMatchers.eq(POST_ID),
                            org.mockito.ArgumentMatchers.any());

            assertThatThrownBy(() -> service.getById(POST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("VISIBILITY_004"));
        }

        @Test
        @DisplayName("異常系: ContentVisibilityChecker が権限不足を投げると VISIBILITY_001")
        void IDで記事取得_権限不足_VISIBILITY001() {
            Mockito.doThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                    .when(contentVisibilityChecker).assertCanView(
                            org.mockito.ArgumentMatchers.eq(ReferenceType.BLOG_POST),
                            org.mockito.ArgumentMatchers.eq(POST_ID),
                            org.mockito.ArgumentMatchers.any());

            assertThatThrownBy(() -> service.getById(POST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("VISIBILITY_001"));
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
                    TEAM_ID_STR, null, null, "日本語記事タイトル", null, "本文",
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
                    null, ORG_ID_STR, null, "組織記事", "custom-slug", "長い本文".repeat(100),
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

            BlogPostResponse result = service.changeStatus(POST_ID, USER_ID, request);

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

            BlogPostResponse result = service.changeStatus(POST_ID, USER_ID, request);

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

            service.changeStatus(POST_ID, USER_ID, request);

            assertThat(entity.getPublishedAt()).isEqualTo(publishAt);
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

            BulkActionResponse result = service.bulkAction(request, USER_ID);

            assertThat(result.getProcessedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: ARCHIVE操作でDRAFT記事はスキップされる")
        void 一括操作_ARCHIVE_DRAFT_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "ARCHIVE");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request, USER_ID);

            assertThat(result.getProcessedCount()).isEqualTo(0);
            assertThat(result.getSkippedIds()).contains(1L);
        }

        @Test
        @DisplayName("正常系: PUBLISH操作でDRAFT記事が公開される")
        void 一括操作_PUBLISH_正常() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "PUBLISH");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request, USER_ID);

            assertThat(result.getProcessedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: PUBLISH操作でPUBLISHED記事はスキップされる")
        void 一括操作_PUBLISH_既公開_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "PUBLISH");
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request, USER_ID);

            assertThat(result.getSkippedIds()).contains(1L);
        }

        @Test
        @DisplayName("正常系: 存在しないIDがスキップされる")
        void 一括操作_存在しないID_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(99L), "DELETE");
            given(postRepository.findById(99L)).willReturn(Optional.empty());

            BulkActionResponse result = service.bulkAction(request, USER_ID);

            assertThat(result.getProcessedCount()).isEqualTo(0);
            assertThat(result.getSkippedIds()).contains(99L);
        }

        @Test
        @DisplayName("正常系: 不明なアクションがスキップされる")
        void 一括操作_不明アクション_スキップ() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L), "UNKNOWN");
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(1L)).willReturn(Optional.of(entity));

            BulkActionResponse result = service.bulkAction(request, USER_ID);

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
        @DisplayName("正常系: チームIDでContentVisibilityCheckerが通過した記事のみ返る")
        void フィード用記事取得_チームID_Checker経由_正常() {
            // Given
            BlogPostEntity pub = createPostEntity(PostStatus.PUBLISHED);
            BlogPostEntity priv = createPostEntity(PostStatus.PUBLISHED);
            org.springframework.test.util.ReflectionTestUtils.setField(pub, "id", 1L);
            org.springframework.test.util.ReflectionTestUtils.setField(priv, "id", 2L);

            given(postRepository.findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
                    TEAM_ID, PostStatus.PUBLISHED)).willReturn(List.of(pub, priv));
            // Checker: 1L(PUBLIC)のみ通過、2L(MEMBERS_ONLY)は拒否
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.BLOG_POST, Set.of(1L, 2L), null)).willReturn(Set.of(1L));
            given(cmsMapper.toBlogPostResponseList(List.of(pub))).willReturn(List.of(createPostResponse()));

            // When
            List<BlogPostResponse> result = service.listPublicPostsForFeed(TEAM_ID, null);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 組織IDでContentVisibilityCheckerが通過した記事のみ返る")
        void フィード用記事取得_組織ID_Checker経由_正常() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", 10L);

            given(postRepository.findTop20ByOrganizationIdAndStatusOrderByPublishedAtDesc(
                    ORG_ID, PostStatus.PUBLISHED)).willReturn(List.of(entity));
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.BLOG_POST, Set.of(10L), null)).willReturn(Set.of(10L));
            given(cmsMapper.toBlogPostResponseList(List.of(entity))).willReturn(List.of(createPostResponse()));

            // When
            List<BlogPostResponse> result = service.listPublicPostsForFeed(null, ORG_ID);

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
            org.mockito.Mockito.verify(contentVisibilityChecker, org.mockito.Mockito.never())
                    .filterAccessible(any(), any(), any());
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
