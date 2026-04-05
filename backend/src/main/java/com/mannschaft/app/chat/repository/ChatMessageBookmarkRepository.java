package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
