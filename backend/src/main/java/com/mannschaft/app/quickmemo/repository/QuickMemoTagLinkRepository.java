package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.QuickMemoTagLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * ポイっとメモ-タグ中間テーブルリポジトリ。
 */
public interface QuickMemoTagLinkRepository extends JpaRepository<QuickMemoTagLinkEntity, Long> {

    /**
     * メモに紐付くタグIDリストを取得する。
     */
    @Query("SELECT l.tagId FROM QuickMemoTagLinkEntity l WHERE l.memoId = :memoId")
    List<Long> findTagIdsByMemoId(@Param("memoId") Long memoId);

    /**
     * メモとタグの紐付けが存在するか確認する。
     */
    boolean existsByMemoIdAndTagId(Long memoId, Long tagId);

    /**
     * メモとタグの紐付けを削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM QuickMemoTagLinkEntity l WHERE l.memoId = :memoId AND l.tagId = :tagId")
    void deleteByMemoIdAndTagId(@Param("memoId") Long memoId, @Param("tagId") Long tagId);

    /**
     * メモに紐付くタグリンクをすべて削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM QuickMemoTagLinkEntity l WHERE l.memoId = :memoId")
    void deleteByMemoId(@Param("memoId") Long memoId);

    /**
     * 複数メモのタグリンク一覧を取得する（物理削除バッチ用・usage_count 集計）。
     */
    List<QuickMemoTagLinkEntity> findByMemoIdIn(@Param("memoIds") List<Long> memoIds);

    /**
     * 指定メモ群のうち、あるタグに紐付くリンクを削除し、<b>実際に削除できた行数</b>を返す
     * （物理削除バッチ用・usage_count 減算の根拠）。
     *
     * <p>usage_count の減算は read-modify-write であり、同じリンクを 2 つの実行が数えると
     * 同じ分だけ 2 回引かれてタグの使用数が実態より少なくなる（0 で下限クリップされるため復元もできない）。
     * 「削除できた行数」を減算量に使えば、リンク行の削除が行ロックで直列化される以上、
     * 全実行の減算量の合計は必ず実際のリンク数と一致する。</p>
     *
     * @param memoIds 対象メモID群
     * @param tagId 対象タグID
     * @return 実際に削除できたリンク行数
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM QuickMemoTagLinkEntity l WHERE l.memoId IN :memoIds AND l.tagId = :tagId")
    int deleteByMemoIdInAndTagId(@Param("memoIds") List<Long> memoIds, @Param("tagId") Long tagId);

    /**
     * メモに紐付くタグリンク件数を取得する（10個上限チェック用）。
     */
    long countByMemoId(Long memoId);

    /**
     * タグに紐付くメモリンク件数を取得する（整合性バッチ用）。
     */
    long countByTagId(Long tagId);
}
