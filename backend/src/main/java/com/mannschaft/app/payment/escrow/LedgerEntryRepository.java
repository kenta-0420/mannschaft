package com.mannschaft.app.payment.escrow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 複式記帳台帳リポジトリ（追記専用）。
 *
 * <p>このフェーズでは Repo 骨格のみ（Service は次陣）。</p>
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    /** 取引に紐づく記帳行を追記順に取得する（整合検算用）。 */
    List<LedgerEntryEntity> findByEscrowTransactionIdOrderByCreatedAtAsc(UUID escrowTransactionId);
}
