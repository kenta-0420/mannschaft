package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.visibility.ChatMessageVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * チャットメッセージリポジトリ。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    String SEARCH_BY_CHANNEL = "SELECT * FROM chat_messages WHERE channel_id = :channelId AND deleted_at IS NULL AND MATCH(body) AGAINST(:keyword IN BOOLEAN MODE) ORDER BY created_at DESC";
    String SEARCH_BY_CHANNELS = "SELECT * FROM chat_messages WHERE channel_id IN (:channelIds) AND deleted_at IS NULL AND MATCH(body) AGAINST(:keyword IN BOOLEAN MODE) ORDER BY created_at DESC";

    /**
     * チャンネルのメッセージ一覧を新しい順に取得する。
     */
    List<ChatMessageEntity> findByChannelIdOrderByCreatedAtDesc(Long channelId, Pageable pageable);

    /**
     * カーソル（指定ID）より前のメッセージを取得する。
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.id < :cursorId " +
            "ORDER BY m.createdAt DESC")
    List<ChatMessageEntity> findByChannelIdAndIdLessThan(
            @Param("channelId") Long channelId, @Param("cursorId") Long cursorId, Pageable pageable);

    /**
     * カーソル（メッセージID）より新しいメッセージを昇順で取得する。
     * WebSocket切断後のキャッチアップ用。cursor より大きいIDを持つメッセージを
     * 古い順（ASC）で返すことで、切断中に積まれたメッセージを時系列順に復元できる。
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.id > :cursorId " +
            "AND m.deletedAt IS NULL ORDER BY m.id ASC")
    List<ChatMessageEntity> findMessagesAfterCursor(
            @Param("channelId") Long channelId,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * スレッド返信を取得する（旧2階層ロジック用）。
     */
    List<ChatMessageEntity> findByParentIdOrderByCreatedAtAsc(Long parentId);

    /**
     * スレッド内の全返信をフラット取得する（root_id でページング）。
     */
    Page<ChatMessageEntity> findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long rootId, Pageable pageable);

    /**
     * アクティブスレッド一覧を取得する（reply_count > 0 のトップレベルメッセージ）。
     */
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.depth = 0 AND m.replyCount > 0 AND m.deletedAt IS NULL ORDER BY m.createdAt DESC")
    Page<ChatMessageEntity> findActiveThreadsByChannelId(@Param("channelId") Long channelId, Pageable pageable);

    /**
     * チャンネルのピン留めメッセージを取得する。
     */
    List<ChatMessageEntity> findByChannelIdAndIsPinnedTrueOrderByCreatedAtDesc(Long channelId);

    /**
     * IDとチャンネルIDでメッセージを取得する。
     */
    Optional<ChatMessageEntity> findByIdAndChannelId(Long id, Long channelId);

    /**
     * チャンネルのメッセージを古い順に全件取得する（履歴コピー用）。
     */
    List<ChatMessageEntity> findByChannelIdOrderByCreatedAtAsc(Long channelId);

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
     * 既存の {@link #findByChannelIdOrderByCreatedAtDesc} はスレッド返信も含めて返してしまうため
     * フィード用途では新規メソッドが必要。</p>
     */
    @Query("""
            SELECT m FROM ChatMessageEntity m
            WHERE m.channelId = :channelId
              AND m.parentId IS NULL
              AND m.deletedAt IS NULL
            ORDER BY m.createdAt DESC
            """)
    List<ChatMessageEntity> findLatestRootMessagesByChannelId(
            @Param("channelId") Long channelId, Pageable pageable);

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
            ORDER BY m.createdAt DESC
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
                m.senderId)
            FROM ChatMessageEntity m, ChatChannelEntity c
            WHERE m.id IN :ids AND c.id = m.channelId
            """)
    List<ChatMessageVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
