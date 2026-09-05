package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・V203）:
 * {@code billing_payer_handover_requests} リポジトリ。
 *
 * <p>このフェーズ（PR-1）では DDL＋読み取り専用の土台のみ。引継要求作成・承諾・切替TX等の
 * Service は後続 PR（設計書 PR-2）のスコープ。</p>
 */
public interface BillingPayerHandoverRequestRepository
        extends JpaRepository<BillingPayerHandoverRequestEntity, UUID> {

    /**
     * 対象契約に対する進行中（非終端）の引継要求を取得する。
     * {@code open_old_contract_id} 生成列と同じ「終端状態以外」の判定をアプリ層でも表現する
     * （生成列自体は DB 側の UNIQUE 制約担保用であり、このメソッドは Java 側からの参照用）。
     */
    List<BillingPayerHandoverRequestEntity> findByOldContractIdAndStatusNotIn(
            UUID oldContractId, List<PayerHandoverStatus> terminalStatuses);

    Optional<BillingPayerHandoverRequestEntity> findByNewContractId(UUID newContractId);
}
