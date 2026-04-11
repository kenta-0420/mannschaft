package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F02.5 行動メモ タグリポジトリ。
 *
 * <p>Phase 1 では Entity / Repository のみ先行作成し、Service / Controller は Phase 4 で実装する。
 * GDPR エクスポート（Phase 1.5）では {@code findByUserId} をそのまま利用する。</p>
 */
public interface ActionMemoTagRepository extends JpaRepository<ActionMemoTagEntity, Long> {

    /**
     * 自分のタグを ID 指定で取得する（論理削除済みは @SQLRestriction で除外）。
     */
    Optional<ActionMemoTagEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーのタグ一覧を sortOrder 昇順で取得する（論理削除済みは @SQLRestriction で除外）。
     */
    List<ActionMemoTagEntity> findByUserIdOrderBySortOrderAsc(Long userId);

    /**
     * 指定 id リストに含まれるタグのうち、自分のもののみを取得する
     * （メモにタグを紐付ける際の所有権チェック用。Phase 4）。
     */
    List<ActionMemoTagEntity> findByIdInAndUserId(List<Long> ids, Long userId);

    /**
     * ユーザーの有効タグ件数を取得する（論理削除済みは @SQLRestriction で除外）。
     * タグ作成時の100件上限チェック用（Phase 4）。
     */
    long countByUserId(Long userId);

    /**
     * 指定 ID リストのタグを論理削除済みも含めて取得する（@SQLRestriction を回避）。
     *
     * <p>メモ取得時（GET /action-memos）で各メモに付いている論理削除済みタグも含めて返す必要がある。
     * 設計書 §3「削除済みタグの API レスポンス表現」に準拠。
     * Phase 1 の申し送り「Phase 4 でタグ管理 API を実装する際、削除済みタグ取得用に
     * @SQLRestriction を外す Repository メソッドを追加する必要がある」への対応。</p>
     */
    @Query(value = "SELECT * FROM action_memo_tags WHERE id IN (:ids)",
            nativeQuery = true)
    List<ActionMemoTagEntity> findByIdInIncludingDeleted(@Param("ids") List<Long> ids);
}
