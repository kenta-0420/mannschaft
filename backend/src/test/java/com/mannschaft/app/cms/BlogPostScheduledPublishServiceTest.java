package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.cms.service.BlogPostRevisionService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogPostShareService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ブログ予約公開のサービス層テスト（issue #2616・試練）。
 *
 * <h2>設計方針（テストが固定する不変条件）</h2>
 * <p>予約中の記事は {@code status = DRAFT} のまま {@code published_at} に未来時刻を持つ。
 * {@code PostStatus.SCHEDULED} は新設しない（F06.1 §155 / §949 / §2023 / §2210-2226）。</p>
 *
 * <h2>受け入れ条件の対応</h2>
 * <ul>
 *   <li><b>AC-1</b>: 未来の publishedAt を指定した publish は DRAFT のまま publishedAt だけ入る</li>
 *   <li><b>AC-2</b>: 過去の publishedAt は即 PUBLISHED</li>
 *   <li><b>AC-3</b>: publishedAt 未指定は現在時刻で即 PUBLISHED</li>
 *   <li><b>AC-16</b>: 他人の記事への publish は従来通り拒否（予約公開の追加で認可が緩まない）</li>
 *   <li><b>AC-17</b>: 一括 PUBLISH・セルフレビュー公開でも予約判定が一貫する</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ブログ予約公開 サービス層テスト（issue #2616）")
class BlogPostScheduledPublishServiceTest {

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
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long POST_ID = 10L;

