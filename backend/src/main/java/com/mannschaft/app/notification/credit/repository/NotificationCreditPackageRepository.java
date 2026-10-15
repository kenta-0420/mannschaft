package com.mannschaft.app.notification.credit.repository;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPackageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 通知クレジットパッケージリポジトリ。
 */
public interface NotificationCreditPackageRepository extends JpaRepository<NotificationCreditPackageEntity, Long> {

    /**
     * 販売中のパッケージを表示順で取得する。
     */
    List<NotificationCreditPackageEntity> findAllByIsActiveTrueOrderByDisplayOrder();
}
