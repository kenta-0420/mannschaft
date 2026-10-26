package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.PointTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ポイントトランザクションリポジトリ。
 */
public interface PointTransactionRepository extends JpaRepository<PointTransactionEntity, Long> {

    /**
     * 二重付与防止のため、同一参照元のトランザクションを検索する。
     */
    Optional<PointTransactionEntity> findByUserIdAndScopeTypeAndScopeIdAndReferenceTypeAndReferenceId(
            Long userId, String scopeType, Long scopeId, String referenceType, Long referenceId);

    /**
     * スコープ内でポイントトランザクションを持つユーザーIDの一覧を取得する（重複排除済み）。
     * GamificationPointService.adminResetPoints() で全件 findAll() の代替として使用。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ユーザーIDリスト（重複なし）
     */
    @Query("SELECT DISTINCT pt.userId FROM PointTransactionEntity pt WHERE pt.scopeType = :scopeType AND pt.scopeId = :scopeId")
    List<Long> findDistinctUserIdByScopeTypeAndScopeId(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * カーソルなし（先頭から）でユーザーのポイント履歴をID降順で取得する。
     * GamificationPointService.getMyPointHistory() で全件 findAll() の代替として使用。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param pageable  ページリクエスト（サイズ=fetchSize、Sort.by DESC id）
     * @return ページ結果
     */
    Page<PointTransactionEntity> findByUserIdAndScopeTypeAndScopeIdOrderByIdDesc(
            Long userId, String scopeType, Long scopeId, Pageable pageable);

    /**
     * カーソルあり（id &lt; cursorId）でユーザーのポイント履歴をID降順で取得する。
     * GamificationPointService.getMyPointHistory() で全件 findAll() の代替として使用。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param cursorId  カーソルID（このIDより小さいレコードを取得）
     * @param pageable  ページリクエスト（サイズ=fetchSize）
     * @return ページ結果
     */
    Page<PointTransactionEntity> findByUserIdAndScopeTypeAndScopeIdAndIdLessThanOrderByIdDesc(
            Long userId, String scopeType, Long scopeId, Long cursorId, Pageable pageable);
}
