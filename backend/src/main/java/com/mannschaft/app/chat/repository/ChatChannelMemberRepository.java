package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * チャンネル内の「送信者以外」全メンバーの {@code unread_count} を一括インクリメントする
     * （未読カウント根治）。
     *
     * <p>{@code ChatChannelMemberEntity.incrementUnreadCount()} がプロダクションコード全域で
     * 呼び出し元ゼロの dead code だったため、メッセージ送信で受信者の未読カウントが一切増えず
     * {@link #sumUnreadCountByUserIdAndChannelIds} が常に 0 を返す構造的バグの根治対応。
     * メンバー数分の SELECT + entity 更新（N+1）を避けるため、1 回の一括 UPDATE 文で実装する。
     * {@link ChatChannelMemberEntity} 側の entity メソッドは使わない（1 件ずつの save が
     * 前提の設計であり、一括更新には向かないため）。</p>
     *
     * @param channelId チャンネルID
     * @param senderId  送信者ユーザーID（このユーザーの unread_count は増やさない）
     * @return 更新された行数
     */
    @Modifying
    @Query("UPDATE ChatChannelMemberEntity m SET m.unreadCount = m.unreadCount + 1 " +
           "WHERE m.channelId = :channelId AND m.userId <> :senderId")
    int incrementUnreadCountForOthers(@Param("channelId") Long channelId, @Param("senderId") Long senderId);
}