    private BlogPostEntity createPostEntity(PostStatus status) {
        return BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(USER_ID)
                .title("テスト記事")
                .slug("test-article")
                .body("テスト本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PUBLIC)
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

    // ========================================================================
    // AC-1 / AC-2 / AC-3: publish の時刻判定
    // ========================================================================

    @Nested
    @DisplayName("changeStatus(PUBLISHED) の予約判定")
    class ScheduledPublishDecision {

        @Test
        @DisplayName("AC-1: 未来の publishedAt を指定すると DRAFT のまま publishedAt だけが入る")
        void ac1_未来日時は予約扱いでDRAFTのまま() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            LocalDateTime future = LocalDateTime.now().plusDays(3);
            PublishRequest request = new PublishRequest("PUBLISHED", future, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.changeStatus(POST_ID, USER_ID, request);

            assertThat(entity.getStatus())
                    .as("予約中は DRAFT のまま（SCHEDULED は新設しない）。"
                            + "公開系クエリは status = PUBLISHED の等値判定なので構造的に漏れない")
                    .isEqualTo(PostStatus.DRAFT);
            assertThat(entity.getPublishedAt())
                    .as("予約時刻が published_at に格納され、バッチの抽出対象になる")
                    .isEqualTo(future);
        }

        @Test
        @DisplayName("AC-2: 過去の publishedAt を指定すると即 PUBLISHED になる")
        void ac2_過去日時は即公開() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            LocalDateTime past = LocalDateTime.now().minusDays(1);
            PublishRequest request = new PublishRequest("PUBLISHED", past, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            service.changeStatus(POST_ID, USER_ID, request);

            assertThat(entity.getStatus()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(entity.getPublishedAt()).isEqualTo(past);
        }

        @Test
        @DisplayName("AC-3: publishedAt 未指定なら現在時刻で即 PUBLISHED になる")
        void ac3_未指定は現在時刻で即公開() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            PublishRequest request = new PublishRequest("PUBLISHED", null, null);
            given(postRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogPostResponse(entity)).willReturn(createPostResponse());

            LocalDateTime before = LocalDateTime.now();
            service.changeStatus(POST_ID, USER_ID, request);
            LocalDateTime after = LocalDateTime.now();

            assertThat(entity.getStatus()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(entity.getPublishedAt())
                    .as("現在時刻が入る（予約扱いにならない）")
                    .isBetween(before, after);
        }
    }

    // ========================================================================
    // AC-16: 認可（予約公開の追加で publish の認可が緩まないこと）
    // ========================================================================

    @Nested
    @DisplayName("予約公開でも認可は緩まない")
    class Authorization {

        @Test
        @DisplayName("AC-16: 他人の記事に未来日時で publish しても認可で拒否され、保存されない")
        void ac16_他人の記事への予約公開は拒否される() {
            BlogPostEntity entity = createPostEntity(PostStatus.DRAFT);
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(entity));
            // 投稿者本人ではないため、スコープ ADMIN 検証で弾かれる
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(OTHER_USER_ID, TEAM_ID, "TEAM");
            PublishRequest request =
                    new PublishRequest("PUBLISHED", LocalDateTime.now().plusDays(3), null);

            assertThatThrownBy(() -> service.changeStatus(POST_ID, OTHER_USER_ID, request))
                    .isInstanceOf(BusinessException.class);

            assertThat(entity.getPublishedAt())
                    .as("認可で弾かれた記事の published_at は書き換わらない（予約が既成事実化しない）")
                    .isNull();
            verify(postRepository, never()).save(any(BlogPostEntity.class));
        }

        @Test
        @DisplayName("AC-16: 存在しない記事への予約公開は POST_NOT_FOUND（存在秘匿）")
        void ac16_存在しない記事は404() {
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());
            PublishRequest request =
                    new PublishRequest("PUBLISHED", LocalDateTime.now().plusDays(3), null);

            assertThatThrownBy(() -> service.changeStatus(POST_ID, OTHER_USER_ID, request))
                    .as("不在も他人の記事も同じ POST_NOT_FOUND で存在を秘匿する")
                    .isInstanceOf(BusinessException.class);
            verify(postRepository, never()).save(any(BlogPostEntity.class));
        }
    }

    // ========================================================================
    // AC-17: 他の公開遷移経路でも予約判定が一貫する
    // ========================================================================

    @Nested
    @DisplayName("他の公開遷移経路での一貫性")
    class OtherPublishPaths {

        @Test
        @DisplayName("AC-17: 一括 PUBLISH は未来 publishedAt を持つ予約記事を公開してしまわない")
        void ac17_一括公開は予約記事を即公開しない() {
            BlogPostEntity scheduled = createPostEntity(PostStatus.DRAFT);
            // 既に予約済み（未来の published_at を持つ DRAFT）
            ReflectionTestUtils.setField(scheduled, "publishedAt", LocalDateTime.now().plusDays(3));
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(scheduled));
            BulkActionRequest request = new BulkActionRequest(List.of(POST_ID), "PUBLISH");

            service.bulkAction(request, USER_ID);

            assertThat(scheduled.getStatus())
                    .as("予約済み記事は一括公開の対象にせず DRAFT のまま据え置く"
                            + "（予約時刻より前に公開されると予約の意味が失われる）")
                    .isEqualTo(PostStatus.DRAFT);
        }

        @Test
        @DisplayName("AC-17: セルフレビュー PUBLISH は予約時刻を持つ記事を DRAFT のまま据え置く")
        void ac17_セルフレビュー公開は予約時刻を尊重する() {
            BlogPostEntity scheduled = createPostEntity(PostStatus.PENDING_SELF_REVIEW);
            ReflectionTestUtils.setField(scheduled, "publishedAt", LocalDateTime.now().plusDays(3));
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(scheduled));
            given(postRepository.save(scheduled)).willReturn(scheduled);
            given(cmsMapper.toBlogPostResponse(scheduled)).willReturn(createPostResponse());
            SelfReviewRequest request = new SelfReviewRequest("PUBLISH");

            service.selfReview(POST_ID, USER_ID, request);

            assertThat(scheduled.getStatus())
                    .as("予約時刻が未来なら DRAFT へ戻し、バッチの公開を待つ")
                    .isEqualTo(PostStatus.DRAFT);
            assertThat(scheduled.getPublishedAt())
                    .as("予約時刻は保持される")
                    .isNotNull();
        }
    }
}
