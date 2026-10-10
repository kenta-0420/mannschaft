package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamNotificationOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code team_notification_outbox} の Repository（docs/architecture/notification_outbox.md §3・§4）。
 *
 * <p>冪等 INSERT（{@code ON DUPLICATE KEY UPDATE id = id}）、claim（{@code FOR UPDATE SKIP LOCKED}）、
 * {@code claim_token} 一致の条件付き印付け、回収、掃除、最古の PENDING の問い合わせを native SQL で持つ。
 * organization_id で絞る問い合わせは持たない（原則7 の対象外。設計書 §3.4）。</p>
 *
 * <h2>時刻の渡し方</h2>
 * <p>時刻は呼び出し側の {@code Instant} を<b>エポックからのマイクロ秒</b>で受け、SQL の中で
 * {@code TIMESTAMPADD(MICROSECOND, :micros, '1970-01-01 00:00:00')} により UTC の DATETIME(6) にする
 * （加盟行の {@code TeamOrgMembershipRepository} がエポック秒で渡すのと同じ作法）。JVM 既定ゾーン・接続の
 * セッションゾーンのどちらにも依らず、Entity（{@code Instant}・{@code hibernate.jdbc.time_zone=UTC}）が書く値と同じ基準になる。
 * 時刻の読み出しも {@code TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', 列)} で同じ基準に戻す。</p>
 */
public interface TeamNotificationOutboxRepository extends JpaRepository<TeamNotificationOutboxEntity, UUID> {

    /**
     * outbox に1行を冪等に書く（PENDING・{@code attempt_count=0}・{@code next_attempt_at=:nowMicros}）。
     * 同じ冪等キーの2回目は何も変えず、例外も投げない（呼び出し側の tx を rollback-only にしない。OB05）。
     *
     * @return 影響行数（新規は1、重複は0）
     */
    @Modifying
    @Query(value = """
            INSERT INTO team_notification_outbox
                (id, idempotency_key, message_kind, payload_version, payload_json, notification_type, organization_id,
                 status, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES
                (:id, :idempotencyKey, :messageKind, :payloadVersion, :payloadJson, :notificationType, :organizationId,
                 'PENDING', 0,
                 TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'))
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIdempotent(@Param("id") UUID id,
                         @Param("idempotencyKey") UUID idempotencyKey,
                         @Param("messageKind") String messageKind,
                         @Param("payloadVersion") int payloadVersion,
                         @Param("payloadJson") String payloadJson,
                         @Param("notificationType") String notificationType,
                         @Param("organizationId") Long organizationId,
                         @Param("nowMicros") long nowMicros);

    /**
     * 取り込み待ち（PENDING かつ {@code next_attempt_at} を過ぎたもの）を id 昇順に最大 {@code limit} 行、
     * {@code FOR UPDATE SKIP LOCKED} で取る（並行する relay とは行が分かれる。OB09）。
     */
    @Query(value = """
            SELECT * FROM team_notification_outbox
             WHERE status = 'PENDING'
               AND next_attempt_at <= TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<TeamNotificationOutboxEntity> lockClaimable(@Param("nowMicros") long nowMicros, @Param("limit") int limit);

    /** ロック済みの PENDING を RELAYING・新しい {@code claim_token} にする（{@link #lockClaimable} と同じ tx で呼ぶ）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'RELAYING',
                   claim_token = :claimToken,
                   claimed_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE id IN (:ids)
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markClaimed(@Param("ids") Collection<UUID> ids,
                    @Param("claimToken") UUID claimToken,
                    @Param("nowMicros") long nowMicros);

    /** RELAYED に印を付ける。{@code id}・RELAYING・{@code claim_token} が一致したときだけ当たる。 */
    @Modifying
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'RELAYED',
                   relayed_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE id = :id
               AND status = 'RELAYING'
               AND claim_token = :claimToken
            """, nativeQuery = true)
    int markRelayed(@Param("id") UUID id, @Param("claimToken") UUID claimToken, @Param("nowMicros") long nowMicros);

    /** 取り込みの失敗を記録して PENDING に戻す（再試行待ち）。世代が一致したときだけ当たる。 */
    @Modifying
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'PENDING',
                   attempt_count = attempt_count + 1,
                   last_error = :lastError,
                   claim_token = NULL,
                   claimed_at = NULL,
                   next_attempt_at = TIMESTAMPADD(MICROSECOND, :nextAttemptMicros, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE id = :id
               AND status = 'RELAYING'
               AND claim_token = :claimToken
            """, nativeQuery = true)
    int markFailedForRetry(@Param("id") UUID id,
                           @Param("claimToken") UUID claimToken,
                           @Param("lastError") String lastError,
                           @Param("nextAttemptMicros") long nextAttemptMicros,
                           @Param("nowMicros") long nowMicros);

    /** 取り込みの失敗を記録して DEAD にする（以後 claim されない）。世代が一致したときだけ当たる。 */
    @Modifying
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'DEAD',
                   attempt_count = attempt_count + 1,
                   last_error = :lastError,
                   claim_token = NULL,
                   dead_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE id = :id
               AND status = 'RELAYING'
               AND claim_token = :claimToken
            """, nativeQuery = true)
    int markDead(@Param("id") UUID id,
                 @Param("claimToken") UUID claimToken,
                 @Param("lastError") String lastError,
                 @Param("nowMicros") long nowMicros);

    /** 読めない版の行を、失敗回数を増やさず PENDING に戻して先送りする。世代が一致したときだけ当たる。 */
    @Modifying
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'PENDING',
                   claim_token = NULL,
                   claimed_at = NULL,
                   next_attempt_at = TIMESTAMPADD(MICROSECOND, :nextAttemptMicros, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE id = :id
               AND status = 'RELAYING'
               AND claim_token = :claimToken
            """, nativeQuery = true)
    int deferUnsupportedVersion(@Param("id") UUID id,
                                @Param("claimToken") UUID claimToken,
                                @Param("nextAttemptMicros") long nextAttemptMicros,
                                @Param("nowMicros") long nowMicros);

    /** claim から止まった RELAYING を PENDING・{@code claim_token=NULL} に戻す。 */
    @Modifying
    @Query(value = """
            UPDATE team_notification_outbox
               SET status = 'PENDING',
                   claim_token = NULL,
                   claimed_at = NULL,
                   updated_at = TIMESTAMPADD(MICROSECOND, :nowMicros, '1970-01-01 00:00:00')
             WHERE status = 'RELAYING'
               AND claimed_at < TIMESTAMPADD(MICROSECOND, :claimedBeforeMicros, '1970-01-01 00:00:00')
            """, nativeQuery = true)
    int recoverStuck(@Param("claimedBeforeMicros") long claimedBeforeMicros, @Param("nowMicros") long nowMicros);

    /** {@code relayed_at} が境界より前の RELAYED を削除する（起算点は created_at ではない）。 */
    @Modifying
    @Query(value = """
            DELETE FROM team_notification_outbox
             WHERE status = 'RELAYED'
               AND relayed_at < TIMESTAMPADD(MICROSECOND, :relayedBeforeMicros, '1970-01-01 00:00:00')
            """, nativeQuery = true)
    int deleteRelayedBefore(@Param("relayedBeforeMicros") long relayedBeforeMicros);

    /** {@code dead_at} が境界より前の DEAD を削除する（起算点は created_at ではない）。 */
    @Modifying
    @Query(value = """
            DELETE FROM team_notification_outbox
             WHERE status = 'DEAD'
               AND dead_at < TIMESTAMPADD(MICROSECOND, :deadBeforeMicros, '1970-01-01 00:00:00')
            """, nativeQuery = true)
    int deleteDeadBefore(@Param("deadBeforeMicros") long deadBeforeMicros);

    /** 最古の PENDING の {@code created_at}（エポックからのマイクロ秒。無ければ null）。 */
    @Query(value = """
            SELECT TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', MIN(created_at))
              FROM team_notification_outbox
             WHERE status = 'PENDING'
            """, nativeQuery = true)
    Long findOldestPendingCreatedAtMicros();
}
