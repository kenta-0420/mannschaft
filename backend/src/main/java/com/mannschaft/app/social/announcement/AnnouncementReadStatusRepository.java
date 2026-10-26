package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
public interface AnnouncementReadStatusRepository extends JpaRepository<AnnouncementReadStatusEntity, Long>,
        JpaSpecificationExecutor<AnnouncementReadStatusEntity> {

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

    /**
     * ユーザーが既読にしたお知らせフィードの既読レコードをまとめ取りする（F04.11 インボックス集約）。
     *
     * <p>
     * 統合インボックスの ANNOUNCEMENT アダプタが、集約後の feed.id 集合に対して
     * 1 クエリで既読状態を解決するために使用する（N+1 回避）。読み取り専用。
     * </p>
     *
     * @param userId  ユーザー ID
     * @param feedIds お知らせフィード ID リスト
     * @return 既読レコードのリスト（未読のフィードは含まれない）
     */
    List<AnnouncementReadStatusEntity> findByUserIdAndAnnouncementFeedIdIn(Long userId, List<Long> feedIds);

    /**
     * 指定したお知らせフィード群を「未読なら既読にする」形で登録する（<b>DB 側で冪等</b>・#2530 ⑤）。
     *
     * <p><b>なぜ素の {@code save} / {@code saveAll} ではいけないか</b>: 既読登録は従来
     * 「{@code findByAnnouncementFeedIdAndUserId} で存在確認 → 無ければ {@code INSERT}」だった。
     * 確認と挿入の間に窓があるため、同一ユーザーが {@code read-all}（あるいは単件既読）を
     * <b>同時に 2 回</b>実行すると両方が同じ未読 ID を拾い、後発が
     * {@code uq_ars_feed_user (announcement_feed_id, user_id)} 違反で
     * {@code DataIntegrityViolationException} → <b>500</b> になっていた。
     * 「既に既読なら何もしない」が正しい挙動なので、<b>競合の解消を DB に任せる</b>。</p>
     *
     * <p><b>なぜ例外を捕まえて畳み込まないのか</b>: {@code DataIntegrityViolationException} を
     * catch する方式は (1) 制約違反で Hibernate のセッションが継続不能になり
     * <b>その 1 件だけを無視して残りを続ける</b>ことができない（チャンク全体を失う）、
     * (2) 他の一意制約・NOT NULL 違反まで「既読済み」に誤読する危険がある、
     * という 2 点で不適切である。ここでは
     * {@code ON DUPLICATE KEY UPDATE}（no-op 更新）により<b>重複キーだけ</b>を
     * 無害化する。本テーブルの一意キーは主キー（AUTO_INCREMENT なので衝突しない）と
     * {@code uq_ars_feed_user} の 2 つだけなので、無害化される衝突は意図したものに限られる。
     * {@code INSERT IGNORE} を採らないのは、あちらが型変換・FK 違反等まで警告に格下げして
     * 飲み込んでしまうためである。</p>
     *
     * <p><b>{@code SELECT} 由来にしている理由</b>: JPA は名前付きパラメータのリストを
     * 複数行の {@code VALUES} タプルへ展開できないため、{@code announcement_feeds} を
     * 引き当て元にした {@code INSERT ... SELECT} 形にする。副産物として
     * 「実在する feed にしか既読行を作らない」ことも保証される。
     * {@code IN} 句の要素数は呼び出し元がチャンクサイズ（500）で上限を切っている。</p>
     *
     * <p><b>{@code UTC_TIMESTAMP()} を使う理由</b>: 本アプリは
     * {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} により、Entity の
     * {@code @PrePersist}（JST の {@code LocalDateTime.now()}）を<b>UTC 壁時計</b>として格納する。
     * 素の {@code NOW()} は接続セッションのタイムゾーン依存なので、Hibernate 経由で作られた
     * 既読行と格納基準が食い違う。{@code UTC_TIMESTAMP()} はタイムゾーン設定に依らず
     * UTC 壁時計を返すため、両経路の {@code read_at} が同じ意味になる。</p>
     *
     * <p><b>戻り値を件数として使ってはならない</b>: MySQL Connector/J は既定
     * （{@code useAffectedRows=false} = {@code CLIENT_FOUND_ROWS} あり）で
     * {@code ON DUPLICATE KEY UPDATE} の「重複だった行」も 1 行として数える。
     * したがって戻り値は<b>新規に既読化した件数と一致しない</b>。
     * 利用者に見せる件数は、直前の未読抽出クエリが返した件数を使うこと
     * （{@link AnnouncementReadService#markAllAsRead}）。</p>
     *
     * @param userId  既読にするユーザー ID
     * @param feedIds 対象のお知らせフィード ID（呼び出し元がチャンクサイズで上限を切る）
     * @return DB が報告した影響行数（<b>新規件数ではない</b>。上記注記を参照）
     */
    // flushAutomatically: 同一トランザクション内で JPA 経由に作られた announcement_feeds が
    // まだ DB に無い状態でこのネイティブ INSERT ... SELECT が走ると、引き当て元が空になり
    // 既読行が 1 件も作られない（静かな取りこぼし）。永続化コンテキストの保留分を先に流す。
    // clearAutomatically は付けない — 呼び出し元が保持している他エンティティを
    // トランザクション途中で detach してしまうため（本メソッドは既読 Entity を読まない）。
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO announcement_read_status
                (announcement_feed_id, user_id, read_at, is_proxy_confirmed)
            SELECT f.id, :userId, UTC_TIMESTAMP(), 0
              FROM announcement_feeds f
             WHERE f.id IN (:feedIds)
            ON DUPLICATE KEY UPDATE read_at = announcement_read_status.read_at
            """, nativeQuery = true)
    int insertReadStatusesIgnoringExisting(@Param("userId") Long userId,
                                           @Param("feedIds") Collection<Long> feedIds);

    /**
     * 既読行に代理確認の証跡（{@code is_proxy_confirmed} / {@code proxy_input_record_id}）を付与する。
     *
     * <p>既読行の作成を {@link #insertReadStatusesIgnoringExisting} に寄せた結果、
     * 呼び出し元は採番された主キーを手にしない（IDENTITY 採番の値はネイティブ
     * {@code INSERT} からは返らない）。代理確認は自然キー
     * {@code (announcement_feed_id, user_id)} で引き当てて更新する
     * — このキーには {@code uq_ars_feed_user} があるため一意に定まる。</p>
     *
     * @param feedId              お知らせフィード ID
     * @param userId              ユーザー ID
     * @param proxyInputRecordId  代理入力記録 ID
     * @return 更新行数（通常 1。0 なら既読行が無い＝呼び出し順序の異常）
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE AnnouncementReadStatusEntity r
               SET r.isProxyConfirmed = true,
                   r.proxyInputRecordId = :proxyInputRecordId
             WHERE r.announcementFeedId = :feedId
               AND r.userId = :userId
            """)
    int markProxyConfirmed(@Param("feedId") Long feedId,
                           @Param("userId") Long userId,
                           @Param("proxyInputRecordId") Long proxyInputRecordId);
}
