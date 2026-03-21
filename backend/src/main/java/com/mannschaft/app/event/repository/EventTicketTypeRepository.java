package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * イベントチケット種別リポジトリ。
 */
public interface EventTicketTypeRepository extends JpaRepository<EventTicketTypeEntity, Long> {

    /**
     * イベントのチケット種別一覧を取得する。
     */
    List<EventTicketTypeEntity> findByEventIdOrderBySortOrder(Long eventId);

    /**
     * イベントの有効なチケット種別一覧を取得する。
     */
    List<EventTicketTypeEntity> findByEventIdAndIsActiveTrueOrderBySortOrder(Long eventId);

    /**
     * イベントのチケット種別数を取得する。
     */
    long countByEventId(Long eventId);
}
