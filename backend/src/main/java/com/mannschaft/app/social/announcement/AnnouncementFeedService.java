package com.mannschaft.app.social.announcement;

import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * お知らせウィジェットフィードサービス（F02.6）。
 *
 * <p>
 * チーム・組織ダッシュボードの「お知らせウィジェット」に表示するフィードの
 * 取得・削除・ピン留めを担う。
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
 *
 * <p>
 * お知らせ作成は {@link AnnouncementCreationService}、
 * 既読管理は {@link AnnouncementReadService} に委譲している。
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

    private final AnnouncementFeedRepository feedRepository;
    private final AnnouncementFeedQueryRepository feedQueryRepository;
    private final AnnouncementReadService readService;
    private final AnnouncementCreationService creationService;
    private final AccessControlService accessControlService;

    // ── 委員会関連リポジトリ（COMMITTEE スコープサポート用） ──
    private final CommitteeMemberRepository committeeMemberRepository;

    // ═════════════════════════════════════════════════════════════
    // 委譲: お知らせ作成（AnnouncementCreationService へ委譲）
    // ═════════════════════════════════════════════════════════════

    /**
     * コンテンツをお知らせウィジェットに登録する。
     *
     * @see AnnouncementCreationService#createAnnouncement
     */
    @Transactional
    public AnnouncementFeedEntity createAnnouncement(
            AnnouncementScopeType scopeType,
            Long scopeId,
            AnnouncementSourceType sourceType,
            Long sourceId,
            Long requestUserId) {
        return creationService.createAnnouncement(scopeType, scopeId, sourceType, sourceId, requestUserId);
    }

    /**
     * アンケート・回覧板の公開時に自動お知らせ化する（Service 層内部から呼ぶ）。
     *
     * @see AnnouncementCreationService#createFromSource
     */
    @Transactional
    public AnnouncementFeedEntity createFromSource(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long authorId) {
        return creationService.createFromSource(sourceType, sourceId, scopeType, scopeId, authorId);
    }

    /**
     * 告知ウィザード（F02.8）経由でお知らせフィードを登録する。
     *
     * @see AnnouncementCreationService#createFromBroadcast
     */
    @Transactional
    public AnnouncementFeedEntity createFromBroadcast(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long authorId,
            String priority,
            java.time.LocalDateTime expiresAt,
            String targetTeamIds,
            String titleCache,
            String visibility) {
        return creationService.createFromBroadcast(
                sourceType, sourceId, scopeType, scopeId, authorId,
                priority, expiresAt, targetTeamIds, titleCache, visibility);
    }

    // ═════════════════════════════════════════════════════════════
    // 委譲: 既読管理（AnnouncementReadService へ委譲）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * @see AnnouncementReadService#markAsRead
     */
    @Transactional
    public void markAsRead(Long announcementId, Long userId) {
        readService.markAsRead(announcementId, userId);
    }

    /**
     * スコープ内の全お知らせを既読にする。
     *
     * @see AnnouncementReadService#markAllAsRead
     */
    @Transactional
    public void markAllAsRead(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        readService.markAllAsRead(scopeType, scopeId, userId);
    }

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
        Set<Long> readFeedIds = readService.fetchReadFeedIds(requestUserId, feedIds);

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
}
