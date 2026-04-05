package com.mannschaft.app.translation.repository;

import com.mannschaft.app.translation.entity.TranslationAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 翻訳担当者アサインリポジトリ。
 */
public interface TranslationAssignmentRepository extends JpaRepository<TranslationAssignmentEntity, Long> {

    /**
     * スコープに紐づく有効なアサイン一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return アサインリスト
     */
    List<TranslationAssignmentEntity> findByScopeTypeAndScopeIdAndIsActiveTrue(
            String scopeType, Long scopeId);

    /**
     * スコープ・ユーザーに紐づく有効なアサイン一覧を取得する。
     * 指定ユーザーがどの言語にアサインされているかの確認に使用する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    ユーザーID
     * @return アサインリスト
     */
    List<TranslationAssignmentEntity> findByScopeTypeAndScopeIdAndUserIdAndIsActiveTrue(
            String scopeType, Long scopeId, Long userId);

    /**
     * 指定のスコープ・ユーザー・言語に対して有効なアサインが存在するか確認する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    ユーザーID
     * @param language  言語コード
     * @return 存在する場合 true
     */
    boolean existsByScopeTypeAndScopeIdAndUserIdAndLanguageAndIsActiveTrue(
            String scopeType, Long scopeId, Long userId, String language);
}
