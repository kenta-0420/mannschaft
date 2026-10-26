package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
