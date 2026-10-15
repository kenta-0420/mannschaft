package com.mannschaft.app.jobmatching.repository;

import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

/**
 * QR トークンリポジトリ。F13.1 Phase 13.1.2。
 */
public interface JobQrTokenRepository extends JpaRepository<JobQrTokenEntity, Long> {

    /**
     * 指定契約・種別の「現在有効な（未使用かつ未失効）」最新トークンを取得する。
     *
     * <p>Requester 画面の QR 再発行ロジックで、現在表示中のトークンが残っているかを判定するのに使用する。</p>
     *
     * @param contractId 契約 ID
     * @param type IN または OUT
     * @param now 現在時刻（{@code expires_at > now} の条件判定に使用）
     */
    Optional<JobQrTokenEntity> findTopByJobContractIdAndTypeAndUsedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
            Long contractId, JobCheckInType type, Instant now);

    /**
     * {@code nonce} を指定して悲観ロック付きで取得する。
     *
     * <p>QR スキャン検証時に同時並行スキャンによる二重消費を防ぐため、
     * 行ロックを取得してから {@code usedAt} をチェック／更新する。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<JobQrTokenEntity> findByNonce(String nonce);

    /**
     * 手動入力フォールバック用の短コードから「現在有効な（未使用かつ未失効）」トークンを取得する。
     *
     * @param shortCode 6 文字の短命コード
     * @param type IN または OUT
     * @param now 現在時刻
     */
    Optional<JobQrTokenEntity> findByShortCodeAndTypeAndUsedAtIsNullAndExpiresAtAfter(
            String shortCode, JobCheckInType type, Instant now);
}
