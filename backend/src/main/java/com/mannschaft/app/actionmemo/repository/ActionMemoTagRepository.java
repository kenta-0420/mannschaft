package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
