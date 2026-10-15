package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * お知らせソース解決コンポーネント（F02.6）。
 *
 * <p>
 * ソース種別（BLOG_POST / BULLETIN_THREAD / TIMELINE_POST / CIRCULATION_DOCUMENT /
 * SURVEY / COMMITTEE_DECISION / COMMITTEE_MINUTES）に応じて元コンテンツを取得し、
 * IDOR 検証・タイトルキャッシュ/抜粋キャッシュ・優先度・visibility・expiresAt を抽出する。
 * </p>
 *
 * <p>
 * {@link AnnouncementCreationService} および {@link AnnouncementFeedService} から呼ばれる。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementSourceResolver {

    /** タイトルキャッシュの最大文字数 */
    private static final int MAX_TITLE_CACHE_LENGTH = 200;

    /** 抜粋キャッシュの最大文字数 */
    private static final int MAX_EXCERPT_CACHE_LENGTH = 300;

    // ── ソースリポジトリ（IDOR 検証・タイトル/excerpt 取得用） ──
    private final BlogPostRepository blogPostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final CirculationDocumentRepository circulationDocumentRepository;
    private final SurveyRepository surveyRepository;

    // ── 委員会関連リポジトリ（COMMITTEE スコープサポート用） ──
    private final CommitteeDistributionLogRepository committeeDistributionLogRepository;

    /**
     * ソース種別に応じて元コンテンツを取得し、IDOR 検証・情報抽出を行う。
     *
     * @param scopeType     リクエストスコープ種別
     * @param scopeId       リクエストスコープ ID
     * @param sourceType    ソース種別
     * @param sourceId      ソース ID
     * @param requestUserId リクエストユーザー ID（著者一致チェック用。createFromSource では authorId）
     * @return ソース情報レコード
     */
    public SourceInfo resolveSourceInfo(
            AnnouncementScopeType scopeType,
            Long scopeId,
            AnnouncementSourceType sourceType,
            Long sourceId,
            Long requestUserId) {

        return switch (sourceType) {
            case BLOG_POST -> resolveBlogPost(scopeType, scopeId, sourceId);
            case BULLETIN_THREAD -> resolveBulletinThread(scopeType, scopeId, sourceId);
            case TIMELINE_POST -> resolveTimelinePost(scopeType, scopeId, sourceId);
            case CIRCULATION_DOCUMENT -> resolveCirculationDocument(scopeType, scopeId, sourceId);
            case SURVEY -> resolveSurvey(scopeType, scopeId, sourceId);
            case COMMITTEE_DECISION, COMMITTEE_MINUTES ->
                    resolveCommitteeDistributionLog(scopeType, scopeId, sourceId, requestUserId);
            // TODO / SCHEDULE は F02.8 告知ウィザード専用。
            // createFromBroadcast() 経由で登録するためここには到達しない。
            case TODO, SCHEDULE -> throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_006);
            // F09.17 ADVERTISER_CAMPAIGN は AnnouncementFeedService.createAdvertiserFeed が
            // resolveSourceInfo を介さず直接 builder を組み立てるためここには到達しない。
            case ADVERTISER_CAMPAIGN -> throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_006);
        };
    }

    /**
     * ブログ記事のソース情報を解決する。
     *
     * <p>
     * IDOR 検証:
     * <ul>
     *   <li>個人ブログ（userId IS NOT NULL）→ ANNOUNCE_007</li>
     *   <li>ソーシャルプロフィール投稿（socialProfileId IS NOT NULL）→ ANNOUNCE_007</li>
     *   <li>スコープ不一致 → ANNOUNCE_005</li>
     * </ul>
     * </p>
     *
     * <p>
     * priority マッピング（設計書 §3 / §5.2）:
     * <ul>
     *   <li>CRITICAL → URGENT</li>
     *   <li>IMPORTANT → IMPORTANT</li>
     *   <li>NORMAL → NORMAL</li>
     * </ul>
     * </p>
     */
    private SourceInfo resolveBlogPost(AnnouncementScopeType scopeType, Long scopeId, Long sourceId) {
        BlogPostEntity post = blogPostRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // 個人ブログ・ソーシャルプロフィール投稿の拒否（設計書 §6.1, §6.5）
        if (post.getUserId() != null || post.getSocialProfileId() != null) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_007);
        }

        // スコープ一致検証（ブログ記事は TEAM / ORGANIZATION スコープのみ対応）
        boolean scopeMatches = switch (scopeType) {
            case TEAM -> scopeId.equals(post.getTeamId());
            case ORGANIZATION -> scopeId.equals(post.getOrganizationId());
            case COMMITTEE -> false; // ブログ記事は委員会スコープ不可
            case ADVERTISER_AD -> false; // F09.17 広告は別経路（createAdvertiserFeed）
        };
        if (!scopeMatches) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        // priority マッピング
        String priority = switch (post.getPriority()) {
            case CRITICAL -> "URGENT";
            case IMPORTANT -> "IMPORTANT";
            default -> "NORMAL";
        };

        // visibility マッピング
        String visibility = mapBlogVisibility(post.getVisibility());

        String titleCache = truncate(post.getTitle(), MAX_TITLE_CACHE_LENGTH);
        String excerptCache = resolveExcerpt(post.getExcerpt(), post.getBody());

        return new SourceInfo(post.getAuthorId(), titleCache, excerptCache, priority, visibility, null);
    }

    /**
     * 掲示板スレッドのソース情報を解決する。
     *
     * <p>
     * priority マッピング（設計書 §3）:
     * <ul>
     *   <li>URGENT → URGENT</li>
     *   <li>IMPORTANT → IMPORTANT</li>
     *   <li>NOTICE → NORMAL</li>
     *   <li>INFO → NORMAL</li>
     * </ul>
     * </p>
     */
    private SourceInfo resolveBulletinThread(AnnouncementScopeType scopeType, Long scopeId, Long sourceId) {
        BulletinThreadEntity thread = bulletinThreadRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // スコープ一致検証
        String expectedScopeType = scopeType.name(); // TEAM / ORGANIZATION
        if (!expectedScopeType.equals(thread.getScopeType().name()) || !scopeId.equals(thread.getScopeId())) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        // priority マッピング（設計書 §3: CRITICAL→URGENT, IMPORTANT→IMPORTANT, WARNING→NORMAL, INFO/LOW→NORMAL）
        String priority = switch (thread.getPriority()) {
            case URGENT -> "URGENT";
            case IMPORTANT -> "IMPORTANT";
            default -> "NORMAL";
        };

        String titleCache = truncate(thread.getTitle(), MAX_TITLE_CACHE_LENGTH);
        String excerptCache = resolveExcerpt(null, thread.getBody());

        // 掲示板は MEMBERS_AND_ABOVE 固定（掲示板自体がメンバー限定のため）
        return new SourceInfo(thread.getAuthorId(), titleCache, excerptCache, priority, "MEMBERS_AND_ABOVE", null);
    }

    /**
     * タイムライン投稿のソース情報を解決する。
     *
     * <p>
     * タイムライン投稿は優先度なし → 常に NORMAL（設計書 §3）。
     * </p>
     */
    private SourceInfo resolveTimelinePost(AnnouncementScopeType scopeType, Long scopeId, Long sourceId) {
        TimelinePostEntity post = timelinePostRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // スコープ一致検証（タイムライン投稿は TEAM / ORGANIZATION スコープのみ対応）
        boolean scopeMatches = switch (scopeType) {
            case TEAM -> scopeId.equals(post.getScopeId())
                    && "TEAM".equals(post.getScopeType().name());
            case ORGANIZATION -> scopeId.equals(post.getScopeId())
                    && "ORGANIZATION".equals(post.getScopeType().name());
            case COMMITTEE -> false; // タイムライン投稿は委員会スコープ不可
            case ADVERTISER_AD -> false; // F09.17 広告は別経路（createAdvertiserFeed）
        };
        if (!scopeMatches) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        // タイムラインはタイトルなし → 本文先頭30文字をタイトルキャッシュに
        String titleCache = post.getContent() != null
                ? truncate(post.getContent(), 30)
                : "(無題)";
        String excerptCache = resolveExcerpt(null, post.getContent());

        // タイムライン: 常に NORMAL（設計書 §3）
        return new SourceInfo(post.getUserId(), titleCache, excerptCache, "NORMAL", "MEMBERS_AND_ABOVE", null);
    }

    /**
     * 回覧板のソース情報を解決する。
     *
     * <p>
     * priority マッピング（設計書 §3）:
     * <ul>
     *   <li>URGENT / HIGH → URGENT</li>
     *   <li>NORMAL → NORMAL</li>
     *   <li>LOW → NORMAL</li>
     * </ul>
     * </p>
     *
     * <p>
     * {@code expiresAt} に {@code dueDate + 1日} をセット（締切翌日に失効）。
     * </p>
     */
    private SourceInfo resolveCirculationDocument(AnnouncementScopeType scopeType, Long scopeId, Long sourceId) {
        CirculationDocumentEntity doc = circulationDocumentRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // スコープ一致検証
        String expectedScopeType = scopeType.name();
        if (!expectedScopeType.equals(doc.getScopeType()) || !scopeId.equals(doc.getScopeId())) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        // priority マッピング（設計書 §3: 回覧板 URGENT→URGENT, それ以外→NORMAL）
        String priority = switch (doc.getPriority()) {
            case URGENT -> "URGENT";
            default -> "NORMAL";
        };

        String titleCache = truncate(doc.getTitle(), MAX_TITLE_CACHE_LENGTH);
        String excerptCache = resolveExcerpt(null, doc.getBody());

        // expires_at: dueDate + 1日（LocalDate → LocalDateTime）
        java.time.LocalDateTime expiresAt = doc.getDueDate() != null
                ? doc.getDueDate().plusDays(1).atStartOfDay()
                : null;

        return new SourceInfo(doc.getCreatedBy(), titleCache, excerptCache, priority, "MEMBERS_AND_ABOVE", expiresAt);
    }

    /**
     * アンケートのソース情報を解決する。
     *
     * <p>
     * アンケートは優先度なし → 常に NORMAL（設計書 §3）。
     * {@code expiresAt} に {@code surveys.expires_at} をコピー（締切と同時にお知らせも失効）。
     * </p>
     */
    private SourceInfo resolveSurvey(AnnouncementScopeType scopeType, Long scopeId, Long sourceId) {
        SurveyEntity survey = surveyRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // スコープ一致検証
        String expectedScopeType = scopeType.name();
        if (!expectedScopeType.equals(survey.getScopeType()) || !scopeId.equals(survey.getScopeId())) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        String titleCache = truncate(survey.getTitle(), MAX_TITLE_CACHE_LENGTH);
        String excerptCache = resolveExcerpt(null, survey.getDescription());

        // アンケート: 常に NORMAL（設計書 §3）
        return new SourceInfo(survey.getCreatedBy(), titleCache, excerptCache, "NORMAL", "MEMBERS_AND_ABOVE",
                survey.getExpiresAt());
    }

    /**
     * 委員会配信ログのソース情報を解決する（COMMITTEE_DECISION / COMMITTEE_MINUTES）。
     *
     * <p>
     * スコープは COMMITTEE 固定。sourceId = committee_distribution_logs.id を参照する。
     * 委員会配信ログには deleted_at カラムが存在しないため、sourceDeletedAt は null 固定。
     * priority は URGENT 固定（委員会からの伝達は常に重要と扱う）。
     * </p>
     *
     * @param scopeType     リクエストスコープ種別（COMMITTEE 以外は ANNOUNCE_005）
     * @param scopeId       委員会 ID
     * @param sourceId      委員会配信ログ ID
     * @param requestUserId リクエストユーザー ID
     * @return ソース情報レコード
     */
    private SourceInfo resolveCommitteeDistributionLog(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long sourceId,
            Long requestUserId) {

        CommitteeDistributionLogEntity log = committeeDistributionLogRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_006));

        // COMMITTEE スコープ以外からの参照は拒否
        if (!AnnouncementScopeType.COMMITTEE.equals(scopeType) || !scopeId.equals(log.getCommitteeId())) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_005);
        }

        // タイトル: customTitle → contentType で代替
        String titleCache = log.getCustomTitle() != null && !log.getCustomTitle().isBlank()
                ? truncate(log.getCustomTitle(), MAX_TITLE_CACHE_LENGTH)
                : truncate(log.getContentType(), MAX_TITLE_CACHE_LENGTH);

        String excerptCache = resolveExcerpt(null, log.getCustomBody());

        // 委員会からの伝達は常に URGENT（重要連絡）
        return new SourceInfo(log.getCreatedBy(), titleCache, excerptCache, "URGENT", "MEMBERS_AND_ABOVE", null);
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: テキスト処理
    // ═════════════════════════════════════════════════════════════

    /**
     * テキストを指定文字数に切り詰める。null の場合は空文字を返す。
     */
    public String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 抜粋テキストを解決する。
     * excerpt が存在すればそれを使用し、なければ body の先頭 150 文字を使用する。
     */
    public String resolveExcerpt(String excerpt, String body) {
        if (excerpt != null && !excerpt.isBlank()) {
            return truncate(excerpt, MAX_EXCERPT_CACHE_LENGTH);
        }
        if (body != null && !body.isBlank()) {
            return truncate(body, 150);
        }
        return null;
    }

    /**
     * ブログの Visibility を announcement_feeds の visibility 文字列にマッピングする。
     */
    public String mapBlogVisibility(Visibility visibility) {
        if (visibility == null) {
            return "MEMBERS_AND_ABOVE";
        }
        return switch (visibility) {
            case PUBLIC -> "PUBLIC";
            case SUPPORTERS_AND_ABOVE -> "SUPPORTERS_AND_ABOVE";
            default -> "MEMBERS_AND_ABOVE";
        };
    }

    // ═════════════════════════════════════════════════════════════
    // 返却型
    // ═════════════════════════════════════════════════════════════

    /**
     * ソースコンテンツから抽出した情報（内部用）。
     *
     * @param authorId     著者 ID
     * @param titleCache   タイトルキャッシュ
     * @param excerptCache 抜粋キャッシュ
     * @param priority     お知らせ優先度（"URGENT" / "IMPORTANT" / "NORMAL"）
     * @param visibility   閲覧可能範囲
     * @param expiresAt    表示終了日時（null = 期限なし）
     */
    public record SourceInfo(
            Long authorId,
            String titleCache,
            String excerptCache,
            String priority,
            String visibility,
            java.time.LocalDateTime expiresAt) {
    }
}
