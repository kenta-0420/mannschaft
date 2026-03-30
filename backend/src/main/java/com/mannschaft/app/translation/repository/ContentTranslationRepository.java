package com.mannschaft.app.translation.repository;

import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 翻訳コンテンツリポジトリ。
 */
public interface ContentTranslationRepository extends JpaRepository<ContentTranslationEntity, Long> {

    /**
     * 原文種別・原文ID・言語で翻訳コンテンツを取得する（未削除）。
     *
     * @param sourceType 原文種別
     * @param sourceId   原文ID
     * @param language   言語コード
     * @return 翻訳コンテンツ
     */
    Optional<ContentTranslationEntity> findBySourceTypeAndSourceIdAndLanguageAndDeletedAtIsNull(
            String sourceType, Long sourceId, String language);

    /**
     * 原文種別・原文IDで全言語の翻訳コンテンツを取得する（未削除）。
     * 利用可能言語の一覧取得に使用する。
     *
     * @param sourceType 原文種別
     * @param sourceId   原文ID
     * @return 翻訳コンテンツリスト
     */
    List<ContentTranslationEntity> findBySourceTypeAndSourceIdAndDeletedAtIsNull(
            String sourceType, Long sourceId);

    /**
     * スコープに紐づく翻訳コンテンツを作成日時降順で取得する（未削除）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 翻訳コンテンツリスト
     */
    List<ContentTranslationEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId);

    /**
     * 原文種別・原文ID・ステータスで翻訳コンテンツを取得する（未削除）。
     * NEEDS_UPDATEバッチ処理等に使用する。
     *
     * @param sourceType 原文種別
     * @param sourceId   原文ID
     * @param status     ステータス
     * @return 翻訳コンテンツリスト
     */
    List<ContentTranslationEntity> findBySourceTypeAndSourceIdAndStatusAndDeletedAtIsNull(
            String sourceType, Long sourceId, String status);
}
