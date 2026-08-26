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
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.lenient;
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
    @Mock
    private PaymentGateService paymentGateService;

    @InjectMocks
    private BlogPostService service;

    /**
     * 既存テストは新依存 {@code checkAccess} を stub しないため、既定で「アクセス可（ゲート無し相当）」を返す。
     * これにより「ゲート無し既定＝body 返却」を既存テストが検証する形になる。
     * 各ペイウォール AC テストは {@code given(...)} で個別に override する（lenient なので未使用でも警告にならない）。
     */
    @BeforeEach
    void stubPaywallAccessibleByDefault() {
        lenient().when(paymentGateService.checkAccess(any(), any(), any()))
                .thenReturn(new GateCheckResponse(true, false, List.of()));
    }

    private static final Long TEAM_ID = 1L;
    private static final String TEAM_ID_STR = TEAM_ID.toString();
    private static final Long USER_ID = 100L;
    private static final Long POST_ID = 10L;
    /** SecurityUtils.getCurrentUserIdOrNull() をモックする際に返す閲覧者ID（非null定数）。
     * シャード実行順序によらず決定論的な assertCanView 引数を保証するために使用する。 */
    private static final Long VIEWER_ID = 99L;

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
            try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER_ID);
                Page<BlogPostResponse> result = service.listByTeam(TEAM_ID_STR, pageable);

                // Then
                assertThat(result).hasSize(1);
                verify(accessControlService).checkMembership(VIEWER_ID, TEAM_ID, "TEAM");
            }
        }

        @Test
        @DisplayName("正常系: スラッグ文字列でチーム別記事一覧が返却される")
        void チーム別一覧_スラッグ文字列_正常() {
            // Given
            String teamSlug = "fc-tokyo";  // スラッグ文字列
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));

            TeamEntity mockTeam = TeamEntity.builder().build();
            org.springframework.test.util.ReflectionTestUtils.setField(mockTeam, "id", TEAM_ID);
            given(teamRepository.findBySlugAndDeletedAtIsNull(teamSlug)).willReturn(java.util.Optional.of(mockTeam));
            given(postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(createPostResponse());

            // When: スラッグ文字列で渡す
            try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER_ID);
                Page<BlogPostResponse> result = service.listByTeam(teamSlug, pageable);

                // Then
                assertThat(result).hasSize(1);
                verify(teamRepository).findBySlugAndDeletedAtIsNull(teamSlug);
                verify(accessControlService).checkMembership(VIEWER_ID, TEAM_ID, "TEAM");
            }
        }

        @Test
        @DisplayName("認可: 非メンバーは COMMON_002 で拒否される（他チームの下書き列挙禁止）")
        void チーム別一覧_非メンバー拒否() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER_ID);
                org.mockito.BDDMockito.willThrow(
                                new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                        .given(accessControlService)
                        .checkMembership(VIEWER_ID, TEAM_ID, "TEAM");

                // When / Then
                assertThatThrownBy(() -> service.listByTeam(TEAM_ID_STR, pageable))
                        .isInstanceOf(BusinessException.class);
                verify(postRepository, never())
                        .findByTeamIdOrderByPinnedDescCreatedAtDesc(any(), any());
            }
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

        @Test
        @DisplayName("認可: slug解決後にContentVisibilityChecker.assertCanViewが呼ばれる（getByIdと同一挙動）")
        void slug検索_可視性チェック呼び出し() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            ReflectionTestUtils.setField(entity, "id", POST_ID);
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "test-slug")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // SecurityUtils.getCurrentUserIdOrNull() を VIEWER_ID に固定する。
            // フルシャード実行では先行テストが認証済み SecurityContext を残置し得るため、
            // ambient な SecurityContext に依存すると Strict Stub が PotentialStubbingProblem を投げる。
            try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);

                // When
                service.getBySlug(TEAM_ID, null, null, "test-slug");

                // Then: 解決した記事IDと固定 viewerUserId で可視性判定が委譲される
                verify(contentVisibilityChecker).assertCanView(ReferenceType.BLOG_POST, POST_ID, VIEWER_ID);
            }
        }

        @Test
        @DisplayName("認可: 非メンバーが他人のMEMBERS_ONLY記事をslug取得→assertCanViewで弾かれる（漏洩根治）")
        void slug検索_可視性拒否で例外伝播() {
            // Given: MEMBERS_ONLY 記事を slug で引けるが、Checker が VISIBILITY_001（403）で拒否する
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            ReflectionTestUtils.setField(entity, "id", POST_ID);
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "secret-slug")).willReturn(Optional.of(entity));

            // SecurityUtils.getCurrentUserIdOrNull() を VIEWER_ID に固定する。
            // フルシャード実行では先行テストが認証済み SecurityContext を残置し得るため、
            // ambient な SecurityContext に依存すると Strict Stub が PotentialStubbingProblem を投げる。
            try (MockedStatic<SecurityUtils> securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                org.mockito.BDDMockito.willThrow(new BusinessException(
                        com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                        .given(contentVisibilityChecker)
                        .assertCanView(ReferenceType.BLOG_POST, POST_ID, VIEWER_ID);

                // When / Then: 例外がそのまま伝播し、レスポンス生成（漏洩）に到達しない
                assertThatThrownBy(() -> service.getBySlug(TEAM_ID, null, null, "secret-slug"))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("VISIBILITY_001"));
                verify(cmsMapper, never()).toBlogPostResponse(any(BlogPostEntity.class));
            }
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
        @DisplayName("B1: visibility=MEMBERS_AND_ABOVE で作成しても500にならず永続化される（新ラダー値受理）")
        void 作成_新ラダー可視性_500にならず保存() {
            // Given: 可視性ラダー統一(#1341)の新ラダー値名を FE が送る
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID_STR, null, null, "新ラダー記事", null, "本文",
                    null, null, null, "MEMBERS_AND_ABOVE", null, null, null, null, null, null, null);
            BlogPostEntity savedEntity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.save(any(BlogPostEntity.class))).willReturn(savedEntity);
            given(cmsMapper.toBlogPostResponse(savedEntity)).willReturn(createPostResponse());

            // When: 以前は Visibility.valueOf("MEMBERS_AND_ABOVE") が IllegalArgumentException → 500
            BlogPostResponse result = service.createPost(USER_ID, request);

            // Then: 例外なく保存され、entity に新ラダー可視性が設定される
            assertThat(result).isNotNull();
            org.mockito.ArgumentCaptor<BlogPostEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(postRepository).save(captor.capture());
            assertThat(captor.getValue().getVisibility()).isEqualTo(Visibility.MEMBERS_AND_ABOVE);
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
        @DisplayName("B1: visibility=MEMBERS_AND_ABOVE で更新しても500にならず反映される（新ラダー値受理）")
        void 更新_新ラダー可視性_500にならず反映() {
            // Given
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "更新タイトル", null, "更新本文", null, null, "MEMBERS_AND_ABOVE",
                    null, null, null, null, null, null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            // When: 以前は Visibility.valueOf("MEMBERS_AND_ABOVE") が IllegalArgumentException → 500
            BlogPostResponse result = service.updatePost(POST_ID, USER_ID, request);

            // Then: 例外なく更新され、新ラダー可視性が反映される
            assertThat(result).isNotNull();
            assertThat(entity.getVisibility()).isEqualTo(Visibility.MEMBERS_AND_ABOVE);
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

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非所有者かつ非ADMINの更新は403(COMMON_002)")
        void 更新_非所有者非ADMIN_例外() {
            // Given: entity.authorId=USER_ID, teamId=TEAM_ID。実際は非所有者かつ非ADMINなので拒否。
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            Long otherUserId = 999L;
            org.mockito.BDDMockito.willThrow(new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(otherUserId, TEAM_ID, "TEAM");
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "乗っ取りタイトル", null, "本文", null, null, null, null, null, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.updatePost(POST_ID, otherUserId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(postRepository, never()).save(any(BlogPostEntity.class));
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
            BlogPostResponse result = service.changeStatus(POST_ID, USER_ID, request);

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
            assertThatThrownBy(() -> service.changeStatus(POST_ID, USER_ID, request))
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
            service.deletePost(POST_ID, USER_ID);

            // Then
            verify(postRepository).save(entity);
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 個人記事(team/org無し)を非所有者が削除しようとすると403(COMMON_002)")
        void 削除_個人記事_非所有者_例外() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .userId(USER_ID).authorId(USER_ID)
                    .title("個人記事").slug("s").body("b")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.DRAFT)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.deletePost(POST_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(postRepository, never()).save(any(BlogPostEntity.class));
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
            given(shareService.issuePreviewToken(POST_ID, USER_ID)).willReturn(expected);

            BlogPostResponse result = service.issuePreviewToken(POST_ID, USER_ID);

            assertThat(result).isSameAs(expected);
            verify(shareService).issuePreviewToken(POST_ID, USER_ID);
        }

        @Test
        @DisplayName("revokePreviewToken: shareService に委譲される")
        void プレビュートークン無効化_委譲() {
            service.revokePreviewToken(POST_ID, USER_ID);

            verify(shareService).revokePreviewToken(POST_ID, USER_ID);
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
            service.revokeShare(POST_ID, 5L, USER_ID);

            verify(shareService).revokeShare(POST_ID, 5L, USER_ID);
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
            assertThatThrownBy(() -> service.bulkAction(request, USER_ID))
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
            BulkActionResponse result = service.bulkAction(request, USER_ID);

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

    // ========================================
    // ペイウォール本文ゲート（F08.9 漏洩根治）
    // ========================================

    @Nested
    @DisplayName("ペイウォール本文ゲート（getById / getBySlug）")
    class Paywall {

        /** 本文つきレスポンス（cmsMapper のスタブ戻り値）。 */
        private BlogPostResponse responseWithBody() {
            return BlogPostResponse.builder()
                    .id(POST_ID)
                    .scope(new BlogPostResponse.BlogPostScopeDto(TEAM_ID, null, null, USER_ID))
                    .content(new BlogPostResponse.BlogPostContentDto(
                            "タイトル", "slug", "有料本文フルテキスト", "要約プレビュー", "cover.png"))
                    .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                    .build();
        }

        /** id を設定した記事エンティティ（authorId=USER_ID=100）。 */
        private BlogPostEntity postWithId() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            ReflectionTestUtils.setField(entity, "id", POST_ID);
            return entity;
        }

        private void stubGetById(BlogPostEntity entity) {
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(responseWithBody());
        }

        @Test
        @DisplayName("AC-1: ゲート無し（accessible=true）→ body 全文が返る")
        void AC1_ゲート無し_全文() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(true, false, List.of()));

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
            }
        }

        @Test
        @DisplayName("AC-2: ゲート有り・課金済（accessible=true）→ body 全文が返る")
        void AC2_課金済_全文() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(true, false,
                                List.of(new GateCheckResponse.RequiredItem(1L, "月会費", null, true))));

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
            }
        }

        @Test
        @DisplayName("AC-3: ゲート有り・未課金・titleHidden=false → body=null / title・excerpt は残る")
        void AC3_未課金_bodyのみマスク() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(false, false,
                                List.of(new GateCheckResponse.RequiredItem(1L, "月会費", null, false))));

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isNull();
                assertThat(result.getContent().title()).isEqualTo("タイトル");
                assertThat(result.getContent().excerpt()).isEqualTo("要約プレビュー");
                assertThat(result.getContent().coverImageUrl()).isEqualTo("cover.png");
            }
        }

        @Test
        @DisplayName("AC-5: titleHidden=true・未課金（認証cms）→ title=null かつ body=null（200）")
        void AC5_titleHidden_タイトルも本文もマスク() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(false, true, List.of()));

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().title()).isNull();
                assertThat(result.getContent().body()).isNull();
                // excerpt は残す（プレビュー素材）
                assertThat(result.getContent().excerpt()).isEqualTo("要約プレビュー");
            }
        }

        @Test
        @DisplayName("AC-7: 著者本人 → ゲート無視で全文（checkAccess を呼ばない）")
        void AC7_著者本人_全文バイパス() {
            BlogPostEntity entity = postWithId(); // authorId=USER_ID
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(USER_ID);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
                verify(paymentGateService, never()).checkAccess(any(), any(), any());
            }
        }

        @Test
        @DisplayName("AC-8: SystemAdmin → ゲート無視で全文（checkAccess を呼ばない）")
        void AC8_SystemAdmin_全文バイパス() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(true);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
                verify(paymentGateService, never()).checkAccess(any(), any(), any());
            }
        }

        @Test
        @DisplayName("AC-9/AC-12: 判定は受益者キー＝閲覧者IDで行う（checkAccess に viewerUserId が渡る＝check API と同一真実源）")
        void AC9_AC12_受益者キーで判定() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(false, false, List.of()));

                service.getById(POST_ID);

                // 著者(USER_ID)ではなく閲覧者(VIEWER_ID)で判定される＝他人の課金で解錠しない／check と一致
                verify(paymentGateService).checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID);
            }
        }

        @Test
        @DisplayName("AC-10: checkAccess 例外＋ゲート有り → fail-closed（body=null）")
        void AC10_例外時ゲート有り_failClosed() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willThrow(new RuntimeException("判定不能"));
                given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(true);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isNull();
            }
        }

        @Test
        @DisplayName("AC-11: checkAccess 例外＋ゲート無し（非課金記事）→ body は返る")
        void AC11_例外時ゲート無し_body返却() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willThrow(new RuntimeException("判定不能"));
                given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(false);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
            }
        }

        @Test
        @DisplayName("AC-10b: checkAccess が null を返す＋ゲート有り → fail-closed（body=null・NPE 再発防止）")
        void AC10b_null時ゲート有り_failClosed() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(null);
                given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(true);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isNull();
            }
        }

        @Test
        @DisplayName("AC-11b: checkAccess が null を返す＋ゲート無し → body は返る（NPE 再発防止）")
        void AC11b_null時ゲート無し_body返却() {
            BlogPostEntity entity = postWithId();
            stubGetById(entity);
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(null);
                given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(false);

                BlogPostResponse result = service.getById(POST_ID);

                assertThat(result.getContent().body()).isEqualTo("有料本文フルテキスト");
            }
        }

        @Test
        @DisplayName("AC-13: 一覧（listByTeam）→ 全 item の body=null")
        void AC13_一覧_body落とし() {
            Pageable pageable = PageRequest.of(0, 10);
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(entity));
            given(postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(TEAM_ID, pageable)).willReturn(page);
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class))).willReturn(responseWithBody());

            Page<BlogPostResponse> result;
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER_ID);
                result = service.listByTeam(TEAM_ID_STR, pageable);
            }

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getContent().body()).isNull();
            // 一覧でも title / excerpt は残る
            assertThat(result.getContent().get(0).getContent().title()).isEqualTo("タイトル");
        }

        @Test
        @DisplayName("AC-15: preview-token 経路 → ゲート適用（未課金で body=null）")
        void AC15_プレビュートークン経路_ゲート適用() {
            BlogPostEntity entity = postWithId();
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, "slug")).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(responseWithBody());
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                given(accessControlService.isSystemAdmin(VIEWER_ID)).willReturn(false);
                given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, VIEWER_ID))
                        .willReturn(new GateCheckResponse(false, false, List.of()));

                BlogPostResponse result = service.getBySlugWithPreviewToken(
                        TEAM_ID, null, null, "slug", "tok-123");

                assertThat(result.getContent().body()).isNull();
            }
        }

        @Test
        @DisplayName("AC-16: 可視性 deny（assertCanView 例外）が優先 → checkAccess を呼ばない")
        void AC16_可視性denyが優先() {
            BlogPostEntity entity = postWithId();
            try (MockedStatic<SecurityUtils> su = Mockito.mockStatic(SecurityUtils.class)) {
                su.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(VIEWER_ID);
                org.mockito.BDDMockito.willThrow(new BusinessException(
                                com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_004))
                        .given(contentVisibilityChecker)
                        .assertCanView(ReferenceType.BLOG_POST, POST_ID, VIEWER_ID);

                assertThatThrownBy(() -> service.getById(POST_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("VISIBILITY_004"));
                verify(paymentGateService, never()).checkAccess(any(), any(), any());
            }
        }
    }
}
