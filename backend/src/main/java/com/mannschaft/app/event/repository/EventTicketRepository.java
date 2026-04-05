package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.TicketStatus;
import com.mannschaft.app.event.entity.EventTicketEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * イベントチケットリポジトリ。
 */
public interface EventTicketRepository extends JpaRepository<EventTicketEntity, Long> {

    /**
     * イベントのチケット一覧をページング取得する。
     */
    Page<EventTicketEntity> findByEventIdOrderByCreatedAtDesc(Long eventId, Pageable pageable);

    /**
     * QRトークンでチケットを取得する。
     */
    Optional<EventTicketEntity> findByQrToken(String qrToken);

    /**
     * 参加登録IDでチケット一覧を取得する。
     */
    List<EventTicketEntity> findByRegistrationId(Long registrationId);

    /**
     * イベントのステータス別チケット数を取得する。
     */
    long countByEventIdAndStatus(Long eventId, TicketStatus status);
}
