package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・PR-1）:
 * {@code billing_payer_handover_requests} エンティティ/リポジトリの JPA slice（試練先行）。
 *
 * <p>DB 生成列（{@code open_old_contract_id}）とその UNIQUE 制約は MySQL 固有機能のため
 * {@link BillingPayerHandoverFoundationFlywayIT} で検証する。ここでは Entity のデフォルト値・
 * 状態機械の 9 値保存・Repository のクエリメソッドを固定する。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
@DisplayName("billing_payer_handover_requests エンティティ/リポジトリ DataJpaTest")
class BillingPayerHandoverRequestEntityJpaTest {

    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    @Autowired private BillingPayerHandoverRequestRepository repository;

    @Test
    @DisplayName("@PrePersist: status 未指定なら REQUESTED・requested_at/created_at/updated_at が埋まる")
    void prePersist_defaultsToRequested() {
        BillingPayerHandoverRequestEntity request = BillingPayerHandoverRequestEntity.builder()
                .oldContractId(UUID.randomUUID())
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(10L)
                .oldPayerUserId(1L)
                .expiresAt(NOW.plus(14, ChronoUnit.DAYS))
                .build();

        BillingPayerHandoverRequestEntity saved = repository.saveAndFlush(request);

        assertThat(saved.getStatus()).isEqualTo(PayerHandoverStatus.REQUESTED);
        assertThat(saved.getRequestedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("9値の状態機械（MANUAL_INTERVENTION/PARTIALLY_COMPLETED含む）を保存・再読込できる")
    void allNineStatuses_persist() {
        for (PayerHandoverStatus status : PayerHandoverStatus.values()) {
            BillingPayerHandoverRequestEntity saved = repository.saveAndFlush(
                    BillingPayerHandoverRequestEntity.builder()
                            .oldContractId(UUID.randomUUID())
                            .scopeKind(EntitlementScopeKind.ORG)
                            .scopeId(20L)
                            .oldPayerUserId(2L)
                            .status(status)
                            .requestedAt(NOW)
                            .expiresAt(NOW.plus(14, ChronoUnit.DAYS))
                            .build());

            assertThat(repository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("findByOldContractIdAndStatusNotIn: 終端状態を除外して進行中の要求のみ返す")
    void findByOldContractIdAndStatusNotIn_excludesTerminal() {
        UUID oldContractId = UUID.randomUUID();
        repository.saveAndFlush(handover(oldContractId, PayerHandoverStatus.REQUESTED));
        repository.saveAndFlush(handover(oldContractId, PayerHandoverStatus.COMPLETED));

        List<BillingPayerHandoverRequestEntity> open = repository.findByOldContractIdAndStatusNotIn(
                oldContractId,
                List.of(PayerHandoverStatus.COMPLETED, PayerHandoverStatus.FAILED, PayerHandoverStatus.EXPIRED));

        assertThat(open).hasSize(1);
        assertThat(open.get(0).getStatus()).isEqualTo(PayerHandoverStatus.REQUESTED);
    }

    private BillingPayerHandoverRequestEntity handover(UUID oldContractId, PayerHandoverStatus status) {
        return BillingPayerHandoverRequestEntity.builder()
                .oldContractId(oldContractId)
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(30L)
                .oldPayerUserId(3L)
                .status(status)
                .requestedAt(NOW)
                .expiresAt(NOW.plus(14, ChronoUnit.DAYS))
                .build();
    }
}
