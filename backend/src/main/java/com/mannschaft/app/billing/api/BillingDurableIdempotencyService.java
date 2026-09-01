package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PR4 durable idempotency（BC-23）。
 *
 * <p>actor/method/path/key と request hash に束縛し、lease 所有者付きの条件付き CAS で
 * 二重実行を防ぐ。既存応答本文は 409 の例外メッセージへ載せない。</p>
 */
@Service
@RequiredArgsConstructor
class BillingDurableIdempotencyService {

    /** PROCESSING lease の保持期間。 */
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    /** 冪等レコードの保持期間。 */
    private static final Duration RECORD_TTL = Duration.ofHours(24);

    /** TTL/lease は Instant 比較のみ。Checkout フローと同じ時間軸を共有するため壁時計を用いる。 */
    @Qualifier("wallClock")
    private final Clock clock;
    private final BillingApiIdempotencyRepository repository;

    BillingIdempotencyDecision begin(long actorId, String httpMethod, String requestPath,
                                     String idempotencyKey, String requestHash, String leaseOwner) {
        Instant now = clock.instant();
        Optional<BillingIdempotencyRecord> existing =
                repository.find(actorId, httpMethod, requestPath, idempotencyKey);
        if (existing.isEmpty()) {
            BillingIdempotencyRecord reserved = repository.reserve(new BillingIdempotencyRecord(
                    null, actorId, httpMethod, requestPath, idempotencyKey, requestHash,
                    BillingIdempotencyStatus.PROCESSING, null, null, leaseOwner,
                    now.plus(LEASE_DURATION), now, null, now.plus(RECORD_TTL)));
            return new BillingIdempotencyDecision(BillingIdempotencyDecisionKind.ACQUIRED,
                    reserved == null ? null : reserved.id(), null, null, 0L);
        }

        BillingIdempotencyRecord record = existing.get();
        if (!requestHash.equals(record.requestHash())) {
            // 既存 response 本文は漏らさず、コード由来の定型メッセージだけを返す。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
        if (record.status() == BillingIdempotencyStatus.PROCESSING) {
            long retryAfterSeconds = record.leaseExpiresAt() == null
                    ? 0L
                    : Math.max(0L, Duration.between(now, record.leaseExpiresAt()).toSeconds());
            return new BillingIdempotencyDecision(BillingIdempotencyDecisionKind.PROCESSING,
                    record.id(), null, null, retryAfterSeconds);
        }
        return new BillingIdempotencyDecision(BillingIdempotencyDecisionKind.REPLAY,
                record.id(), record.responseStatus(), record.responseJson(), 0L);
    }

    void complete(UUID id, String leaseOwner, int responseStatus, String responseJson) {
        Instant now = clock.instant();
        if (repository.completeIfLeaseOwner(id, leaseOwner, responseStatus, responseJson, now) != 1) {
            // CAS 失敗＝lease を他者に奪われている。成功扱いにせず競合として返す。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
    }

    void recoverStale(UUID id, String previousLeaseOwner, Instant observedExpiry,
                      String newLeaseOwner) {
        Instant now = clock.instant();
        // 観測した owner と expiry を CAS 条件に含め、他 worker との二重回収を防ぐ。
        repository.recoverStaleLease(id, previousLeaseOwner, observedExpiry, newLeaseOwner,
                now.plus(LEASE_DURATION), now);
    }
}

enum BillingIdempotencyStatus { PROCESSING, SUCCEEDED, FAILED }

enum BillingIdempotencyDecisionKind { ACQUIRED, REPLAY, PROCESSING }

record BillingIdempotencyRecord(
        UUID id, long actorId, String httpMethod, String requestPath,
        String idempotencyKey, String requestHash, BillingIdempotencyStatus status,
        Integer responseStatus, String responseJson, String leaseOwner,
        Instant leaseExpiresAt, Instant startedAt, Instant completedAt, Instant expiresAt) { }

record BillingIdempotencyDecision(
        BillingIdempotencyDecisionKind kind, UUID id, Integer responseStatus,
        String responseJson, long retryAfterSeconds) { }
