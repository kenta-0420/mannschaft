package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
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
@Slf4j
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
            try {
                BillingIdempotencyRecord reserved = repository.reserve(new BillingIdempotencyRecord(
                        null, actorId, httpMethod, requestPath, idempotencyKey, requestHash,
                        BillingIdempotencyStatus.PROCESSING, null, null, leaseOwner,
                        now.plus(LEASE_DURATION), now, null, now.plus(RECORD_TTL)));
                return new BillingIdempotencyDecision(BillingIdempotencyDecisionKind.ACQUIRED,
                        reserved == null ? null : reserved.id(), null, null, 0L);
            } catch (DataIntegrityViolationException e) {
                // 握り潰しではない。同一キーの同時到達で両者が find で空を観測し、
                // 一方の INSERT が uk_bai_actor_request と衝突した状態である。
                // これは「先に予約した側が居る」という冪等性そのものの事実なので、
                // 既存レコードを読み直して PROCESSING / REPLAY / hash 不一致 409 の
                // 正規の冪等応答へ写す。読み直しても不在なら別の制約違反であり、
                // 事実を隠さず元の例外をそのまま送出する。
                BillingIdempotencyRecord raced =
                        repository.find(actorId, httpMethod, requestPath, idempotencyKey)
                                .orElseThrow(() -> e);
                return evaluateExisting(raced, requestHash, leaseOwner, now);
            }
        }

        return evaluateExisting(existing.get(), requestHash, leaseOwner, now);
    }

    /**
     * 既存レコードから冪等応答を導く。
     *
     * <p>PROCESSING かつ lease が期限切れなら、観測した owner / expiry を CAS 条件に含めて
     * 回収を試みる。CAS に勝てば新しい leaseOwner で ACQUIRED（＝再実行可能）とし、
     * 負けた場合は他 worker が先に回収した後なので横取りせず PROCESSING を返す。
     * これが無いと、予約後に落ちた worker のキーが RECORD_TTL いっぱい詰まる。</p>
     */
    private BillingIdempotencyDecision evaluateExisting(BillingIdempotencyRecord record,
                                                        String requestHash, String leaseOwner,
                                                        Instant now) {
        if (!requestHash.equals(record.requestHash())) {
            // 既存 response 本文は漏らさず、コード由来の定型メッセージだけを返す。
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
        if (record.status() == BillingIdempotencyStatus.PROCESSING) {
            Instant leaseExpiresAt = record.leaseExpiresAt();
            if (leaseExpiresAt != null && !leaseExpiresAt.isAfter(now)
                    && recoverStale(record.id(), record.leaseOwner(), leaseExpiresAt, leaseOwner)) {
                return new BillingIdempotencyDecision(BillingIdempotencyDecisionKind.ACQUIRED,
                        record.id(), null, null, 0L);
            }
            long retryAfterSeconds = leaseExpiresAt == null
                    ? 0L
                    : Math.max(0L, Duration.between(now, leaseExpiresAt).toSeconds());
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

    /**
     * 確定済みでない（＝処理が例外で終わった）冪等レコードを FAILED へ確定する。
     *
     * <p>元の業務例外を握り潰さないため、ここでは例外を投げない（CAS 失敗＝lease を
     * 他者に奪われた場合は記録だけ残す）。応答 status / body は<b>保存しない</b>
     * （失敗時の HTTP status を controller が推測して刻むと嘘の記録になるため。
     * 保存済み本文が無い FAILED は replay できず、再送は 021/409 として弾かれる）。</p>
     */
    void fail(UUID id, String leaseOwner) {
        if (id == null || leaseOwner == null) {
            return;
        }
        try {
            if (repository.failIfLeaseOwner(id, leaseOwner, null, null, clock.instant()) != 1) {
                log.warn("冪等レコードの FAILED 確定が CAS で成立しませんでした id={}", id);
            }
        } catch (RuntimeException e) {
            log.error("冪等レコードの FAILED 確定に失敗しました id={}", id, e);
        }
    }

    /**
     * actor/method/path/key で既存の冪等レコード id を引く（照合キューへ刻む追跡子の解決用）。
     */
    Optional<UUID> findRecordId(long actorId, String httpMethod, String requestPath,
                                String idempotencyKey) {
        Optional<BillingIdempotencyRecord> found =
                repository.find(actorId, httpMethod, requestPath, idempotencyKey);
        return found == null ? Optional.empty() : found.map(BillingIdempotencyRecord::id);
    }

    /**
     * 期限切れ lease を新しい所有者へ回収する。
     *
     * @return CAS に勝って回収できたら true（負けた＝他 worker が先に回収した場合は false）
     */
    boolean recoverStale(UUID id, String previousLeaseOwner, Instant observedExpiry,
                         String newLeaseOwner) {
        Instant now = clock.instant();
        // 観測した owner と expiry を CAS 条件に含め、他 worker との二重回収を防ぐ。
        return repository.recoverStaleLease(id, previousLeaseOwner, observedExpiry, newLeaseOwner,
                now.plus(LEASE_DURATION), now) == 1;
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
