package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.visibility.ChatMessageVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * チャットメッセージリポジトリ。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** GDPR 本人エクスポート用。削除済み行は Entity の SQLRestriction に従い除外する。 */
    List<ChatMessageEntity> findBySenderIdOrderByCreatedAtDescIdDesc(Long senderId);

    /** 指定チャンネルに当該トークンを参照する招待カードが存在するか。取消時の path 束縛に使う。 */
    boolean existsByChannelIdAndInviteTokenIdAndMessageType(
            Long channelId, Long inviteTokenId, String messageType);

    String SEARCH_BY_CHANNEL = "SELECT * FROM chat_messages WHERE channel_id = :channelId AND deleted_at IS NULL AND MATCH(body) AGAINST(:keyword IN BOOLEAN MODE) ORDER BY created_at DESC, id DESC";
    String SEARCH_BY_CHANNELS = "SELECT * FROM chat_messages WHERE channel_id IN (:channelIds) AND deleted_at IS NULL AND MATCH(body) AGAINST(:keyword IN BOOLEAN MODE) ORDER BY created_at DESC, id DESC";

    /**
     * チャンネルのメッセージ一覧を新しい順に取得する。
     */
    List<ChatMessageEntity> findByChannelIdOrderByCreatedAtDescIdDesc(Long channelId, Pageable pageable);

    /**
     * チャンネルの最新の未削除メッセージを返す（部屋一覧プレビューの再計算用）。
     * {@code @SQLRestriction} により削除済みは含まれない。
     */
    Optional<ChatMessageEntity> findFirstByChannelIdOrderByCreatedAtDescIdDesc(Long channelId);

    /**
     * カーソル（メッセージID）の created_at を、論理削除済みの行も含めて取得する。
     * <p>境界の (created_at, id) はカーソル対象が削除されていても解決できる必要がある
     * （{@code @SQLRestriction} が副問い合わせにも効いて境界が NULL になり結果が空になるのを防ぐ）。
     * 結果本体は従来どおり削除済みを除外する。native のため deleted_at を問わない。</p>
     */
    @Query(value = "SELECT created_at FROM chat_messages WHERE id = :id", nativeQuery = true)
    Optional<LocalDateTime> findCreatedAtIncludingDeleted(@Param("id") Long id);

    /**
     * カーソル（指定ID）より前のメッセージを (created_at, id) 降順で取得する。
     * カーソル対象が論理削除済みでも境界を解決する。カーソル対象が存在しなければ空。
     */
    default List<ChatMessageEntity> findByChannelIdAndIdLessThan(
            Long channelId, Long cursorId, Pageable pageable) {
        return findCreatedAtIncludingDeleted(cursorId)
                .map(at -> findBeforeBoundary(channelId, at, cursorId, pageable))
                .orElseGet(List::of);
    }

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND (" +
            "m.createdAt < :cursorAt OR (m.createdAt = :cursorAt AND m.id < :cursorId)) " +
            "ORDER BY m.createdAt DESC, m.id DESC")
    List<ChatMessageEntity> findBeforeBoundary(
            @Param("channelId") Long channelId, @Param("cursorAt") LocalDateTime cursorAt,
            @Param("cursorId") Long cursorId, Pageable pageable);

    /**
     * カーソル（メッセージID）より新しいメッセージを (created_at, id) 昇順で取得する。
     * WebSocket切断後のキャッチアップ用。カーソル対象が論理削除済みでも境界を解決する。
     */
    default List<ChatMessageEntity> findMessagesAfterCursor(
            Long channelId, Long cursorId, Pageable pageable) {
        return findCreatedAtIncludingDeleted(cursorId)
                .map(at -> findAfterBoundary(channelId, at, cursorId, pageable))
                .orElseGet(List::of);
    }

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND (" +
            "m.createdAt > :cursorAt OR (m.createdAt = :cursorAt AND m.id > :cursorId)) " +
            "AND m.deletedAt IS NULL ORDER BY m.createdAt ASC, m.id ASC")
    List<ChatMessageEntity> findAfterBoundary(
            @Param("channelId") Long channelId, @Param("cursorAt") LocalDateTime cursorAt,
            @Param("cursorId") Long cursorId, Pageable pageable);

    /**
     * スレッド返信を取得する（旧2階層ロジック用）。
     */
    List<ChatMessageEntity> findByParentIdOrderByCreatedAtAscIdAsc(Long parentId);

    /**
     * スレッド内の全返信をフラット取得する（root_id でページング）。
     */
    Page<ChatMessageEntity> findByRootIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(Long rootId, Pageable pageable);

    /**
     * アクティブスレッド一覧を取得する（reply_count > 0 のトップレベルメッセージ）。
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.depth = 0 AND m.replyCount > 0 AND m.deletedAt IS NULL ORDER BY m.createdAt DESC, m.id DESC")
    Page<ChatMessageEntity> findActiveThreadsByChannelId(@Param("channelId") Long channelId, Pageable pageable);

    /**
     * チャンネルのピン留めメッセージを取得する。
     */
    List<ChatMessageEntity> findByChannelIdAndIsPinnedTrueOrderByCreatedAtDescIdDesc(Long channelId);

    /**
     * IDとチャンネルIDでメッセージを取得する。
     */
    Optional<ChatMessageEntity> findByIdAndChannelId(Long id, Long channelId);

    /**
     * チャンネルのメッセージを古い順に全件取得する（履歴コピー用）。
     */
    List<ChatMessageEntity> findByChannelIdOrderByCreatedAtAscIdAsc(Long channelId);

    /**
     * 特定送信者のメッセージ総件数を取得する（GDPR削除プレビュー用）。
     */
    long countBySenderId(Long senderId);

    /**
     * 予約送信バッチ対象メッセージを取得する。
     * <p>
     * 条件: scheduled_at &lt;= now かつ scheduled_sent_at IS NULL かつ deleted_at IS NULL
     * </p>
     * <p>
     * {@code @SQLRestriction("deleted_at IS NULL")} が Entity に付与されているため、
     * deleted_at IS NULL は自動的に適用される。
     * </p>
     *
     * @param now バッチ実行時刻
     * @return 未配信の予約送信メッセージ一覧
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.scheduledAt <= :now AND m.scheduledSentAt IS NULL")
    List<ChatMessageEntity> findPendingScheduledMessages(@Param("now") java.time.LocalDateTime now);

    /**
     * チャンネル内のメッセージを全文検索する。
     */
    @Query(value = SEARCH_BY_CHANNEL, nativeQuery = true)
    List<ChatMessageEntity> searchByKeyword(
            @Param("channelId") Long channelId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * 複数チャンネル横断でメッセージを全文検索する。
     */
    @Query(value = SEARCH_BY_CHANNELS, nativeQuery = true)
    List<ChatMessageEntity> searchByKeywordInChannels(
            @Param("channelIds") List<Long> channelIds, @Param("keyword") String keyword, Pageable pageable);

    // ====================================================================
    // F17.1 Phase 1 — 村ロビー検索 + ダッシュボード集約（B10 担当：読み取り専用）
    // ====================================================================

    /**
     * 村ロビーチャネル等の最新トップレベルメッセージ N 件を返す（F17.1 §4.13）。
     *
     * <p>parentId IS NULL でスレッド返信を除外。
     * 既存の {@link #findByChannelIdOrderByCreatedAtDescIdDesc} はスレッド返信も含めて返してしまうため
     * フィード用途では新規メソッドが必要。</p>
     */
    @Query("""
            SELECT m FROM ChatMessageEntity m
            WHERE m.channelId = :channelId
              AND m.parentId IS NULL
              AND m.deletedAt IS NULL
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<ChatMessageEntity> findLatestRootMessagesByChannelId(
            @Param("channelId") Long channelId, Pageable pageable);

    /**
     * {@link #findLatestRootMessagesByChannelId} のチャンネル ID 群版（村フィードの N+1 解消用）。
     * チャンネルごとに最新 {@code perChannel} 件を window 関数で切り出す。native のため
     * {@code deleted_at IS NULL} を明示する。
     */
    @Query(value = """
            SELECT x.* FROM (
              SELECT m.*, ROW_NUMBER() OVER (
                       PARTITION BY m.channel_id ORDER BY m.created_at DESC, m.id DESC) AS rn
              FROM chat_messages m
              WHERE m.channel_id IN (:channelIds)
                AND m.parent_id IS NULL
                AND m.deleted_at IS NULL
            ) x
            WHERE x.rn <= :perChannel
            ORDER BY x.created_at DESC, x.id DESC
            """, nativeQuery = true)
    List<ChatMessageEntity> findLatestRootMessagesByChannelIds(
            @Param("channelIds") java.util.Collection<Long> channelIds,
            @Param("perChannel") int perChannel);

    /**
     * 村ロビーチャネル内のメッセージを部分一致で検索する（F17.1 §4.12）。
     *
     * <p>既存の {@link #searchByKeyword} は FULLTEXT を使う native query で高速だが、
     * 村内検索の Phase 1 では短いキーワード（2 文字）も許可するため LIKE で実装。
     * FULLTEXT に揃える場合は B11 以降で要件再確認の上切替。</p>
     */
    @Query("""
            SELECT m FROM ChatMessageEntity m
            WHERE m.channelId = :channelId
              AND m.deletedAt IS NULL
              AND LOWER(m.body) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<ChatMessageEntity> searchByChannelIdAndKeyword(
            @Param("channelId") Long channelId,
            @Param("q") String q,
            Pageable pageable);

    /** 村ロビーチャネル内メッセージ検索結果件数（ページャ用）。 */
    @Query("""
            SELECT COUNT(m) FROM ChatMessageEntity m
            WHERE m.channelId = :channelId
              AND m.deletedAt IS NULL
              AND LOWER(m.body) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    long countByChannelIdAndKeyword(
            @Param("channelId") Long channelId,
            @Param("q") String q);

    // ====================================================================
    // F00 Phase B（積み残し根治） — CHAT_MESSAGE 可視性判定用 Projection
    // ====================================================================

    /**
     * {@link ChatMessageVisibilityResolver} 用の可視性 Projection を 1 SQL で取得する。
     *
     * <p>メッセージ自身は scope を持たないため、所属チャンネル（{@code chat_channels}）を
     * 結合して scope（{@code "TEAM"}/{@code "ORGANIZATION"}）と scopeId を導出する。
     * {@link ChatMessageEntity} / {@code ChatChannelEntity} 双方の
     * {@code @SQLRestriction("deleted_at IS NULL")} により、論理削除済のメッセージ・チャンネルは
     * 射影段階で自動除外される（取得不可 → fail-closed）。DM・村ロビー等 team/org を持たない
     * チャンネルは scopeType/scopeId が null となり、基底の SCOPE_AFFILIATED 判定で不可視になる。</p>
     *
     * <p>チャンネルの {@code is_private} / {@code is_inquiry_channel} も射影し、Resolver 側で
     * PRIVATE=fail-closed / 問い合わせ=管理者限定の粒度制御に用いる（検分是正 2026-07-11）。</p>
     *
     * <p>{@code AbstractContentVisibilityResolver#loadProjections} からのみ呼ばれ、
     * 戻り値の順序は保証しない。</p>
     *
     * @param ids 取得対象 chat_messages.id 集合（空の場合は空 List を返す）
     * @return 実存するメッセージの可視性 Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.chat.visibility.ChatMessageVisibilityProjection(
                m.id,
                CASE WHEN c.teamId IS NOT NULL THEN 'TEAM'
                     WHEN c.organizationId IS NOT NULL THEN 'ORGANIZATION'
                     ELSE NULL END,
                COALESCE(c.teamId, c.organizationId),
                m.senderId,
                c.isPrivate,
                c.isInquiryChannel)
            FROM ChatMessageEntity m, ChatChannelEntity c
            WHERE m.id IN :ids AND c.id = m.channelId
            """)
    List<ChatMessageVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
