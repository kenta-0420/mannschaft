package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・PR-1）: {@code billing_contracts} への
 * {@code payer_user_id}/{@code handover_request_id} 追加・{@code PENDING_HANDOVER} 状態・
 * {@code hardDeleteBySlotAndContractId} を JPA slice で固定する（試練先行）。
 *
 * <p>対象 AC: AC-1（payer_user_id の土台）／AC-14 の土台（contract_id 一致条件での hardDelete）。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_contracts payer/PENDING_HANDOVER・hardDeleteBySlotAndContractId DataJpaTest")
class BillingContractPayerHandoverJpaTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 5, 12, 0, 0);

    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private ActiveContractPointerRepository activeContractPointerRepository;

    @Test
    @DisplayName("AC-1土台: payer_user_id / handover_request_id が persist され再読込できる")
    void payerUserIdAndHandoverRequestId_persist() {
        UUID handoverRequestId = UUID.randomUUID();
        BillingContractEntity contract = contract(ContractStatus.PENDING_HANDOVER, 42L, handoverRequestId);

        BillingContractEntity saved = billingContractRepository.saveAndFlush(contract);
        billingContractRepository.flush();

        BillingContractEntity reloaded = billingContractRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPayerUserId()).isEqualTo(42L);
        assertThat(reloaded.getHandoverRequestId()).isEqualTo(handoverRequestId);
        assertThat(reloaded.getStatus()).isEqualTo(ContractStatus.PENDING_HANDOVER);
    }

    @Test
    @DisplayName("R2-P0-1: PENDING_HANDOVER（16文字）が status 列長に収まり切り捨てられない")
    void pendingHandoverStatus_fitsColumnLength() {
        BillingContractEntity saved = billingContractRepository.saveAndFlush(
                contract(ContractStatus.PENDING_HANDOVER, 1L, null));

        assertThat(billingContractRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.PENDING_HANDOVER);
    }

    @Test
    @DisplayName("AC-14土台: contract_id 一致のときのみ hardDeleteBySlotAndContractId が削除する")
    void hardDeleteBySlotAndContractId_onlyDeletesWhenContractIdMatches() {
        UUID oldContractId = UUID.randomUUID();
        UUID newContractId = UUID.randomUUID();
        // 切替TXにより pointer は既に新契約へ付け替わっている想定。
        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(77L)
                .contractKind(ContractKind.PLAN)
                .addonFeatureKey("")
                .contractId(newContractId)
                .build();
        activeContractPointerRepository.saveAndFlush(pointer);

        // 旧契約由来の webhook が遅延到達し、旧 contract_id を条件に削除しようとするケース。
        int deletedByOldContract = activeContractPointerRepository.hardDeleteBySlotAndContractId(
                EntitlementScopeKind.TEAM, 77L, ContractKind.PLAN, "", oldContractId);
        assertThat(deletedByOldContract).as("contract_id 不一致は 0 件更新であること").isZero();
        assertThat(activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        EntitlementScopeKind.TEAM, 77L, ContractKind.PLAN, ""))
                .as("新契約の pointer は無傷のまま残ること")
                .isPresent();

        int deletedByNewContract = activeContractPointerRepository.hardDeleteBySlotAndContractId(
                EntitlementScopeKind.TEAM, 77L, ContractKind.PLAN, "", newContractId);
        assertThat(deletedByNewContract).as("contract_id 一致は 1 件削除されること").isEqualTo(1);
        assertThat(activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        EntitlementScopeKind.TEAM, 77L, ContractKind.PLAN, ""))
                .isEmpty();
    }

    private BillingContractEntity contract(ContractStatus status, Long payerUserId, UUID handoverRequestId) {
        return BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(91L)
                .organizationId(5L)
                .contractKind(ContractKind.PLAN)
                .planKey("PRO")
                .status(status)
                .contractedAt(NOW.minusDays(1))
                .createdBy(7L)
                .payerUserId(payerUserId)
                .handoverRequestId(handoverRequestId)
                .build();
    }
}
