package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.RetryAfterBusinessException;
import com.mannschaft.app.effortrecord.entity.EffortOperationReceiptEntity;
import com.mannschaft.app.effortrecord.enums.EffortReceiptRouteKind;
import com.mannschaft.app.effortrecord.repository.EffortOperationReceiptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 Idempotency-Key の receipt（API 契約 §4.1・データ設計 §2）。
 *
 * <p><b>呼出し側の業務 TX に必ず参加する</b>（{@link Propagation#MANDATORY}）。receipt の確保・業務更新・応答保存を
 * 同一 TX で確定するため、commit 前の例外では原本も receipt も残らず、プロセス停止でも永久の「処理中」が残らない
 * （AC-62）。</p>
 *
 * <ul>
 *   <li>同 key・同 hash・24 時間以内: 保存済みの status/body を返す（{@link Handle#replay()}）。</li>
 *   <li>同 key・別 hash: 409 {@code COMMON_003}。</li>
 *   <li>同 key が別 TX で処理中（未 commit の receipt 行が存在）: 待たずに 409 + {@code Retry-After: 1}。
 *       INSERT の行ロック待ちを {@code SET SESSION innodb_lock_wait_timeout=1} で最大 1 秒に抑え、
 *       ロック待ち超過・デッドロック・重複キー（直前に別 TX が commit）をすべて「処理中」として扱う。</li>
 *   <li>24 時間経過した receipt は同 key の再利用時に置き換える（保証外）。期限削除は
 *       {@link EffortOperationReceiptCleanupBatchService}。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EffortReceiptService {

    /** 保持期間（データ設計 §2: 24 時間）。 */
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final EffortOperationReceiptRepository receipts;
    private final EffortSnapshotCodec codec;
    @Qualifier("utcClock")
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    /** 保存済み応答。 */
    public record StoredResponse(int status, String json) {
    }

    /** {@link #begin} の結果。replay が非 null なら業務処理を行わず保存済み応答を返す。 */
    public record Handle(UUID receiptId, StoredResponse replay) {
    }

    /** request hash（HTTP method+path と本文・query・version を束ねる）。 */
    public String requestHash(String operationPath, String canonicalBody) {
        return codec.hash(operationPath + "\n" + canonicalBody);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Handle begin(Long ownerUserId, String operationPath, UUID key, String requestHash) {
        String operationKey = key.toString();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<EffortOperationReceiptEntity> existing = receipts
                .findByRouteKindAndRouteIdAndActorUserIdAndOperationPathAndOperationKey(
                        EffortReceiptRouteKind.OWNER, ownerUserId, ownerUserId, operationPath, operationKey);
        if (existing.isPresent()) {
            EffortOperationReceiptEntity receipt = existing.get();
            if (receipt.getExpiresAt().isAfter(now)) {
                if (!receipt.getRequestHash().equals(requestHash)) {
                    throw new BusinessException(CommonErrorCode.COMMON_003);
                }
                if (receipt.getResponseStatus() == null || receipt.getResponseJson() == null) {
                    throw new RetryAfterBusinessException(CommonErrorCode.COMMON_003, 1);
                }
                return new Handle(receipt.getId(), new StoredResponse(receipt.getResponseStatus(), receipt.getResponseJson()));
            }
            // 24 時間経過後の同 key は保証外。古い receipt を消して新規として扱う。
            entityManager.createNativeQuery("DELETE FROM effort_operation_receipts WHERE id = UNHEX(REPLACE(?1, '-', ''))")
                    .setParameter(1, receipt.getId().toString()).executeUpdate();
            entityManager.detach(receipt);
        }

        UUID receiptId = com.mannschaft.app.common.UuidV7.generate();
        // 同 key の未 commit 行があると INSERT は行ロック待ちになる。待たずに「処理中」を返すため、
        // この INSERT の間だけ接続のロック待ちを 1 秒に短縮し、必ず既定へ戻す（接続は pool で再利用される）。
        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
        try {
            entityManager.createNativeQuery(
                            "INSERT INTO effort_operation_receipts "
                                    + "(id, route_kind, route_id, actor_user_id, operation_key, operation_path, request_hash, "
                                    + "response_status, response_json, created_at, updated_at, expires_at) "
                                    + "VALUES (UNHEX(REPLACE(?1, '-', '')), 'OWNER', ?2, ?3, ?4, ?5, ?6, NULL, NULL, ?7, ?8, ?9)")
                    .setParameter(1, receiptId.toString())
                    .setParameter(2, ownerUserId)
                    .setParameter(3, ownerUserId)
                    .setParameter(4, operationKey)
                    .setParameter(5, operationPath)
                    .setParameter(6, requestHash)
                    .setParameter(7, now)
                    .setParameter(8, now)
                    .setParameter(9, now.plus(RETENTION))
                    .executeUpdate();
        } catch (RuntimeException e) {
            if (isInProgress(e)) {
                throw new RetryAfterBusinessException(CommonErrorCode.COMMON_003, 1);
            }
            throw e;
        } finally {
            entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = DEFAULT").executeUpdate();
        }
        return new Handle(receiptId, null);
    }

    /**
     * 24 時間以内の同 key の receipt を、TX 外の事前検証（TEAM 定義 token の期限・現在所属など）より前に判定する
     * （書込なしの先読み）。同 hash で完了済みなら保存済み応答を返し、別 hash なら 409 {@code COMMON_003} を確定する
     * （API 契約 §4.1。別本文を「未完了」扱いにすると後続の token 失効 400 や脱退 403 が key 衝突を覆い隠す）。
     * 有効な receipt が無い・同 hash で未完了（別 TX が処理中で未 commit）のときは empty とし、{@link #begin} に委ねる。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredResponse> findCompleted(Long ownerUserId, String operationPath, UUID key, String requestHash) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<EffortOperationReceiptEntity> live = receipts
                .findByRouteKindAndRouteIdAndActorUserIdAndOperationPathAndOperationKey(
                        EffortReceiptRouteKind.OWNER, ownerUserId, ownerUserId, operationPath, key.toString())
                .filter(r -> r.getExpiresAt().isAfter(now));
        if (live.isPresent() && !live.get().getRequestHash().equals(requestHash)) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        return live.filter(r -> r.getResponseStatus() != null && r.getResponseJson() != null)
                .map(r -> new StoredResponse(r.getResponseStatus(), r.getResponseJson()));
    }

    /** 応答を同一 TX で保存して receipt を確定する。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID receiptId, int status, String responseJson) {
        entityManager.createNativeQuery("UPDATE effort_operation_receipts SET response_status = ?1, response_json = ?2, "
                        + "updated_at = UTC_TIMESTAMP(6) WHERE id = UNHEX(REPLACE(?3, '-', ''))")
                .setParameter(1, status)
                .setParameter(2, responseJson)
                .setParameter(3, receiptId.toString())
                .executeUpdate();
    }

    /** ロック待ち超過・デッドロック・重複キーは、別 TX が同 key を処理中（または直前に完了）と判定する。 */
    private static boolean isInProgress(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                int code = sql.getErrorCode();
                if (code == MYSQL_LOCK_WAIT_TIMEOUT || code == MYSQL_DEADLOCK || code == MYSQL_DUPLICATE_ENTRY) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
