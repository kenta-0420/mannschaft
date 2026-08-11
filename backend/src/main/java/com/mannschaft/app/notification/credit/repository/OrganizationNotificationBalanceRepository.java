package com.mannschaft.app.notification.credit.repository;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 組織別通知クレジット残高リポジトリ。
 */
public interface OrganizationNotificationBalanceRepository
        extends JpaRepository<OrganizationNotificationBalanceEntity, Long> {

    /**
     * 組織IDで残高を取得する（PESSIMISTIC_WRITE ロック）。
     *
     * <p>{@code consume()} 呼び出し時は必ずこのメソッドでロックを取得してから
     * 残高を更新すること。並行送信時の二重消費を防ぐ。</p>
     *
     * @param organizationId 組織ID
     * @return 残高エンティティ（存在しない場合は {@link Optional#empty()}）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM OrganizationNotificationBalanceEntity b WHERE b.organizationId = :organizationId")
    Optional<OrganizationNotificationBalanceEntity> findByOrganizationIdForUpdate(
            @Param("organizationId") Long organizationId);

    /**
     * 組織IDで残高を取得する（読み取り専用）。
     *
     * @param organizationId 組織ID
     * @return 残高エンティティ
     */
    Optional<OrganizationNotificationBalanceEntity> findByOrganizationId(Long organizationId);

    /**
     * 全組織の残高を id 昇順の<b>キーセットページング</b>で取得する（月次リセットバッチ用）。
     *
     * <p>本クエリに絞り込み条件は無く、処理（{@code monthlyReset}）を行っても対象母集合は
     * 縮まないため、ページ0固定でのドレインは無限ループになる。カーソル（前回ページの
     * 最終 id）を必ず前進させること。</p>
     *
     * @param cursor   前回ページの最終 id（初回は 0）
     * @param pageable ページング情報（サイズのみ使用。ソートは本クエリで固定）
     * @return id 昇順の残高一覧（該当なしは空リスト）
     */
    @Query("SELECT b FROM OrganizationNotificationBalanceEntity b WHERE b.id > :cursor ORDER BY b.id ASC")
    List<OrganizationNotificationBalanceEntity> findAllAfterId(@Param("cursor") Long cursor, Pageable pageable);
}
