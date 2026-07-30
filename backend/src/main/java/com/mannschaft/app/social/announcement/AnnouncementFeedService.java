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
 *   <li>一覧取得: 閲覧者ロールに応じた可視性集合で絞り込み（SUPPORTER は MEMBERS_AND_ABOVE を除外、MEMBER 以上は全種）</li>
 *   <li>お知らせ化: 著者本人または ADMIN/DEPUTY_ADMIN</li>
 *   <li>お知らせ解除: 著者本人または ADMIN/DEPUTY_ADMIN</li>
 *   <li>ピン留め: ADMIN/DEPUTY_ADMIN のみ</li>
 *   <li>既読マーク: <b>その閲覧者に一覧で見えているお知らせ</b>（＝可視性ベース。冪等）。
 *       一覧は非メンバーにも PUBLIC を返すため、既読も同じ集合に揃える
 *       （{@link AnnouncementReadService} のクラス Javadoc 参照）</li>
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
     * F09.17 Phase 11-b ε-B 広告主キャンペーン由来お知らせを登録する。
     *
     * <p>{@link AnnouncementCreationService#createFromBroadcast} とは異なり以下の特徴を持つ:</p>
     * <ul>
     *   <li>{@code scopeType = ADVERTISER_AD}, {@code scopeId = advertiser_accounts.id}</li>
     *   <li>{@code sourceType = ADVERTISER_CAMPAIGN}, {@code sourceId} は採番後の自フィード ID と
     *       一致させるため、まず仮 ID で保存して取得後に再代入する（自己参照）</li>
     *   <li>{@code authorId = null}（広告主由来のためユーザー所属を持たない）</li>
     *   <li>{@code isAdvertisement = true}（景品表示法対応の「広告」ラベル必須化）</li>
     *   <li>IDOR / 権限チェックは呼び出し元（{@code AdAnnouncementChannelService}）に委譲</li>
     * </ul>
     *
     * <p>戻り値は保存済み {@link AnnouncementFeedEntity}。呼び出し元はこの ID を
     * {@code ad_announcement_deliveries.announcement_feed_id} に転記する。</p>
     *
     * @param advertiserAccountId 広告主アカウント ID（scope_id）
     * @param campaignId          キャンペーン ID（ログ用、source_id へは入れない）
     * @param userId              受信者ユーザー ID（フィードは 1 ユーザー 1 行）
     * @param titleCache          タイトル（最大 200 文字、超過時は切り詰め）
     * @param excerptCache        本文抜粋（最大 300 文字、超過時は切り詰め）
     * @return 保存済みのお知らせフィードエンティティ
     */
    @Transactional
    public AnnouncementFeedEntity createAdvertiserFeed(
            Long advertiserAccountId,
            java.util.UUID campaignId,
            Long userId,
            String titleCache,
            String excerptCache) {

        // タイトル・本文の長さ補正（カラム制約に合わせる）
        String safeTitle = truncate(titleCache != null ? titleCache : "(広告)", 200);
        String safeExcerpt = excerptCache == null ? null : truncate(excerptCache, 300);

        // 仮の sourceId として広告主 ID + キャンペーン UUID の MSB を用いる。
        // 詳細: source_id 列は NOT NULL (BIGINT) のため一時値が必要。
        // 採番後の自己参照では UNIQUE 制約があるとぶつかるが、本テーブルに UNIQUE はない設計。
        long sourceIdSeed = campaignId.getLeastSignificantBits() & Long.MAX_VALUE;

        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ADVERTISER_AD)
                .scopeId(advertiserAccountId)
                .sourceType(AnnouncementSourceType.ADVERTISER_CAMPAIGN)
                .sourceId(sourceIdSeed)
                .authorId(null)
                .titleCache(safeTitle)
                .excerptCache(safeExcerpt)
                .priority("NORMAL")
                .visibility("MEMBERS_AND_ABOVE")
                .isAdvertisement(true)
                .build();

        AnnouncementFeedEntity saved = feedRepository.save(entity);
        log.info("広告お知らせフィード登録 feedId={} userId={} advertiserId={} campaignId={}",
                saved.getId(), userId, advertiserAccountId, campaignId);
        return saved;
    }

    /**
     * 指定長を超える文字列を切り詰める。null セーフ。
     */
    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
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
     * <p>スコープ（URL のパス変数由来）を下流へ通し、スコープ帰属検証と可視性検証を
     * {@link AnnouncementReadService} に行わせる（規則は「見える＝既読にできる」）。</p>
     *
     * @see AnnouncementReadService#markAsRead
     */
    @Transactional
    public void markAsRead(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        readService.markAsRead(scopeType, scopeId, announcementId, userId);
    }

    /**
     * スコープ内の全お知らせを既読にする。
     *
     * <p>下流は「可視かつ未読」を DB 側で絞り、{@link AnnouncementReadService#MARK_ALL_BATCH_SIZE}
     * 件ずつのチャンクで処理する（#2494）。実行コストは<b>未読件数</b>にのみ比例する。</p>
     *
     * <p><b>既知の未解消（#2494 の範囲外・#2530 で追跡）</b>: 下流
     * {@link AnnouncementReadService#markAllAsRead} は新規既読化件数を返すようになったが、
     * Controller の応答は<b>値もキー名も</b>設計書 F02.6 §4 と食い違っている。</p>
     * <ul>
     *   <li><b>値</b> — Controller は {@code markedCount} にハードコードの {@code 0} を返す
     *       （実件数を伝搬していない）</li>
     *   <li><b>キー名</b> — 設計書は {@code marked_count}（snake_case）、実装は {@code markedCount}</li>
     * </ul>
     * <p>応答は {@code Map<String, Object>} のため OpenAPI スキーマに現れず、
     * キー名の食い違いを機械的に検出する仕組みが無い。是正時は値だけでなくキー名も決着させること。
     * 本メソッドのシグネチャ（＝Controller の応答）に触れる修正になるため #2494 では手を入れていない。
     * 詳細は設計書 F02.6 §4 の {@code read-all} 節の ⚠️ ブロックを参照。</p>
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
     * 閲覧者ロールに応じた「閲覧できる visibility 集合」での絞り込みを
     * {@link AnnouncementFeedQueryRepository#findByScope} の WHERE 句で実施する（Service 層の if 文に依存しない）。
     * 集合は {@link AnnouncementVisibility#allowedFor(String)} が正準算出する:
     * SUPPORTER は {@code {PUBLIC, SUPPORTERS_AND_ABOVE}}（MEMBERS_AND_ABOVE を露出させない）、
     * MEMBER 以上は 3 種全部（PUBLIC/SUPPORTERS_AND_ABOVE を取りこぼさない）。
     * </p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID
     * @param requestUserId  リクエストユーザー ID
     * @param viewerRoleName 閲覧者の実ロール名（SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/PUBLIC）
     * @param cursor         カーソル（null の場合は先頭から）
     * @param limit          取得件数（0以下は DEFAULT_LIMIT、MAX_LIMIT 超は補正）
     * @return フィード取得結果（data / nextCursor / hasNext / unreadCount）
     */
    public AnnouncementFeedResult getAnnouncementFeed(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Long requestUserId,
            String viewerRoleName,
            Long cursor,
            int limit) {

        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        // 閲覧者ロール → 閲覧できる visibility 集合（正準）。
        Set<String> allowedVisibilities = AnnouncementVisibility.allowedFor(viewerRoleName);

        // limit + 1 件取得して hasNext を判定
        List<AnnouncementFeedEntity> rows = feedQueryRepository.findByScope(
                scopeType, scopeId, allowedVisibilities, cursor, effectiveLimit + 1);

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
