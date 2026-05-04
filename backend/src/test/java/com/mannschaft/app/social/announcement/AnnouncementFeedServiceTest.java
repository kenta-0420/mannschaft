package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AnnouncementFeedService} の単体テスト。
 *
 * <p>
 * createAnnouncement / togglePin / markAsRead の正常系・異常系を検証する。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementFeedService 単体テスト")
class AnnouncementFeedServiceTest {

    // ── モック ──

    @Mock
    private AnnouncementFeedRepository feedRepository;

    @Mock
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Mock
    private AnnouncementReadStatusRepository readStatusRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private BlogPostRepository blogPostRepository;

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private CirculationDocumentRepository circulationDocumentRepository;

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private AnnouncementFeedService announcementFeedService;

    // ── 定数 ──

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 1L;
    private static final Long AUTHOR_USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long BLOG_POST_ID = 500L;
    private static final Long TIMELINE_POST_ID = 600L;
    private static final Long ANNOUNCEMENT_ID = 999L;

    // ── ヘルパー: テスト用エンティティ生成 ──

    /**
     * チームブログ記事エンティティを生成する（個人ブログでない）。
     */
    private BlogPostEntity buildTeamBlogPost(Long postId, Long teamId, Long authorId, PostPriority priority) {
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(teamId)
                .organizationId(null)
                .userId(null)           // 個人ブログでない
                .socialProfileId(null)  // ソーシャルプロフィール投稿でない
                .authorId(authorId)
                .title("チームブログ記事タイトル")
                .slug("team-blog-slug")
                .body("本文テキスト")
                .priority(priority)
                .visibility(Visibility.MEMBERS_ONLY)
                .build();
        try {
            java.lang.reflect.Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(post, postId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return post;
    }

    /**
     * 個人ブログ記事エンティティを生成する（userId != null）。
     */
    private BlogPostEntity buildPersonalBlogPost(Long postId, Long userId) {
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(null)
                .organizationId(null)
                .userId(userId)         // 個人ブログ
                .socialProfileId(null)
                .authorId(userId)
                .title("個人ブログ記事タイトル")
                .slug("personal-blog-slug")
                .body("本文テキスト")
                .priority(PostPriority.NORMAL)
                .visibility(Visibility.PUBLIC)
                .build();
        try {
            java.lang.reflect.Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(post, postId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return post;
    }

    /**
     * タイムライン投稿エンティティを生成する（チームスコープ）。
     */
    private TimelinePostEntity buildTimelinePost(Long postId, Long teamId, Long userId) {
        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(teamId)
                .userId(userId)
                .content("タイムライン投稿の本文テキスト")
                .build();
        try {
            java.lang.reflect.Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(post, postId);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return post;
    }

    /**
     * お知らせフィードエンティティを生成する。
     */
    private AnnouncementFeedEntity buildAnnouncement(Long id, Long scopeId, Long authorId, boolean isPinned) {
        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM)
                .scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(BLOG_POST_ID)
                .authorId(authorId)
                .titleCache("タイトル")
                .excerptCache("抜粋")
                .priority("NORMAL")
                .visibility("MEMBERS_ONLY")
                .build();
        // isPinned を設定
        if (isPinned) {
            entity.markPinned(authorId);
        }
        // ID をセット
        try {
            java.lang.reflect.Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return entity;
    }

    @BeforeEach
    void setUp() {
        // feedRepository.save は引数をそのまま返す（異常系テストでは使われないため lenient を使用）
        lenient().when(feedRepository.save(any(AnnouncementFeedEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ═══════════════════════════════════════════════════════════════
    // createAnnouncement
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createAnnouncement（お知らせ化）")
    class CreateAnnouncement {

        @Test
        @DisplayName("正常系: ADMIN がチームブログ記事をお知らせ化できる")
        void createAnnouncement_正常_ADMIN_ブログ記事() {
            // Given: チームブログ記事が存在し、重複なし、ADMINである
            BlogPostEntity post = buildTeamBlogPost(BLOG_POST_ID, TEAM_ID, OTHER_USER_ID, PostPriority.IMPORTANT);
            given(blogPostRepository.findById(BLOG_POST_ID)).willReturn(Optional.of(post));
            given(feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                    AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, AnnouncementScopeType.TEAM, TEAM_ID))
                    .willReturn(Optional.empty());
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            // When
            AnnouncementFeedEntity result = announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, ADMIN_USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getScopeType()).isEqualTo(AnnouncementScopeType.TEAM);
            assertThat(result.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(result.getSourceType()).isEqualTo(AnnouncementSourceType.BLOG_POST);
            assertThat(result.getSourceId()).isEqualTo(BLOG_POST_ID);
            assertThat(result.getPriority()).isEqualTo("IMPORTANT"); // IMPORTANT → IMPORTANT
            verify(feedRepository).save(any(AnnouncementFeedEntity.class));
        }

        @Test
        @DisplayName("正常系: 著者本人がタイムライン投稿をお知らせ化できる")
        void createAnnouncement_正常_著者本人_タイムライン投稿() {
            // Given: タイムライン投稿が存在し、著者本人がリクエスト
            TimelinePostEntity post = buildTimelinePost(TIMELINE_POST_ID, TEAM_ID, AUTHOR_USER_ID);
            given(timelinePostRepository.findById(TIMELINE_POST_ID)).willReturn(Optional.of(post));
            given(feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                    AnnouncementSourceType.TIMELINE_POST, TIMELINE_POST_ID, AnnouncementScopeType.TEAM, TEAM_ID))
                    .willReturn(Optional.empty());
            given(accessControlService.isAdminOrAbove(AUTHOR_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // When
            AnnouncementFeedEntity result = announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.TIMELINE_POST,
                    TIMELINE_POST_ID, AUTHOR_USER_ID);

            // Then: 著者本人なのでお知らせ化成功
            assertThat(result).isNotNull();
            assertThat(result.getSourceType()).isEqualTo(AnnouncementSourceType.TIMELINE_POST);
            assertThat(result.getSourceId()).isEqualTo(TIMELINE_POST_ID);
            assertThat(result.getPriority()).isEqualTo("NORMAL"); // タイムラインは常に NORMAL
            verify(feedRepository).save(any(AnnouncementFeedEntity.class));
        }

        @Test
        @DisplayName("異常系: 同じコンテンツの重複登録 → ANNOUNCE_003")
        void createAnnouncement_異常_重複登録_ANNOUNCE003() {
            // Given: 既にお知らせフィードが存在する
            BlogPostEntity post = buildTeamBlogPost(BLOG_POST_ID, TEAM_ID, AUTHOR_USER_ID, PostPriority.NORMAL);
            AnnouncementFeedEntity existing = buildAnnouncement(ANNOUNCEMENT_ID, TEAM_ID, AUTHOR_USER_ID, false);
            given(blogPostRepository.findById(BLOG_POST_ID)).willReturn(Optional.of(post));
            given(feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                    AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, AnnouncementScopeType.TEAM, TEAM_ID))
                    .willReturn(Optional.of(existing));

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_003"));
        }

        @Test
        @DisplayName("異常系: 個人ブログはお知らせ化不可 → ANNOUNCE_007")
        void createAnnouncement_異常_個人ブログ_ANNOUNCE007() {
            // Given: 個人ブログ記事（userId != null）
            BlogPostEntity personalPost = buildPersonalBlogPost(BLOG_POST_ID, AUTHOR_USER_ID);
            given(blogPostRepository.findById(BLOG_POST_ID)).willReturn(Optional.of(personalPost));

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, AUTHOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_007"));
        }

        @Test
        @DisplayName("異常系: 権限なし（他人の投稿をMEMBERがお知らせ化）→ ANNOUNCE_002")
        void createAnnouncement_異常_権限なし_ANNOUNCE002() {
            // Given: ブログ記事の著者は OTHER_USER_ID、リクエストは ADMIN でも著者でもない
            BlogPostEntity post = buildTeamBlogPost(BLOG_POST_ID, TEAM_ID, OTHER_USER_ID, PostPriority.NORMAL);
            given(blogPostRepository.findById(BLOG_POST_ID)).willReturn(Optional.of(post));
            given(feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                    AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, AnnouncementScopeType.TEAM, TEAM_ID))
                    .willReturn(Optional.empty());
            // AUTHOR_USER_ID != OTHER_USER_ID かつ isAdmin = false
            given(accessControlService.isAdminOrAbove(AUTHOR_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, AUTHOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_002"));
        }

        @Test
        @DisplayName("異常系: ソースのスコープ不一致 → ANNOUNCE_005")
        void createAnnouncement_異常_スコープ不一致_ANNOUNCE005() {
            // Given: ブログ記事は別のチーム（teamId=99）に属する
            Long anotherTeamId = 99L;
            BlogPostEntity post = buildTeamBlogPost(BLOG_POST_ID, anotherTeamId, AUTHOR_USER_ID, PostPriority.NORMAL);
            given(blogPostRepository.findById(BLOG_POST_ID)).willReturn(Optional.of(post));

            // When / Then: TEAM_ID=10 でリクエストするが、記事は teamId=99
            assertThatThrownBy(() -> announcementFeedService.createAnnouncement(
                    AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.BLOG_POST, BLOG_POST_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_005"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // togglePin
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("togglePin（ピン留めトグル）")
    class TogglePin {

        @Test
        @DisplayName("正常系: ADMIN がピン留めできる（isPinned false → true）")
        void togglePin_正常_ADMIN_ピン留めON() {
            // Given: ピン留めされていないお知らせ
            AnnouncementFeedEntity entity = buildAnnouncement(ANNOUNCEMENT_ID, TEAM_ID, AUTHOR_USER_ID, false);
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(feedRepository.countByScopeTypeAndScopeIdAndIsPinnedTrueAndSourceDeletedAtIsNull(
                    AnnouncementScopeType.TEAM, TEAM_ID)).willReturn(0L); // 上限未達

            // When
            AnnouncementFeedEntity result = announcementFeedService.togglePin(ANNOUNCEMENT_ID, ADMIN_USER_ID);

            // Then: ピン留めが有効になる
            assertThat(result.getIsPinned()).isTrue();
            assertThat(result.getPinnedBy()).isEqualTo(ADMIN_USER_ID);
            verify(feedRepository).save(entity);
        }

        @Test
        @DisplayName("正常系: ADMIN がピン留め解除できる（isPinned true → false）")
        void togglePin_正常_ADMIN_ピン留め解除() {
            // Given: 既にピン留めされているお知らせ
            AnnouncementFeedEntity entity = buildAnnouncement(ANNOUNCEMENT_ID, TEAM_ID, AUTHOR_USER_ID, true);
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            // When
            AnnouncementFeedEntity result = announcementFeedService.togglePin(ANNOUNCEMENT_ID, ADMIN_USER_ID);

            // Then: ピン留めが解除される
            assertThat(result.getIsPinned()).isFalse();
            assertThat(result.getPinnedBy()).isNull();
            verify(feedRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: ピン留め上限5件超過 → ANNOUNCE_004")
        void togglePin_異常_ピン留め上限超過_ANNOUNCE004() {
            // Given: 既に5件ピン留め済み（上限達成）、今回のお知らせはピン未留め
            AnnouncementFeedEntity entity = buildAnnouncement(ANNOUNCEMENT_ID, TEAM_ID, AUTHOR_USER_ID, false);
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(feedRepository.countByScopeTypeAndScopeIdAndIsPinnedTrueAndSourceDeletedAtIsNull(
                    AnnouncementScopeType.TEAM, TEAM_ID)).willReturn(5L); // 上限到達

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.togglePin(ANNOUNCEMENT_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_004"));
        }

        @Test
        @DisplayName("異常系: MEMBER がピン留め試みる → ANNOUNCE_002")
        void togglePin_異常_MEMBER_権限なし_ANNOUNCE002() {
            // Given: お知らせは存在するが、リクエストユーザーは MEMBER（非 ADMIN）
            AnnouncementFeedEntity entity = buildAnnouncement(ANNOUNCEMENT_ID, TEAM_ID, AUTHOR_USER_ID, false);
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(OTHER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.togglePin(ANNOUNCEMENT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_002"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // markAsRead
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markAsRead（既読マーク）")
    class MarkAsRead {

        @Test
        @DisplayName("正常系: 未読 → 既読に変更（save が呼ばれる）")
        void markAsRead_正常_未読から既読() {
            // Given: お知らせが存在し、未読状態
            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .willReturn(Optional.empty()); // 未読

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, AUTHOR_USER_ID);

            // Then: 既読レコードが保存される
            verify(readStatusRepository).save(any(AnnouncementReadStatusEntity.class));
        }

        @Test
        @DisplayName("正常系: 既に既読の場合は save が呼ばれない（冪等）")
        void markAsRead_正常_既読済みは冪等() {
            // Given: お知らせが存在し、既読済み
            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            AnnouncementReadStatusEntity existingStatus = AnnouncementReadStatusEntity.builder()
                    .announcementFeedId(ANNOUNCEMENT_ID)
                    .userId(AUTHOR_USER_ID)
                    .build();
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .willReturn(Optional.of(existingStatus)); // 既読済み

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, AUTHOR_USER_ID);

            // Then: save は呼ばれない（冪等）
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
        }
    }
}
