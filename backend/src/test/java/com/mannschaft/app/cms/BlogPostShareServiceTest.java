package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.cms.service.BlogPostShareService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostShareService} の単体テスト。
 *
 * <p>リファクタリング第10弾で BlogPostService から分離した
 * 共有・プレビュートークン処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostShareService 単体テスト")
class BlogPostShareServiceTest {

    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private BlogPostShareRepository shareRepository;
    @Mock
    private CmsMapper cmsMapper;
    @Mock
    private AccessControlService accessControlService;

    /** 本番の {@code ClockConfig#utcClock} と同じ UTC 固定 Clock を実インスタンスで注入する。 */
    @Spy
    private java.time.Clock clock = java.time.Clock.systemUTC();

    @InjectMocks
    private BlogPostShareService service;

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
        return BlogPostResponse.builder()
                .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                .build();
    }

    @Nested
    @DisplayName("sharePost")
    class SharePost {

        @Test
        @DisplayName("異常系: ソーシャルプロフィール記事の共有でCMS_012例外")
        void 共有_ソーシャルプロフィール_例外() {
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

            assertThatThrownBy(() -> service.sharePost(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_012"));
        }

        @Test
        @DisplayName("異常系: チームスコープで重複共有でCMS_013例外")
        void 共有_チーム重複_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            SharePostRequest request = new SharePostRequest(2L, null);
            given(shareRepository.findByBlogPostIdAndTeamId(POST_ID, 2L))
                    .willReturn(Optional.of(BlogPostShareEntity.builder().build()));

            assertThatThrownBy(() -> service.sharePost(POST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_013"));
        }

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
        @DisplayName("異常系(認可根治Wave3-B7): 個人記事の非所有者による共有は403(COMMON_002・IDOR対策)")
        void 共有_非所有者_例外() {
            // entity.userId/authorId=USER_ID（個人ブログ・team/org無し）。actor=999L は非所有者。
            BlogPostEntity entity = BlogPostEntity.builder()
                    .userId(USER_ID).authorId(USER_ID)
                    .title("個人記事").slug("s").body("b")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PUBLISHED)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            SharePostRequest request = new SharePostRequest(2L, null);

            assertThatThrownBy(() -> service.sharePost(POST_ID, 999L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(shareRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("異常系: 組織スコープで重複共有でCMS_013例外")
        void 共有_組織重複_例外() {
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

    @Nested
    @DisplayName("revokeShare")
    class RevokeShare {

        @Test
        @DisplayName("異常系: 共有不在でCMS_019例外")
        void 共有取消_不在_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(shareRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeShare(POST_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_019"));
        }

        @Test
        @DisplayName("異常系: 共有の記事IDが不一致でCMS_019例外（BOLA存在秘匿）")
        void 共有取消_記事ID不一致_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            // share.blogPostId = 99 ≠ POST_ID = 10
            BlogPostShareEntity share = BlogPostShareEntity.builder()
                    .blogPostId(99L).teamId(1L).sharedBy(USER_ID).build();
            given(shareRepository.findById(5L)).willReturn(Optional.of(share));

            assertThatThrownBy(() -> service.revokeShare(POST_ID, 5L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_019"));
        }

        @Test
        @DisplayName("正常系: 共有が取り消される")
        void 共有取消_正常_削除実行() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            BlogPostShareEntity share = BlogPostShareEntity.builder()
                    .blogPostId(POST_ID).teamId(1L).sharedBy(USER_ID).build();
            given(shareRepository.findById(5L)).willReturn(Optional.of(share));

            service.revokeShare(POST_ID, 5L, USER_ID);

            verify(shareRepository).delete(share);
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非所有者かつ非ADMINの共有取消は403(COMMON_002)")
        void 共有取消_非所有者非ADMIN_例外() {
            // entity.authorId=USER_ID, teamId=TEAM_ID。非所有者・非ADMINなので拒否。
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            Long otherUserId = 999L;
            org.mockito.BDDMockito.willThrow(new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(otherUserId, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.revokeShare(POST_ID, 5L, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(shareRepository, org.mockito.Mockito.never()).findById(any());
        }
    }

    @Nested
    @DisplayName("issuePreviewToken")
    class IssuePreviewToken {

        @Test
        @DisplayName("異常系: 公開済み記事でCMS_010例外")
        void プレビュートークン_公開済み_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.issuePreviewToken(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_010"));
        }

        @Test
        @DisplayName("正常系: ドラフト記事にプレビュートークンが発行される")
        void プレビュートークン_ドラフト_正常発行() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            BlogPostResponse result = service.issuePreviewToken(POST_ID, USER_ID);

            assertThat(result).isNotNull();
            assertThat(entity.getPreviewToken()).isNotNull();
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非所有者かつ非ADMINのプレビュートークン発行は403(COMMON_002)")
        void プレビュートークン発行_非所有者非ADMIN_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            Long otherUserId = 999L;
            org.mockito.BDDMockito.willThrow(new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(otherUserId, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.issuePreviewToken(POST_ID, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("revokePreviewToken")
    class RevokePreviewToken {

        @Test
        @DisplayName("正常系: プレビュートークンが無効化される")
        void プレビュートークン無効化_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            service.revokePreviewToken(POST_ID, USER_ID);

            verify(postRepository).save(entity);
        }
    }
}
