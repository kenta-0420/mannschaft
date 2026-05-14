package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
