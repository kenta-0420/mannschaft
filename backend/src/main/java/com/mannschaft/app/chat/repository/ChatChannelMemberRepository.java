package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * チャンネル内の「指定ユーザー以外」のメンバー行を取得する。
     * DM の相手（呼出ユーザー以外）解決に用いる。DM は 2 名構成のため通常 1 件返る。
     */
    List<ChatChannelMemberEntity> findByChannelIdAndUserIdNot(Long channelId, Long userId);

    /**
     * 複数チャンネルの「指定ユーザー以外」のメンバー行を一括取得する。
     * チャンネル一覧の DM 相手解決を N+1 なしで行うために用いる。
     */
    List<ChatChannelMemberEntity> findByChannelIdInAndUserIdNot(List<Long> channelIds, Long userId);

    /**
     * 複数チャンネルのメンバー数をまとめて集計する（チャンネル数に依存しない 1 クエリ）。
     */
    @Query("SELECT m.channelId AS channelId, COUNT(m) AS memberCount FROM ChatChannelMemberEntity m " +
           "WHERE m.channelId IN :channelIds GROUP BY m.channelId")
    List<ChannelMemberCount> countGroupedByChannelIds(@Param("channelIds") List<Long> channelIds);

    /**
     * {@link #countGroupedByChannelIds(List)} 用のインターフェース・プロジェクション。
     */
    interface ChannelMemberCount {
        Long getChannelId();

        long getMemberCount();
    }

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

    /**
     * 指定ユーザーの指定チャンネル群における未読件数の合計を返す（F10.7 業務アラート用）。
     */
    @Query("SELECT COALESCE(SUM(m.unreadCount), 0) FROM ChatChannelMemberEntity m " +
           "WHERE m.userId = :userId AND m.channelId IN :channelIds")
    int sumUnreadCountByUserIdAndChannelIds(@Param("userId") Long userId,
                                            @Param("channelIds") List<Long> channelIds);
}
