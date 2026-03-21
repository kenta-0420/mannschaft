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
