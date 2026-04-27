package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.entity.EventCheckinEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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
    /**
     * イベントIDとユーザーIDでチェックインが存在するか確認する。
     *
     * <p>QR/セルフチェックイン（ticket→registration 経由）と
     * 点呼（rollCallUserId 直接）の両方を考慮する。</p>
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @return チェックイン済みの場合 true
     */
    @Query("""
            SELECT COUNT(c) > 0
            FROM EventCheckinEntity c
            LEFT JOIN EventTicketEntity t ON t.id = c.ticketId
            LEFT JOIN EventRegistrationEntity r ON r.id = t.registrationId
            WHERE c.eventId = :eventId
              AND (r.userId = :userId OR c.rollCallUserId = :userId)
            """)
    boolean existsByEventIdAndUserId(@Param("eventId") Long eventId, @Param("userId") Long userId);

    /**
     * 点呼セッションID・ユーザーIDで既存チェックインレコードを取得する（冪等処理用）。
     *
     * <p>F03.12 §14 主催者点呼機能。同一 rollCallSessionId + userId の再送時に UPDATE 対象を特定する。</p>
     *
     * @param eventId          イベントID
     * @param rollCallSessionId 点呼セッションID
     * @param userId           ユーザーID
     * @return 既存チェックインレコード（存在しない場合は空）
     */
    /**
     * 点呼セッションID・ユーザーIDで既存チェックインレコードを取得する（冪等処理用）。
     *
     * <p>F03.12 §14 主催者点呼機能。同一 rollCallSessionId + userId の再送時に UPDATE 対象を特定する。
     * 点呼レコードは rollCallUserId で userId を保持するため、直接参照する。</p>
     *
     * @param eventId           イベントID
     * @param rollCallSessionId 点呼セッションID
     * @param userId            ユーザーID
     * @return 既存チェックインレコード（存在しない場合は空）
     */
    @Query("""
            SELECT c
            FROM EventCheckinEntity c
            WHERE c.eventId = :eventId
              AND c.rollCallSessionId = :rollCallSessionId
              AND c.rollCallUserId = :userId
            """)
    Optional<EventCheckinEntity> findByEventIdAndRollCallSessionIdAndUserId(
            @Param("eventId") Long eventId,
            @Param("rollCallSessionId") String rollCallSessionId,
            @Param("userId") Long userId);

    /**
     * 点呼セッションID でチェックインレコード一覧を取得する（セッション履歴用）。
     *
     * <p>F03.12 §14 主催者点呼機能。GET /roll-call/sessions の詳細表示に使用する。</p>
     *
     * @param rollCallSessionId 点呼セッションID
     * @return 該当セッションのチェックインレコード一覧
     */
    List<EventCheckinEntity> findByRollCallSessionId(String rollCallSessionId);

    /**
     * イベントIDとユーザーIDで最新の点呼チェックインレコードを取得する（個別修正用）。
     *
     * <p>F03.12 §14 PATCH /roll-call/{userId} の修正対象特定に使用する。</p>
     *
     * @param eventId イベントID
     * @param userId  点呼対象ユーザーID
     * @return 最新の点呼チェックインレコード（存在しない場合は空）
     */
    @Query("""
            SELECT c FROM EventCheckinEntity c
            WHERE c.eventId = :eventId
              AND c.rollCallUserId = :userId
              AND c.rollCallSessionId IS NOT NULL
            ORDER BY c.checkedInAt DESC
            """)
    List<EventCheckinEntity> findRollCallByEventIdAndUserId(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId);

    /**
     * イベントIDで点呼セッションIDの一覧を重複排除して取得する（過去セッション一覧用）。
     *
     * <p>F03.12 §14 GET /roll-call/sessions で使用する。</p>
     *
     * @param eventId イベントID
     * @return 点呼セッションIDの一覧
     */
    @Query("""
            SELECT DISTINCT c.rollCallSessionId
            FROM EventCheckinEntity c
            WHERE c.eventId = :eventId
              AND c.rollCallSessionId IS NOT NULL
            """)
    List<String> findDistinctRollCallSessionIdsByEventId(@Param("eventId") Long eventId);
}
