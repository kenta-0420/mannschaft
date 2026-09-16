package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

/**
 * {@link AnnouncementCreationService} の単体テスト。
 *
 * <p>
 * createAnnouncement の正常系・異常系を検証する。
 * togglePin / markAsRead は {@link AnnouncementFeedService} / {@link AnnouncementReadService} が担う。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementFeedService 単体テスト")
class AnnouncementFeedServiceTest {

    // ── AnnouncementSourceResolver のモック ──

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
    private CommitteeDistributionLogRepository committeeDistributionLogRepository;

    @InjectMocks
    private AnnouncementSourceResolver announcementSourceResolver;

    // ── AnnouncementCreationService のモック ──

    @Mock
    private AnnouncementFeedRepository feedRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    @InjectMocks
    private AnnouncementCreationService announcementCreationService;

    // ── AnnouncementReadService のモック ──

    @Mock
    private AnnouncementReadStatusRepository readStatusRepository;

    /** 既読の可視性ゲートが使う閲覧者ロール解決（一覧側と同一の正準経路）。 */
    @Mock
    private RoleResolver roleResolver;

    @InjectMocks
    private AnnouncementReadService announcementReadService;

    // ── AnnouncementFeedService（委譲先として creationService と readService を使用） ──

    @Mock
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Mock
    private PaymentGateService paymentGateService;

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
                // W2: announcement の「内輪」可視性は正準名 MEMBERS_AND_ABOVE（旧 MEMBERS_ONLY 改称・挙動不変）
                .visibility("MEMBERS_AND_ABOVE")
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
        // AnnouncementCreationService に AnnouncementSourceResolver を注入
        try {
            java.lang.reflect.Field f = AnnouncementCreationService.class.getDeclaredField("sourceResolver");
            f.setAccessible(true);
            f.set(announcementCreationService, announcementSourceResolver);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("sourceResolverのセットに失敗しました", e);
        }
        // AnnouncementFeedService に creationService を注入
        try {
            java.lang.reflect.Field f = AnnouncementFeedService.class.getDeclaredField("creationService");
            f.setAccessible(true);
            f.set(announcementFeedService, announcementCreationService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("creationServiceのセットに失敗しました", e);
        }
        // AnnouncementFeedService に readService を注入
        try {
            java.lang.reflect.Field f = AnnouncementFeedService.class.getDeclaredField("readService");
            f.setAccessible(true);
            f.set(announcementFeedService, announcementReadService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("readServiceのセットに失敗しました", e);
        }
        // AnnouncementReadService に creationService を注入
        try {
            java.lang.reflect.Field f = AnnouncementReadService.class.getDeclaredField("creationService");
            f.setAccessible(true);
            f.set(announcementReadService, announcementCreationService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("readService.creationServiceのセットに失敗しました", e);
        }
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
        @DisplayName("正常系: 未読 → 既読に変更（冪等 UPSERT が呼ばれる）")
        void markAsRead_正常_未読から既読() {
            // Given: 当該スコープに帰属し、閲覧者（MEMBER）に可視なお知らせが存在し、未読状態
            given(feedRepository.findById(ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(buildScopedFeed()));
            givenViewerRole(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .willReturn(Optional.empty()); // 未読
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementFeedService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, AUTHOR_USER_ID);

            // Then: 既読レコードが作られる。
            // #2530 ⑤ で「存在確認 → 素の INSERT」をやめ、DB 側で冪等な UPSERT に寄せたため、
            // 検証先も save ではなく insertReadStatusesIgnoringExisting になる。
            verify(readStatusRepository)
                    .insertReadStatusesIgnoringExisting(AUTHOR_USER_ID, List.of(ANNOUNCEMENT_ID));
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
        }

        @Test
        @DisplayName("正常系: 既に既読の場合は save が呼ばれない（冪等）")
        void markAsRead_正常_既読済みは冪等() {
            // Given: 当該スコープに帰属し、閲覧者（MEMBER）に可視なお知らせが存在し、既読済み
            given(feedRepository.findById(ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(buildScopedFeed()));
            givenViewerRole(ViewerRole.MEMBER);
            AnnouncementReadStatusEntity existingStatus = AnnouncementReadStatusEntity.builder()
                    .announcementFeedId(ANNOUNCEMENT_ID)
                    .userId(AUTHOR_USER_ID)
                    .build();
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .willReturn(Optional.of(existingStatus)); // 既読済み

            // When
            announcementFeedService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, AUTHOR_USER_ID);

            // Then: save は呼ばれない（冪等）
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
            // #2530 ⑤: 既読作成は UPSERT 経路に移ったので、そちらも呼ばれないことを確かめる
            verify(readStatusRepository, never()).insertReadStatusesIgnoringExisting(anyLong(), any());
        }

        @Test
        @DisplayName("異常系: 別スコープのお知らせ ID は ANNOUNCE_001（越境の存在秘匿）")
        void markAsRead_異常_越境スコープはANNOUNCE_001() {
            // Given: お知らせは存在するが teamB（別スコープ）に帰属する
            AnnouncementFeedEntity otherScopeFeed = AnnouncementFeedEntity.builder()
                    .scopeType(AnnouncementScopeType.TEAM)
                    .scopeId(TEAM_ID + 1)
                    .sourceType(AnnouncementSourceType.BLOG_POST)
                    .sourceId(BLOG_POST_ID)
                    .titleCache("別チームのお知らせ")
                    .build();
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(otherScopeFeed));

            // When / Then
            assertThatThrownBy(() -> announcementFeedService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_001"));
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
            // #2530 ⑤: 既読作成は UPSERT 経路に移ったので、そちらも呼ばれないことを確かめる
            verify(readStatusRepository, never()).insertReadStatusesIgnoringExisting(anyLong(), any());
        }

        @Test
        @DisplayName("異常系: 応援者に内輪限定（MEMBERS_AND_ABOVE）は ANNOUNCE_001（可視性ゲート）")
        void markAsRead_異常_応援者に内輪限定はANNOUNCE_001() {
            // Given: 当該スコープの内輪限定お知らせ。閲覧者は SUPPORTER なので一覧にも出ない。
            given(feedRepository.findById(ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(buildScopedFeed()));
            givenViewerRole(ViewerRole.SUPPORTER);

            // When / Then: 越境と同一のエラーコードに畳み込まれる（存在秘匿）
            assertThatThrownBy(() -> announcementFeedService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANNOUNCE_001"));
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
            // #2530 ⑤: 既読作成は UPSERT 経路に移ったので、そちらも呼ばれないことを確かめる
            verify(readStatusRepository, never()).insertReadStatusesIgnoringExisting(anyLong(), any());
        }

        @Test
        @DisplayName("正常系: 非メンバー（PUBLIC ロール）でも PUBLIC のお知らせは既読にできる")
        void markAsRead_正常_非メンバーでもPUBLICは既読可() {
            // Given: PUBLIC 可視のお知らせ。閲覧者はロールなし（＝一覧では PUBLIC のみ見える）。
            given(feedRepository.findById(ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(buildScopedFeed(AnnouncementVisibility.PUBLIC)));
            givenViewerRole(ViewerRole.PUBLIC);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, AUTHOR_USER_ID))
                    .willReturn(Optional.empty());
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementFeedService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, AUTHOR_USER_ID);

            // Then（#2530 ⑤: 既読作成は DB 側で冪等な UPSERT 経路）
            verify(readStatusRepository)
                    .insertReadStatusesIgnoringExisting(AUTHOR_USER_ID, List.of(ANNOUNCEMENT_ID));
        }

        private void givenViewerRole(ViewerRole viewerRole) {
            given(roleResolver.resolveViewerRole(AUTHOR_USER_ID, "TEAM", TEAM_ID)).willReturn(viewerRole);
            lenient().when(paymentGateService.checkAccess(
                    eq(ContentGateType.ANNOUNCEMENT), eq(ANNOUNCEMENT_ID), eq(AUTHOR_USER_ID), any(ContentGateTarget.class)))
                    .thenReturn(new GateCheckResponse(true, false, List.of()));
        }

        /** 当該スコープ（TEAM_ID）に帰属する内輪限定のお知らせフィードを組み立てる。 */
        private AnnouncementFeedEntity buildScopedFeed() {
            return buildScopedFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE);
        }

        private AnnouncementFeedEntity buildScopedFeed(String visibility) {
            AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                    .scopeType(AnnouncementScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .sourceType(AnnouncementSourceType.BLOG_POST)
                    .sourceId(BLOG_POST_ID)
                    .titleCache("お知らせ")
                    .visibility(visibility)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(feed, "id", ANNOUNCEMENT_ID);
            return feed;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // getAnnouncementFeed（可視性漏洩根治）
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAnnouncementFeed（閲覧者ロール → 可視 visibility 集合）")
    class GetAnnouncementFeed {

        private AnnouncementFeedEntity feed(long id) {
            AnnouncementFeedEntity feed = mock(AnnouncementFeedEntity.class);
            given(feed.getId()).willReturn(id);
            given(feed.getScopeType()).willReturn(AnnouncementScopeType.TEAM);
            given(feed.getScopeId()).willReturn(TEAM_ID);
            return feed;
        }

        @Test
        @DisplayName("HIDDENを除外して次chunkを補充し、ページ境界を維持する")
        void hiddenRowsAreReplenishedFromNextChunk() {
            AnnouncementFeedEntity hidden5 = feed(5L);
            AnnouncementFeedEntity full4 = feed(4L);
            AnnouncementFeedEntity hidden3 = feed(3L);
            AnnouncementFeedEntity full2 = feed(2L);
            AnnouncementFeedEntity full1 = feed(1L);
            given(feedQueryRepository.findByScope(eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(),
                    isNull(), eq(3))).willReturn(List.of(hidden5, full4, hidden3));
            given(feedQueryRepository.findByScope(eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(),
                    eq(3L), eq(3))).willReturn(List.of(full2, full1));
            given(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), eq(OTHER_USER_ID), any(Map.class)))
                    .willReturn(java.util.Map.of(
                            5L, new GateCheckResponse(false, true, List.of()),
                            3L, new GateCheckResponse(false, true, List.of()),
                            4L, new GateCheckResponse(false, false, List.of()),
                            2L, new GateCheckResponse(false, false, List.of()),
                            1L, new GateCheckResponse(true, false, List.of())));
            AnnouncementFeedService.AnnouncementFeedResult result = announcementFeedService.getAnnouncementFeed(
                    AnnouncementScopeType.TEAM, TEAM_ID, OTHER_USER_ID, "MEMBER", null, 2);

            assertThat(result.data()).extracting(item -> item.feed().getId()).containsExactly(4L, 2L);
            assertThat(result.data()).extracting(AnnouncementFeedService.AnnouncementFeedItem::accessState)
                    .containsExactly("LOCKED", "LOCKED");
            assertThat(result.hasNext()).isTrue();
            assertThat(result.nextCursor()).isEqualTo(2L);
            assertThat(result.unreadCount()).isEqualTo(2L);
            verify(feedQueryRepository).findByScope(eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(),
                    eq(3L), eq(3));
        }

        @Test
        @DisplayName("全件HIDDENなら空ページかつ次ページなし")
        void allHiddenRowsAreExcluded() {
            AnnouncementFeedEntity hidden2 = feed(2L);
            AnnouncementFeedEntity hidden1 = feed(1L);
            given(feedQueryRepository.findByScope(eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(),
                    isNull(), eq(3))).willReturn(List.of(hidden2, hidden1));
            given(paymentGateService.checkAccessBatch(eq(ContentGateType.ANNOUNCEMENT), any(), eq(OTHER_USER_ID), any(Map.class)))
                    .willReturn(java.util.Map.of(
                            2L, new GateCheckResponse(false, true, List.of()),
                            1L, new GateCheckResponse(false, true, List.of())));
            AnnouncementFeedService.AnnouncementFeedResult result = announcementFeedService.getAnnouncementFeed(
                    AnnouncementScopeType.TEAM, TEAM_ID, OTHER_USER_ID, "MEMBER", null, 2);

            assertThat(result.data()).isEmpty();
            assertThat(result.hasNext()).isFalse();
            assertThat(result.nextCursor()).isNull();
            assertThat(result.unreadCount()).isZero();
        }

        /**
         * 閲覧者ロール名から findByScope に渡される allowedVisibilities を捕捉する。
         */
        private Set<String> captureAllowedVisibilities(String viewerRoleName) {
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), isNull(), anyInt()))
                    .willReturn(List.of());

            announcementFeedService.getAnnouncementFeed(
                    AnnouncementScopeType.TEAM, TEAM_ID, ADMIN_USER_ID, viewerRoleName, null, 10);

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<Set<String>> captor =
                    org.mockito.ArgumentCaptor.forClass(Set.class);
            verify(feedQueryRepository).findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), captor.capture(), isNull(), anyInt());
            return captor.getValue();
        }

        @Test
        @DisplayName("SUPPORTER → {PUBLIC, SUPPORTERS_AND_ABOVE}（MEMBERS_AND_ABOVE を露出させない＝漏洩根治）")
        void supporter_doesNotLeakMembersOnly() {
            // W2: 内輪可視性は正準名 MEMBERS_AND_ABOVE（旧 MEMBERS_ONLY 改称・挙動不変）
            assertThat(captureAllowedVisibilities("SUPPORTER"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE")
                    .doesNotContain("MEMBERS_AND_ABOVE");
        }

        @Test
        @DisplayName("MEMBER → 3 種全部（PUBLIC/SUPPORTERS_AND_ABOVE 取りこぼし解消）")
        void member_seesAllThree() {
            assertThat(captureAllowedVisibilities("MEMBER"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE");
        }

        @Test
        @DisplayName("ADMIN → 3 種全部")
        void admin_seesAllThree() {
            assertThat(captureAllowedVisibilities("ADMIN"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE");
        }

        @Test
        @DisplayName("PUBLIC（ロールなし）→ {PUBLIC} のみ")
        void public_seesOnlyPublic() {
            assertThat(captureAllowedVisibilities("PUBLIC"))
                    .containsExactlyInAnyOrder("PUBLIC");
        }
    }
}
