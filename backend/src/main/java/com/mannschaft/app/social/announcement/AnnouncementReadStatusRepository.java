package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * お知らせ既読状態リポジトリ（F02.6）。
 *
 * <p>
 * {@code announcement_read_status} テーブルへのアクセス経路。
 * ウィジェットの未読バッジ・既読マーク・全件既読などの操作で使用する。
 * </p>
 */
public interface AnnouncementReadStatusRepository extends JpaRepository<AnnouncementReadStatusEntity, Long> {

    /**
     * フィード ID とユーザー ID で既読レコードを取得する（既読確認・冪等チェック用）。
     *
     * @param feedId お知らせフィード ID
     * @param userId ユーザー ID
     * @return 既読レコード（未読の場合は空）
     */
    Optional<AnnouncementReadStatusEntity> findByAnnouncementFeedIdAndUserId(Long feedId, Long userId);

    /**
     * 指定したお知らせフィードに紐づく全ての既読レコードを削除する。
     *
     * <p>
     * お知らせフィードを物理削除する際に呼び出す（CASCADE で削除されるが、
     * 明示的な呼び出しでバッチ処理の意図を明確にする場合にも使用可能）。
     * </p>
     *
     * @param feedId お知らせフィード ID
     */
    void deleteByAnnouncementFeedId(Long feedId);

    /**
     * ユーザーが指定したお知らせフィードリストのうち既読しているものの件数を返す。
     *
     * <p>
     * ウィジェット一覧取得時の未読数カウント算出（既読件数 = 総件数 - 未読件数）や
     * Valkey キャッシュの初期値設定に使用する。
     * </p>
     *
     * @param userId  ユーザー ID
     * @param feedIds お知らせフィード ID リスト
     * @return 既読件数
     */
    long countByUserIdAndAnnouncementFeedIdIn(Long userId, List<Long> feedIds);
}
