package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventCheckinEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * イベントチェックインリポジトリ。
 */
public interface EventCheckinRepository extends JpaRepository<EventCheckinEntity, Long> {

    /**
     * イベントのチェックイン一覧をページング取得する。
     */
    Page<EventCheckinEntity> findByEventIdOrderByCheckedInAtDesc(Long eventId, Pageable pageable);

    /**
     * チケットIDでチェックインが存在するか確認する。
     */
    boolean existsByTicketId(Long ticketId);

    /**
     * イベントのチェックイン数を取得する。
     */
    long countByEventId(Long eventId);
}
