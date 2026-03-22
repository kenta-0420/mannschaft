package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.NotificationChannel;
import com.mannschaft.app.admin.entity.NotificationDeliveryStatsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 通知配信統計リポジトリ。
 */
public interface NotificationDeliveryStatsRepository extends JpaRepository<NotificationDeliveryStatsEntity, Long> {

    /**
     * 日付範囲で統計を取得する。
     */
    List<NotificationDeliveryStatsEntity> findByDateBetweenOrderByDateDescChannelAsc(
            LocalDate from, LocalDate to);

    /**
     * 日付・チャネルで統計を取得する。
     */
    Optional<NotificationDeliveryStatsEntity> findByDateAndChannel(LocalDate date, NotificationChannel channel);

    /**
     * チャネル別に統計を取得する。
     */
    List<NotificationDeliveryStatsEntity> findByChannelAndDateBetweenOrderByDateDesc(
            NotificationChannel channel, LocalDate from, LocalDate to);
}
