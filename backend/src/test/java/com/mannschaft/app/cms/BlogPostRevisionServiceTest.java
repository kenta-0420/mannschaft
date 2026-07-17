package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.cms.service.BlogPostRevisionService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostRevisionService} の単体テスト。
 *
 * <p>リファクタリング第10弾で BlogPostService から分離したリビジョン管理処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostRevisionService 単体テスト")
class BlogPostRevisionServiceTest {

    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private BlogPostRevisionRepository revisionRepository;
    @Mock
    private CmsMapper cmsMapper;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private BlogPostRevisionService service;

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
        return BlogPostResponse.builder()
                .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                .build();
    }

    @Nested
    @DisplayName("listRevisions")
    class ListRevisions {

        @Test
        @DisplayName("正常系: リビジョン一覧が返却される")
        void リビジョン一覧_正常_返却() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.findByBlogPostIdOrderByCreatedAtDesc(POST_ID)).willReturn(List.of());
            given(cmsMapper.toRevisionResponseList(any())).willReturn(List.of());

            List<RevisionResponse> result = service.listRevisions(POST_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("異常系: 記事不在でCMS_001例外")
        void 記事不在_例外() {
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.listRevisions(POST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_001"));
        }
    }

    @Nested
    @DisplayName("restoreRevision")
    class RestoreRevision {

        @Test
        @DisplayName("異常系: リビジョン不在でCMS_004例外")
        void 復元_リビジョン不在_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            given(revisionRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.restoreRevision(POST_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_004"));
        }

        @Test
        @DisplayName("正常系: リビジョンから記事が復元されDRAFTに戻る")
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
            // 復元前に現状をリビジョン保存する
            verify(revisionRepository).save(any(BlogPostRevisionEntity.class));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7・BOLA): リビジョンが他postID配下でCMS_004例外（存在秘匿）")
        void 復元_他post配下のリビジョン_例外() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            // revision.blogPostId=999（POST_ID=10 とは別記事）＝越境アクセス
            BlogPostRevisionEntity otherPostRevision = BlogPostRevisionEntity.builder()
                    .blogPostId(999L).revisionNumber(1).title("他post").body("他post本文").editorId(USER_ID).build();
            given(revisionRepository.findById(77L)).willReturn(Optional.of(otherPostRevision));

            assertThatThrownBy(() -> service.restoreRevision(POST_ID, 77L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_004"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 個人記事(team/org無し)を非所有者が復元しようとすると403(COMMON_002)")
        void 復元_個人記事_非所有者_例外() {
            BlogPostEntity entity = BlogPostEntity.builder()
                    .userId(USER_ID).authorId(USER_ID)
                    .title("個人記事").slug("s").body("b")
                    .postType(PostType.BLOG).visibility(Visibility.MEMBERS_ONLY)
                    .priority(PostPriority.NORMAL).status(PostStatus.PUBLISHED)
                    .readingTimeMinutes((short) 1).build();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.restoreRevision(POST_ID, 5L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(revisionRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("saveRevision (package-private)")
    class SaveRevision {

        @Test
        @DisplayName("正常系: 1件のリビジョンが保存される")
        void リビジョン保存_正常() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(revisionRepository.countByBlogPostId(any())).willReturn(0L);

            service.saveRevision(entity, USER_ID);

            verify(revisionRepository).save(any(BlogPostRevisionEntity.class));
        }

        @Test
        @DisplayName("正常系: リビジョン数が10件以上の場合、最古が物理削除される")
        void リビジョン保存_10件超_最古削除() {
            BlogPostEntity entity = createPostEntity(PostStatus.PUBLISHED);
            given(revisionRepository.countByBlogPostId(any())).willReturn(10L);
            BlogPostRevisionEntity oldest = BlogPostRevisionEntity.builder()
                    .blogPostId(POST_ID).revisionNumber(1).title("旧").body("旧本文").build();
            given(revisionRepository.findFirstByBlogPostIdOrderByRevisionNumberAsc(any()))
                    .willReturn(Optional.of(oldest));

            service.saveRevision(entity, USER_ID);

            verify(revisionRepository).delete(oldest);
            verify(revisionRepository).save(any(BlogPostRevisionEntity.class));
        }
    }
}
