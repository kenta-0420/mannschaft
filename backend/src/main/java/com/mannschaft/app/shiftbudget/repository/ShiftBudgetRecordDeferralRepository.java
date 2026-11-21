package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetRecordDeferralEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 遅延記録リポジトリ（CMP-260909-2152 確定設計 r12 §4.4）。確保・延長・確定は DB の NOW() と所有者（claimed_by）で判定し、
 * 0行なら所有喪失として扱う。
 */
@Repository
public interface ShiftBudgetRecordDeferralRepository extends JpaRepository<ShiftBudgetRecordDeferralEntity, UUID> {

    /** 確保。1行なら所有者になれた。リース有効な他者の確保・PENDING でないものは0行。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE shift_budget_record_deferrals SET claimed_by = :me, lease_until = NOW(6) + INTERVAL 30 MINUTE, "
            + "attempt_count = attempt_count + 1 WHERE id = :id AND state = 'PENDING' "
            + "AND (claimed_by IS NULL OR lease_until < NOW(6))", nativeQuery = true)
    int claim(@Param("id") byte[] id, @Param("me") String me);

    /** 延長。所有者かつリース有効のときだけ1行。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE shift_budget_record_deferrals SET lease_until = NOW(6) + INTERVAL 30 MINUTE "
            + "WHERE id = :id AND claimed_by = :me AND lease_until >= NOW(6) AND state = 'PENDING'", nativeQuery = true)
    int extend(@Param("id") byte[] id, @Param("me") String me);

    /** 確定（REPLAYED／REDEFERRED）。所有者のみ。0行なら結果を破棄する。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE shift_budget_record_deferrals SET state = :state, claimed_by = NULL, lease_until = NULL, "
            + "resolved_at = NOW(6) WHERE id = :id AND claimed_by = :me AND state = 'PENDING'", nativeQuery = true)
    int resolve(@Param("id") byte[] id, @Param("me") String me, @Param("state") String state);

    /** 失敗の記録と所有解除（PENDING のまま last_error を残す）。attempt_count は確保時に加算済み。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE shift_budget_record_deferrals SET claimed_by = NULL, lease_until = NULL, last_error = :error "
            + "WHERE id = :id AND claimed_by = :me AND state = 'PENDING'", nativeQuery = true)
    int releaseWithError(@Param("id") byte[] id, @Param("me") String me, @Param("error") String error);

    /** シフト×締めイベントの PENDING が無ければ作る（pending_key の UNIQUE により並行しても1行）。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT IGNORE INTO shift_budget_record_deferrals (id, shift_id, close_event_id, state, attempt_count, created_at) "
            + "VALUES (:id, :shiftId, :eventId, 'PENDING', 0, NOW(6))", nativeQuery = true)
    int insertPendingIgnore(@Param("id") byte[] id, @Param("shiftId") Long shiftId, @Param("eventId") byte[] eventId);

    /** 回収対象: イベントが PREPARED でなくなった PENDING、またはリース切れの PENDING（古い順）。 */
    @Query(value = "SELECT d.id FROM shift_budget_record_deferrals d "
            + "JOIN shift_budget_monthly_close_events e ON e.id = d.close_event_id "
            + "WHERE d.state = 'PENDING' AND e.state <> 'PREPARED' AND (d.claimed_by IS NULL OR d.lease_until < NOW(6)) "
            + "ORDER BY d.created_at, d.id LIMIT :limit", nativeQuery = true)
    List<byte[]> findRecoverableIds(@Param("limit") int limit);

    /** 指定イベントの再実行待ち（Commit／Abort 直後に段取り役が古い順に回す）。 */
    @Query(value = "SELECT d.id FROM shift_budget_record_deferrals d WHERE d.close_event_id = :eventId AND d.state = 'PENDING' "
            + "AND (d.claimed_by IS NULL OR d.lease_until < NOW(6)) ORDER BY d.created_at, d.id", nativeQuery = true)
    List<byte[]> findPendingIdsByEvent(@Param("eventId") byte[] eventId);

    /** シフト×イベントの PENDING の有無（遅延の二重作成の事前確認）。 */
    boolean existsByShiftIdAndCloseEventIdAndState(Long shiftId, UUID closeEventId,
                                                  com.mannschaft.app.shiftbudget.ShiftBudgetDeferralState state);
}
