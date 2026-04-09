package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F02.5 行動メモ × タグ 中間テーブルリポジトリ。
 *
 * <p>Phase 1 ではリポジトリのみ先行作成。GDPR エクスポート（Phase 1.5）で利用する。</p>
 */
public interface ActionMemoTagLinkRepository extends JpaRepository<ActionMemoTagLinkEntity, Long> {

    /**
     * メモ ID に紐付く中間レコード一覧を取得する。
     */
    List<ActionMemoTagLinkEntity> findByMemoId(Long memoId);

    /**
     * 指定メモ ID リストに紐付く中間レコード一覧を取得する（一覧 API の N+1 対策）。
     */
    List<ActionMemoTagLinkEntity> findByMemoIdIn(List<Long> memoIds);

    /**
     * GDPR エクスポート用: 指定メモ ID リストに紐付く中間レコードを取得する。
     * ユーザー所有のメモ経由で束ねる。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT l FROM ActionMemoTagLinkEntity l "
                    + "WHERE l.memoId IN (SELECT m.id FROM ActionMemoEntity m WHERE m.userId = :userId)")
    List<ActionMemoTagLinkEntity> findByUserId(
            @org.springframework.data.repository.query.Param("userId") Long userId);
}
