package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * メッセージリアクションリポジトリ。
 */
public interface ChatMessageReactionRepository extends JpaRepository<ChatMessageReactionEntity, Long> {

    /**
     * メッセージのリアクション一覧を取得する。
     */
    List<ChatMessageReactionEntity> findByMessageId(Long messageId);

    /**
     * 複数メッセージのリアクションを ID 昇順で一括取得する（一覧の N+1 解消用）。
     * 呼び出し側は {@code messageIds} が空の場合に本メソッドを呼ばないこと。
     */
    List<ChatMessageReactionEntity> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    /**
     * メッセージ・ユーザー・絵文字でリアクションを取得する。
     */
    Optional<ChatMessageReactionEntity> findByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    /**
     * メッセージ・ユーザー・絵文字でリアクションが存在するか確認する。
     */
    boolean existsByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    /**
     * メッセージ・ユーザー・絵文字でリアクションを削除する。
     */
    void deleteByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);
}
