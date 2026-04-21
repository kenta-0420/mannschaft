package com.mannschaft.app.visibility.repository;

import com.mannschaft.app.visibility.entity.VisibilityTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 公開範囲テンプレートリポジトリ。
 * テンプレートの検索・カウント・アクセス制御クエリを提供する。
 */
public interface VisibilityTemplateRepository extends JpaRepository<VisibilityTemplateEntity, Long> {

    /**
     * 指定ユーザーのカスタムテンプレート一覧を作成日時の降順で取得する。
     * システムプリセットは含まない。
     *
     * @param ownerUserId テンプレート所有ユーザーID
     * @return カスタムテンプレート一覧（新しい順）
     */
    List<VisibilityTemplateEntity> findByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    /**
     * システムプリセット一覧を ID 昇順で取得する。
     *
     * @return システムプリセット一覧
     */
    List<VisibilityTemplateEntity> findByIsSystemPresetTrueOrderByIdAsc();

    /**
     * 指定ユーザーのカスタムテンプレート数を返す（上限チェック用）。
     * システムプリセットはカウントに含まない。
     *
     * @param ownerUserId テンプレート所有ユーザーID
     * @return カスタムテンプレート数
     */
    long countByOwnerUserIdAndIsSystemPresetFalse(Long ownerUserId);

    /**
     * 指定IDのテンプレートを取得する（アクセス制御付き）。
     * 自分のテンプレートまたはシステムプリセットのみ取得可能。
     * IDOR 防止のため、他ユーザーのテンプレートは取得できない。
     *
     * @param id     テンプレートID
     * @param userId リクエストユーザーID
     * @return アクセス可能なテンプレート（存在しない場合は空）
     */
    @Query("SELECT vt FROM VisibilityTemplateEntity vt WHERE vt.id = :id AND (vt.ownerUserId = :userId OR vt.isSystemPreset = true)")
    Optional<VisibilityTemplateEntity> findAccessibleById(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 指定ユーザーが所有するテンプレートを ID で取得する（IDOR 対策）。
     * 他ユーザーのテンプレートは 404 扱いとするため、ownerUserId で絞り込む。
     *
     * @param id      テンプレートID
     * @param userId  所有ユーザーID
     * @return 所有テンプレート（存在しない場合は空）
     */
    @Query("SELECT vt FROM VisibilityTemplateEntity vt WHERE vt.id = :id AND vt.ownerUserId = :userId")
    Optional<VisibilityTemplateEntity> findByIdAndOwnerUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 指定ユーザー内でテンプレート名が重複しているか確認する（重複登録防止用）。
     *
     * @param ownerUserId テンプレート所有ユーザーID
     * @param name        テンプレート名
     * @return 重複している場合は true
     */
    boolean existsByOwnerUserIdAndName(Long ownerUserId, String name);
}
