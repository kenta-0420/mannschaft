package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チャンネルメンバーリポジトリ。
 */
public interface ChatChannelMemberRepository extends JpaRepository<ChatChannelMemberEntity, Long> {

    /**
     * チャンネルのメンバー一覧を取得する。
     */
    List<ChatChannelMemberEntity> findByChannelIdOrderByJoinedAtAsc(Long channelId);

    /**
     * チャンネルとユーザーでメンバーを取得する。
     */
    Optional<ChatChannelMemberEntity> findByChannelIdAndUserId(Long channelId, Long userId);

    /**
     * チャンネルにユーザーが参加しているか確認する。
     */
    boolean existsByChannelIdAndUserId(Long channelId, Long userId);

    /**
     * チャンネルのメンバー数を取得する。
     */
    long countByChannelId(Long channelId);

    /**
     * ユーザーが参加しているチャンネルIDリストを取得する。
     */
    List<ChatChannelMemberEntity> findByUserId(Long userId);

    /**
     * チャンネルのメンバーを全件削除する。
     */
    void deleteByChannelId(Long channelId);

    /**
     * チャンネルとユーザーでメンバーを削除する。
     */
    void deleteByChannelIdAndUserId(Long channelId, Long userId);
}
