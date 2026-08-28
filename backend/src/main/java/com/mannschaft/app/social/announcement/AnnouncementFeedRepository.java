package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * お知らせフィードリポジトリ（F02.6）。
 *
 * <p>
 * {@code announcement_feeds} テーブルへのアクセス経路。
 * カーソルページングが必要なクエリは {@link AnnouncementFeedQueryRepository} を使用すること。
 * </p>
 */
public interface AnnouncementFeedRepository extends JpaRepository<AnnouncementFeedEntity, Long> {

    boolean existsByIdAndScopeTypeAndScopeId(
            Long id, AnnouncementScopeType scopeType, Long scopeId);

    /**
     * ソース種別・ソース ID・スコープの組み合わせで一意のお知らせフィードを取得する。
     *
     * <p>
     * 同一コンテンツの重複登録チェック（409 Conflict 判定）および既存レコードの取得に使用する。
     * </p>
     *
     * @param sourceType 元コンテンツ種別
     * @param sourceId   元コンテンツ ID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープ ID
     * @return お知らせフィードエンティティ（存在しなければ空）
     */
    Optional<AnnouncementFeedEntity> findBySourceTypeAndSourceIdAndScopeTypeAndScopeId(
            AnnouncementSourceType sourceType,
            Long sourceId,
            AnnouncementScopeType scopeType,
            Long scopeId);

    // NOTE (#2494): findByScopeTypeAndScopeIdAndSourceDeletedAtIsNull（スコープ内 feed の
    // limit 無し全件取得）は削除した。唯一の利用者だった一括既読が、可視性・未読を DB 側で絞る
    // AnnouncementFeedQueryRepository#findUnreadIdsByScope（件数上限つき）へ移行したためである。
    // 「スコープの feed を無制限に全件メモリへ載せる」経路は再導入しないこと
    //（IN 句のプレースホルダ上限・max_allowed_packet に触れ、実行時間がスコープの歴史に比例する）。

    /**
     * スコープ内のピン留め済みお知らせ件数を取得する（ピン留め上限チェック用）。
     *
     * <p>
     * Service 層で「新たにピン留めする前に上限（5件）チェック」のために使用する。
     * 元コンテンツ削除済みのレコードはカウントしない。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @return ピン留め済みお知らせ件数
     */
    long countByScopeTypeAndScopeIdAndIsPinnedTrueAndSourceDeletedAtIsNull(
            AnnouncementScopeType scopeType,
            Long scopeId);

    /**
     * F00 可視性判定用の射影を ID 集合で一括取得する（F08.9 P4b ペイウォール連結）。
     *
     * <p>取得した Entity から
     * {@link com.mannschaft.app.social.announcement.visibility.AnnouncementFeedVisibilityResolver}
     * がメモリ上で {@link AnnouncementFeedVisibilityProjection} に変換する。
     * {@code source_deleted_at IS NOT NULL} のレコードも含む
     * （可視性判定後に削除済み表示除外は一覧クエリ側の別軸で制御）。</p>
     *
     * @param ids 取得対象 ID 集合（空でない、{@code null} ではない）
     * @return 実存する {@link AnnouncementFeedEntity} の List
     */
    List<AnnouncementFeedEntity> findByIdIn(Collection<Long> ids);

    /**
     * 元コンテンツの種別と ID に紐づくお知らせフィードを全件取得する。
     *
     * <p>
     * 元コンテンツ削除連動（ApplicationEvent → {@code source_deleted_at} セット）や
     * 元コンテンツ更新時のキャッシュ同期、モデレーション（通報による削除連動）で使用する。
     * 複数スコープ（チームA・チームB 両方でお知らせ化されているケース）にも対応する。
     * </p>
     *
     * @param sourceType 元コンテンツ種別
     * @param sourceId   元コンテンツ ID
     * @return 該当するお知らせフィードリスト
     */
    @Query("SELECT a FROM AnnouncementFeedEntity a WHERE a.sourceType = :sourceType AND a.sourceId = :sourceId")
    List<AnnouncementFeedEntity> findAllBySource(
            @Param("sourceType") AnnouncementSourceType sourceType,
            @Param("sourceId") Long sourceId);
}
