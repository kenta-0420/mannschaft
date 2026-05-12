package com.mannschaft.app.notification.credit.repository;

import com.mannschaft.app.notification.credit.entity.NotificationMonthlyUsageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 通知月次使用量集計リポジトリ。
 */
public interface NotificationMonthlyUsageRepository extends JpaRepository<NotificationMonthlyUsageEntity, Long> {

    /**
     * 組織ID・月・発生源で使用量を取得する。
     *
     * @param organizationId 組織ID
     * @param month          集計月（YYYY-MM-01）
     * @param sourceType     通知発生源
     * @return 月次使用量エンティティ
     */
    Optional<NotificationMonthlyUsageEntity> findByOrganizationIdAndMonthAndSourceType(
            Long organizationId, LocalDate month, NotificationSourceType sourceType);
}
