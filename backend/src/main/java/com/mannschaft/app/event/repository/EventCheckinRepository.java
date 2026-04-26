package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventCheckinEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

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

    /**
     * イベントIDとユーザーIDでチェックインが存在するか確認する。
     *
     * <p>event_checkins → event_tickets → event_registrations の結合で userId を照合する。
     * F03.12 Phase4 バッチが未チェックイン判定に使用する。</p>
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @return チェックイン済みの場合 true
     */
    @Query("""
            SELECT COUNT(c) > 0
            FROM EventCheckinEntity c
            JOIN EventTicketEntity t ON t.id = c.ticketId
            JOIN EventRegistrationEntity r ON r.id = t.registrationId
            WHERE c.eventId = :eventId
              AND r.userId = :userId
            """)
    boolean existsByEventIdAndUserId(@Param("eventId") Long eventId, @Param("userId") Long userId);

    /**
     * 指定イベントにチェックイン済みのユーザーIDリストを取得する。
     *
     * <p>event_checkins → event_tickets → event_registrations の結合で userId を取得する。
     * F03.12 §16 解散通知サービスが RSVP 未登録だがチェックイン済みの参加者を補完するために使用する。</p>
     *
     * @param eventId イベントID
     * @return チェックイン済みユーザーIDリスト
     */
    @Query("""
            SELECT DISTINCT r.userId
            FROM EventCheckinEntity c
            JOIN EventTicketEntity t ON t.id = c.ticketId
            JOIN EventRegistrationEntity r ON r.id = t.registrationId
            WHERE c.eventId = :eventId
            """)
    List<Long> findCheckedInUserIdsByEventId(@Param("eventId") Long eventId);
}
