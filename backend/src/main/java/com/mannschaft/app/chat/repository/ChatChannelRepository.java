package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * チャットチャンネルリポジトリ。
 */
public interface ChatChannelRepository extends JpaRepository<ChatChannelEntity, Long> {

    /**
     * チームのチャンネル一覧を取得する（アーカイブ除外）。
     */
    List<ChatChannelEntity> findByTeamIdAndIsArchivedFalseOrderByLastMessageAtDesc(Long teamId);

    /**
     * 組織のチャンネル一覧を取得する（アーカイブ除外）。
     */
    List<ChatChannelEntity> findByOrganizationIdAndIsArchivedFalseOrderByLastMessageAtDesc(Long organizationId);

    /**
     * ユーザーが参加しているチャンネル一覧を取得する。
     */
    @Query("SELECT c FROM ChatChannelEntity c JOIN ChatChannelMemberEntity m ON c.id = m.channelId " +
            "WHERE m.userId = :userId AND c.isArchived = false ORDER BY c.lastMessageAt DESC")
    List<ChatChannelEntity> findByMemberUserId(@Param("userId") Long userId);

    /**
     * 2人の間に既存のDMチャンネルが存在するか検索する。
     * chat_channel_members に両ユーザーが同一チャンネルに属し、かつ channelType = DM であるチャンネルを返す。
     */
    @Query("""
            SELECT c FROM ChatChannelEntity c
            WHERE c.channelType = 'DM'
              AND c.isArchived = false
              AND c.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM ChatChannelMemberEntity m1 WHERE m1.channelId = c.id AND m1.userId = :userId1)
              AND EXISTS (SELECT 1 FROM ChatChannelMemberEntity m2 WHERE m2.channelId = c.id AND m2.userId = :userId2)
            """)
    Optional<ChatChannelEntity> findExistingDm(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 2人の間のDMチャンネルを取得する（アーカイブ済みも含む）。ブロック時のアーカイブ処理用。
     */
    @Query("""
            SELECT c FROM ChatChannelEntity c
            WHERE c.channelType = 'DM'
              AND c.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM ChatChannelMemberEntity m1 WHERE m1.channelId = c.id AND m1.userId = :userId1)
              AND EXISTS (SELECT 1 FROM ChatChannelMemberEntity m2 WHERE m2.channelId = c.id AND m2.userId = :userId2)
            """)
    Optional<ChatChannelEntity> findDmChannelBetween(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * ソースタイプとソースIDでチャンネルを取得する。
     */
    Optional<ChatChannelEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * チーム内で同名のチャンネルが存在するか確認する。
     */
    boolean existsByTeamIdAndNameAndDeletedAtIsNull(Long teamId, String name);

    /**
     * 組織内で同名のチャンネルが存在するか確認する。
     */
    boolean existsByOrganizationIdAndNameAndDeletedAtIsNull(Long organizationId, String name);
}
