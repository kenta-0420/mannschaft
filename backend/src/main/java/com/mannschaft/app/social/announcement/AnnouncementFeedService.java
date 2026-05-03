package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.circulation.CirculationPriority;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * お知らせウィジェットフィードサービス（F02.6）。
 *
 * <p>
 * チーム・組織ダッシュボードの「お知らせウィジェット」に表示するフィードの
 * 取得・登録・削除・ピン留め・既読管理を担う。
 * </p>
 *
 * <p>
 * <b>IDOR 対策</b>:
 * {@code createAnnouncement} 時に {@code source_id} の実スコープと
 * パス変数の {@code scopeId} を照合し、他スコープのコンテンツのお知らせ化を防ぐ。
 * 詳細は設計書 §6.1 を参照。
 * </p>
 *
 * <p>
 * <b>権限モデル</b>:
 * <ul>
 *   <li>一覧取得: メンバー以上（SUPPORTER は MEMBERS_ONLY を除外）</li>
 *   <li>お知らせ化: 著者本人または ADMIN/DEPUTY_ADMIN</li>
 *   <li>お知らせ解除: 著者本人または ADMIN/DEPUTY_ADMIN</li>
 *   <li>ピン留め: ADMIN/DEPUTY_ADMIN のみ</li>
 *   <li>既読マーク: メンバー以上（冪等）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementFeedService {

    /** デフォルト取得件数 */
    private static final int DEFAULT_LIMIT = 20;

    /** 最大取得件数 */
    private static final int MAX_LIMIT = 50;

    /** ピン留め最大件数（スコープごと） */
    private static final int MAX_PIN_COUNT = 5;

    /** タイトルキャッシュの最大文字数 */
    private static final int MAX_TITLE_CACHE_LENGTH = 200;

    /** 抜粋キャッシュの最大文字数 */
    private static final int MAX_EXCERPT_CACHE_LENGTH = 300;

    private final AnnouncementFeedRepository feedRepository;
    private final AnnouncementFeedQueryRepository feedQueryRepository;
    private final AnnouncementReadStatusRepository readStatusRepository;
    private final AccessControlService accessControlService;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;

    // ── ソースリポジトリ（IDOR 検証・タイトル/excerpt 取得用） ──
    private final BlogPostRepository blogPostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final CirculationDocumentRepository circulationDocumentRepository;
    private final SurveyRepository surveyRepository;

    // ── 委員会関連リポジトリ（COMMITTEE スコープサポート用） ──
    private final CommitteeDistributionLogRepository committeeDistributionLogRepository;
    private final CommitteeMemberRepository committeeMemberRepository;

    // ═════════════════════════════════════════════════════════════
    // 2.1 一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせフィード一覧をカーソルページングで取得する。
     *
     * <p>
     * visibility に応じて SUPPORTER への MEMBERS_ONLY コンテンツ除外フィルタを
     * {@link AnnouncementFeedQueryRepository#findByScope} の WHERE 句で実施する（Service 層の if 文に依存しない）。
     * </p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID
     * @param requestUserId  リクエストユーザー ID
     * @param userVisibility ロールに応じた visibility 指定値（"MEMBER" or "SUPPORTER"）
     * @param cursor         カーソル（null の場合は先頭から）
     * @param limit          取得件数（0以下は DEFAULT_LIMIT、MAX_LIMIT 超は補正）
     * @return フィード取得結果（data / nextCursor / hasNext / unreadCount）
     */
    public AnnouncementFeedResult getAnnouncementFeed(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long requestUserId,
            String userVisibility,
            Long cursor,
            int limit) {

        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        // visibility 変換: "MEMBER" → "MEMBERS_ONLY", "SUPPORTER" → "SUPPORTERS_AND_ABOVE"
        String visibilityParam = "SUPPORTER".equalsIgnoreCase(userVisibility)
                ? "SUPPORTERS_AND_ABOVE"
                : "MEMBERS_ONLY";

        // limit + 1 件取得して hasNext を判定
        List<AnnouncementFeedEntity> rows = feedQueryRepository.findByScope(
                scopeType, scopeId, visibilityParam, cursor, effectiveLimit + 1);

        boolean hasNext = rows.size() > effectiveLimit;
        List<AnnouncementFeedEntity> dataRows = hasNext ? rows.subList(0, effectiveLimit) : rows;

        // 既読状態をバッチ取得（N+1 防止）
        List<Long> feedIds = dataRows.stream().map(AnnouncementFeedEntity::getId).toList();
        Set<Long> readFeedIds = fetchReadFeedIds(requestUserId, feedIds);

        // 未読数: スコープ内の全フィード件数 - 既読件数
        long totalCount = (long) feedIds.size();
        long readCount = readFeedIds.size();
        long unreadCount = totalCount - readCount;

        List<AnnouncementFeedItem> items = dataRows.stream()
                .map(feed -> new AnnouncementFeedItem(feed, readFeedIds.contains(feed.getId())))
                .toList();

        Long nextCursor = null;
        if (hasNext && !dataRows.isEmpty()) {
            nextCursor = dataRows.get(dataRows.size() - 1).getId();
        }

        return new AnnouncementFeedResult(items, nextCursor, hasNext, unreadCount);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.2 お知らせ化（公開登録）
    // ═════════════════════════════════════════════════════════════

    /**
     * コンテンツをお知らせウィジェットに登録する。
     *
     * <p>
     * <b>IDOR 検証フロー</b>:
     * <ol>
     *   <li>source_type に応じて元コンテンツを取得（存在しなければ ANNOUNCE_006）</li>
     *   <li>個人ブログ・ソーシャルプロフィール投稿を拒否（ANNOUNCE_007）</li>
     *   <li>元コンテンツの scope が request の scopeId と一致するか検証（ANNOUNCE_005）</li>
     *   <li>重複登録を拒否（ANNOUNCE_003）</li>
     *   <li>権限チェック：著者本人または ADMIN+（ANNOUNCE_002）</li>
     * </ol>
     * </p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param sourceType    元コンテンツ種別
     * @param sourceId      元コンテンツ ID
     * @param requestUserId リクエストユーザー ID
     * @return 作成したお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createAnnouncement(
            AnnouncementScopeType scopeType,
            Long scopeId,
            AnnouncementSourceType sourceType,
            Long sourceId,
            Long requestUserId) {

        // ── ソースコンテンツ検証・情報取得 ──
        SourceInfo sourceInfo = resolveSourceInfo(scopeType, scopeId, sourceType, sourceId, requestUserId);

        // ── 重複登録チェック ──
        feedRepository.findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
                sourceType, sourceId, scopeType, scopeId)
                .ifPresent(existing -> {
                    throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_003);
                });

        // ── 権限チェック: 著者本人または ADMIN+（COMMITTEE スコープはメンバーチェック） ──
        boolean isAuthor = requestUserId.equals(sourceInfo.authorId());
        boolean isAdmin = AnnouncementScopeType.COMMITTEE.equals(scopeType)
                ? committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(scopeId, requestUserId)
                : accessControlService.isAdminOrAbove(requestUserId, scopeId, scopeType.name());
        if (!isAuthor && !isAdmin) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_002);
        }

        // ── エンティティ作成 ──
        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .authorId(requestUserId)
                .titleCache(sourceInfo.titleCache())
                .excerptCache(sourceInfo.excerptCache())
                .priority(sourceInfo.priority())
                .visibility(sourceInfo.visibility())
                .expiresAt(sourceInfo.expiresAt())
                .build();

        return feedRepository.save(entity);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.3 お知らせ解除（削除）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせウィジェットからコンテンツを解除（物理削除）する。
     *
     * @param announcementId お知らせフィード ID
     * @param requestUserId  リクエストユーザー ID
     */
    @Transactional
    public void deleteAnnouncement(Long announcementId, Long requestUserId) {
        AnnouncementFeedEntity entity = feedRepository.findById(announcementId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_001));

        // 著者本人または ADMIN+（COMMITTEE スコープはメンバーチェック） のみ削除可
        boolean isAuthor = requestUserId.equals(entity.getAuthorId());
        boolean isAdmin = AnnouncementScopeType.COMMITTEE.equals(entity.getScopeType())
                ? committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(
                        entity.getScopeId(), requestUserId)
                : accessControlService.isAdminOrAbove(
                        requestUserId, entity.getScopeId(), entity.getScopeType().name());
        if (!isAuthor && !isAdmin) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_002);
        }

        feedRepository.delete(entity);
        log.debug("お知らせ解除完了 announcementId={}, requestUserId={}", announcementId, requestUserId);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.4 ピン留めトグル
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせのピン留め状態を切り替える。
     *
     * <p>
     * ピン留め ON への変更時にスコープ内の現在のピン留め数が上限（5件）に達していないことを確認する。
     * </p>
     *
     * @param announcementId お知らせフィード ID
     * @param requestUserId  リクエストユーザー ID（ADMIN+ のみ可）
     * @return 更新後のお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity togglePin(Long announcementId, Long requestUserId) {
        AnnouncementFeedEntity entity = feedRepository.findById(announcementId)
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_001));

        // ADMIN+（COMMITTEE スコープはメンバーチェック） のみピン留め操作可能
        boolean canPin = AnnouncementScopeType.COMMITTEE.equals(entity.getScopeType())
                ? committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(
                        entity.getScopeId(), requestUserId)
                : accessControlService.isAdminOrAbove(
                        requestUserId, entity.getScopeId(), entity.getScopeType().name());
        if (!canPin) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_002);
        }

        if (Boolean.TRUE.equals(entity.getIsPinned())) {
            // ピン留め → 解除
            entity.unpin();
            log.debug("ピン留め解除 announcementId={}, requestUserId={}", announcementId, requestUserId);
        } else {
            // 未ピン → ピン留め: 上限チェック
            long currentPinCount = feedRepository.countByScopeTypeAndScopeIdAndIsPinnedTrueAndSourceDeletedAtIsNull(
                    entity.getScopeType(), entity.getScopeId());
            if (currentPinCount >= MAX_PIN_COUNT) {
                throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_004);
            }
            entity.markPinned(requestUserId);
            log.debug("ピン留め設定 announcementId={}, requestUserId={}", announcementId, requestUserId);
        }

        return feedRepository.save(entity);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.5 既読マーク（単件）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * <p>
     * 既に既読レコードが存在する場合は何もしない。
     * </p>
     *
     * @param announcementId お知らせフィード ID
     * @param userId         ユーザー ID
     */
    @Transactional
    public void markAsRead(Long announcementId, Long userId) {
        // お知らせ存在確認
        if (!feedRepository.existsById(announcementId)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_001);
        }

        // 冪等: 既読済みなら何もしない
        boolean alreadyRead = readStatusRepository
                .findByAnnouncementFeedIdAndUserId(announcementId, userId)
                .isPresent();
        if (alreadyRead) {
            return;
        }

        AnnouncementReadStatusEntity status = AnnouncementReadStatusEntity.builder()
                .announcementFeedId(announcementId)
                .userId(userId)
                .build();
        status = readStatusRepository.save(status);

        // 代理確認の場合: proxy_input_records を作成し、is_proxy_confirmed フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveAnnouncementProxyRecord(
                    "ANNOUNCEMENT_READ", announcementId);
            readStatusRepository.save(status.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.debug("既読マーク完了 announcementId={}, userId={}", announcementId, userId);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.6 全件既読
    // ═════════════════════════════════════════════════════════════

    /**
     * スコープ内の全お知らせを既読にする。
     *
     * <p>
     * 既読済みのものは除外し、未読のものだけを既読登録する。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param userId    ユーザー ID
     */
    @Transactional
    public void markAllAsRead(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        // スコープ内の有効なフィード一覧を取得
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<AnnouncementFeedEntity> feeds = feedRepository
                .findByScopeTypeAndScopeIdAndSourceDeletedAtIsNull(scopeType, scopeId, sort);

        if (feeds.isEmpty()) {
            return;
        }

        List<Long> feedIds = feeds.stream().map(AnnouncementFeedEntity::getId).toList();

        // 既読済みのフィード ID セットを取得
        Set<Long> alreadyReadFeedIds = fetchReadFeedIds(userId, feedIds);

        // 未読のものだけ既読登録
        List<AnnouncementReadStatusEntity> newReadStatuses = feedIds.stream()
                .filter(feedId -> !alreadyReadFeedIds.contains(feedId))
                .map(feedId -> AnnouncementReadStatusEntity.builder()
                        .announcementFeedId(feedId)
                        .userId(userId)
                        .build())
                .toList();

        if (!newReadStatuses.isEmpty()) {
            readStatusRepository.saveAll(newReadStatuses);
            log.debug("全件既読マーク完了 scopeType={}, scopeId={}, userId={}, count={}",
                    scopeType, scopeId, userId, newReadStatuses.size());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 2.7 自動お知らせ化（アンケート・回覧板から呼ぶ）
    // ═════════════════════════════════════════════════════════════

    /**
     * アンケート・回覧板の公開時に自動お知らせ化する（Service 層内部から呼ぶ）。
     *
     * <p>
     * {@link #createAnnouncement} の内部ロジックを流用しつつ、
     * 重複時は例外を投げずに既存レコードを返す。
     * 呼び出し元 Service（SurveyService / CirculationService）が認可チェック済みの前提で動作する。
     * </p>
     *
     * @param sourceType ソース種別
     * @param sourceId   ソース ID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープ ID
     * @param authorId   登録者 ID（アンケート・回覧板の作成者）
     * @return 作成または既存のお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createFromSource(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long authorId) {

        // 重複チェック: 既存レコードがあれば返す（例外なし）
        Optional<AnnouncementFeedEntity> existing = feedRepository
                .findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(sourceType, sourceId, scopeType, scopeId);
        if (existing.isPresent()) {
            log.debug("自動お知らせ化スキップ（既存） sourceType={}, sourceId={}, scopeType={}, scopeId={}",
                    sourceType, sourceId, scopeType, scopeId);
            return existing.get();
        }

        // ソース情報取得（IDOR 検証は呼び出し元が保証。スコープ一致チェックのみ実施）
        SourceInfo sourceInfo = resolveSourceInfo(scopeType, scopeId, sourceType, sourceId, authorId);

        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .authorId(authorId)
                .titleCache(sourceInfo.titleCache())
                .excerptCache(sourceInfo.excerptCache())
                .priority(sourceInfo.priority())
                .visibility(sourceInfo.visibility())
                .expiresAt(sourceInfo.expiresAt())
                .build();

        AnnouncementFeedEntity saved = feedRepository.save(entity);
        log.info("自動お知らせ化完了 sourceType={}, sourceId={}, scopeType={}, scopeId={}",
                sourceType, sourceId, scopeType, scopeId);
        return saved;
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: 代理入力記録作成
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせ代理確認の proxy_input_records を作成して保存する（冪等性チェック付き）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済みの代理入力記録エンティティ
     */
    private ProxyInputRecordEntity buildAndSaveAnnouncementProxyRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("ANNOUNCEMENT_READ")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: ソース情報解決（IDOR 検証・タイトル/excerpt 取得）
    // ═════════════════════════════════════════════════════════════

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
    private SourceInfo resolveSourceInfo(
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

        // 掲示板は MEMBERS_ONLY 固定（掲示板自体がメンバー限定のため）
        return new SourceInfo(thread.getAuthorId(), titleCache, excerptCache, priority, "MEMBERS_ONLY", null);
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
        return new SourceInfo(post.getUserId(), titleCache, excerptCache, "NORMAL", "MEMBERS_ONLY", null);
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

        return new SourceInfo(doc.getCreatedBy(), titleCache, excerptCache, priority, "MEMBERS_ONLY", expiresAt);
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
        return new SourceInfo(survey.getCreatedBy(), titleCache, excerptCache, "NORMAL", "MEMBERS_ONLY",
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
        return new SourceInfo(log.getCreatedBy(), titleCache, excerptCache, "URGENT", "MEMBERS_ONLY", null);
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: 既読 ID セット取得（N+1 防止）
    // ═════════════════════════════════════════════════════════════

    /**
     * ユーザーが既読しているフィード ID のセットをバッチ取得する（N+1 防止）。
     *
     * @param userId  ユーザー ID
     * @param feedIds フィード ID リスト
     * @return 既読済みフィード ID セット
     */
    private Set<Long> fetchReadFeedIds(Long userId, List<Long> feedIds) {
        if (feedIds.isEmpty()) {
            return Set.of();
        }
        // AnnouncementReadStatusRepository から既読レコードをバッチ取得
        return feedIds.stream()
                .filter(feedId -> readStatusRepository
                        .findByAnnouncementFeedIdAndUserId(feedId, userId)
                        .isPresent())
                .collect(Collectors.toSet());
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: テキスト処理
    // ═════════════════════════════════════════════════════════════

    /**
     * テキストを指定文字数に切り詰める。null の場合は空文字を返す。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 抜粋テキストを解決する。
     * excerpt が存在すればそれを使用し、なければ body の先頭 150 文字を使用する。
     */
    private String resolveExcerpt(String excerpt, String body) {
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
    private String mapBlogVisibility(Visibility visibility) {
        if (visibility == null) {
            return "MEMBERS_ONLY";
        }
        return switch (visibility) {
            case PUBLIC -> "PUBLIC";
            case SUPPORTERS_AND_ABOVE -> "SUPPORTERS_AND_ABOVE";
            default -> "MEMBERS_ONLY";
        };
    }

    // ═════════════════════════════════════════════════════════════
    // 返却型
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせフィード取得結果。
     *
     * @param data        お知らせフィードアイテムリスト
     * @param nextCursor  次ページカーソル（null = 次ページなし）
     * @param hasNext     次ページがあるか
     * @param unreadCount 未読件数
     */
    public record AnnouncementFeedResult(
            List<AnnouncementFeedItem> data,
            Long nextCursor,
            boolean hasNext,
            long unreadCount) {
    }

    /**
     * お知らせフィード 1 件分のアイテム（既読状態付き）。
     *
     * @param feed   お知らせフィードエンティティ
     * @param isRead 既読済みかどうか
     */
    public record AnnouncementFeedItem(
            AnnouncementFeedEntity feed,
            boolean isRead) {
    }

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
    private record SourceInfo(
            Long authorId,
            String titleCache,
            String excerptCache,
            String priority,
            String visibility,
            java.time.LocalDateTime expiresAt) {
    }
}
