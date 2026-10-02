package com.mannschaft.app.billing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * F20.1: 契約作成の冪等キー（{@code Idempotency-Key} ヘッダ）を Valkey に短期保存する（設計書 02 §0・M-1）。
 *
 * <p>キー: {@code billing:idem:{userId}:{key}} → 最初の応答の contractId（TTL 24h）。
 * <b>時間差リトライ（ネットワーク再送・再押下）の吸収に限定</b>する。完全同時の再送に対する
 * 厳密な原子性は {@code active_contract_pointers.uk_acp_slot} が backstop する（L2・02 §0）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingIdempotencyService {

    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    private static String key(Long userId, String idempotencyKey) {
        return "billing:idem:" + userId + ":" + idempotencyKey;
    }

    /**
     * 既に記録済みの冪等キーがあれば、そのとき返した contractId を返す（無ければ null）。
     *
     * <p>Valkey 障害時は WARN で null 扱い（＝新規実行に倒す）。冪等吸収は best-effort であり
     * DB の一意制約が最終 backstop（02 §0・L2）。</p>
     */
    public UUID findStoredContractId(Long userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        try {
            String v = redisTemplate.opsForValue().get(key(userId, idempotencyKey));
            return v == null ? null : UUID.fromString(v);
        } catch (RuntimeException ex) {
            log.warn("BillingIdempotencyService: 冪等キー参照に失敗（続行）userId={} err={}", userId, ex.toString());
            return null;
        }
    }

    /** 契約作成の結果 contractId を冪等キーに紐付けて保存する（TTL 24h）。 */
    public void store(Long userId, String idempotencyKey, UUID contractId) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank() || contractId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(userId, idempotencyKey), contractId.toString(), TTL);
        } catch (RuntimeException ex) {
            log.warn("BillingIdempotencyService: 冪等キー保存に失敗（続行）userId={} err={}", userId, ex.toString());
        }
    }
}
