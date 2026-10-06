package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * メッセージブックマークリポジトリ。
 */
public interface ChatMessageBookmarkRepository extends JpaRepository<ChatMessageBookmarkEntity, Long> {

    /**
     * ユーザーのブックマーク一覧を取得する。
     */
    List<ChatMessageBookmarkEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーとメッセージでブックマークを取得する。
     */
    Optional<ChatMessageBookmarkEntity> findByUserIdAndMessageId(Long userId, Long messageId);

    /**
     * ブックマークが存在するか確認する。
     */
    boolean existsByUserIdAndMessageId(Long userId, Long messageId);

    /**
     * ユーザーとメッセージでブックマークを削除する。
     */
    void deleteByUserIdAndMessageId(Long userId, Long messageId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM chat_message_bookmarks WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
