package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.9 P5 第一波: {@link MembershipSubscriptionEntity} / {@link MembershipSubscriptionRepository} の
 * 永続化・取得テスト（実 MySQL / Testcontainers）。
 *
 * <p>主に UUIDv7 主キーの BINARY(16) 往復・enum 列（status/scope_kind/billing_interval）の永続化・
 * テナント＆払い手＆受領主体の引き当てクエリを検証する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる（CI で実行）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.1</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("MembershipSubscriptionRepository 永続化テスト（Testcontainers）")
class MembershipSubscriptionRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MembershipSubscriptionRepository repository;

    private MembershipSubscriptionEntity newActive(Long payerUserId, Long beneficiaryUserId,
                                                   String stripeSubId) {
        return MembershipSubscriptionEntity.builder()
                .organizationId(900L)
                .paymentItemId(10L)
                .beneficiaryUserId(beneficiaryUserId)
                .payerUserId(payerUserId)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(50L)
                .payeeConnectAccountId(UUID.randomUUID())
                .stripeSubscriptionId(stripeSubId)
                .stripeCustomerId("cus_test")
                .billingInterval(BillingInterval.MONTHLY)
                .billingAnchorDay((short) 15)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .feePolicyKey("DEFAULT")
                .faceAmount(1000)
                .currency("JPY")
                .currentPeriodStart(LocalDate.of(2026, 6, 1))
                .currentPeriodEnd(LocalDate.of(2026, 6, 30))
                .build();
    }

    @Test
    @DisplayName("save → findByIdAndDeletedAtIsNull で UUID 主キーが BINARY(16) 往復する")
    void persistAndFindById() {
        MembershipSubscriptionEntity saved = repository.save(newActive(2L, 1L, "sub_round_trip"));
        assertThat(saved.getId()).isNotNull();

        Optional<MembershipSubscriptionEntity> found = repository.findByIdAndDeletedAtIsNull(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
        assertThat(found.get().getBillingInterval()).isEqualTo(BillingInterval.MONTHLY);
        assertThat(found.get().getScopeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(found.get().getFaceAmount()).isEqualTo(1000);
        assertThat(found.get().getCurrentPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("findByStripeSubscriptionIdAndDeletedAtIsNull で Webhook 引き当てができる")
    void findByStripeSubscriptionId() {
        repository.save(newActive(3L, 4L, "sub_webhook_key"));

        Optional<MembershipSubscriptionEntity> found =
                repository.findByStripeSubscriptionIdAndDeletedAtIsNull("sub_webhook_key");
        assertThat(found).isPresent();
        assertThat(found.get().getPayerUserId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("findByPayerUserIdAndStatusIn で払い手視点の一覧を状態で絞れる")
    void findByPayerUserIdAndStatusIn() {
        repository.save(newActive(7L, 8L, "sub_payer_a"));

        List<MembershipSubscriptionEntity> list =
                repository.findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        7L, List.of(MembershipSubscriptionStatus.ACTIVE));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStripeSubscriptionId()).isEqualTo("sub_payer_a");
    }

    @Test
    @DisplayName("findByScopeKindAndScopeId で受領主体（チーム）視点の一覧を引ける")
    void findByScope() {
        repository.save(newActive(11L, 12L, "sub_scope_a"));

        List<MembershipSubscriptionEntity> list =
                repository.findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(ScopeKind.TEAM, 50L);
        assertThat(list).extracting(MembershipSubscriptionEntity::getStripeSubscriptionId)
                .contains("sub_scope_a");
    }
}
