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
     * メモに紐付くタグリンク件数を取得する（10個上限チェック用）。
     */
    long countByMemoId(Long memoId);
}
