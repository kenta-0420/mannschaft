package com.mannschaft.app.social.announcement;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * スコープ内の有効なお知らせフィードを取得する（元コンテンツ削除済みを除外）。
     *
     * <p>
     * 管理画面やバッチ処理で全件リスト表示する際に使用する。
     * ウィジェットのカーソルページング取得には {@link AnnouncementFeedQueryRepository#findByScope} を使うこと。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param sort      ソート順（ピン留め優先 → 新着順が推奨）
     * @return 有効なお知らせフィードリスト
     */
    List<AnnouncementFeedEntity> findByScopeTypeAndScopeIdAndSourceDeletedAtIsNull(
            AnnouncementScopeType scopeType,
            Long scopeId,
            Sort sort);

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
