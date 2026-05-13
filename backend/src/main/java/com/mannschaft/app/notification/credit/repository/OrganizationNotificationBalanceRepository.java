package com.mannschaft.app.notification.credit.repository;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import jakarta.persistence.LockModeType;
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
     * 全組織の残高を取得する（月次リセットバッチ用）。
     *
     * @return 全組織残高リスト
     */
    @Override
    List<OrganizationNotificationBalanceEntity> findAll();
}
