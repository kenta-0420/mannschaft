package com.mannschaft.app.billing;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 単一 future 制限の DB 側の最後の砦（{@code uk_bpv_single_future}）を実 DB で検証する
 * （御裁可 2026-09-24: PROVISIONING / PROVISION_FAILED も future として扱う）。
 *
 * <p>アプリ側の判定（{@code PriceRevisionCreateService} の FUTURE_STATUSES）を経由せず Repository へ直接
 * 保存し、生成列 {@code future_reservation_key} が PROVISIONING / PROVISION_FAILED の行でも非 null になって
 * UNIQUE で2本目の DRAFT を拒否することを確認する。test profile は ddl-auto=create のため、
 * Entity の {@code columnDefinition} と Flyway migration の生成列定義を同じにしておく必要がある。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("単一 future 制限 uk_bpv_single_future の実 DB 検証")
class PriceRevisionSingleFutureConstraintIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private BillingPriceVersionRepository versionRepository;

    @Test
    @DisplayName("PROVISIONING の revision がある商品へ2本目の DRAFT を保存すると UNIQUE 違反")
    void provisioningRevisionOccupiesFutureSlot() {
        assertSecondDraftRejected(BillingPriceVersionStatus.PROVISIONING);
    }

    @Test
    @DisplayName("PROVISION_FAILED の revision がある商品へ2本目の DRAFT を保存すると UNIQUE 違反")
    void provisionFailedRevisionOccupiesFutureSlot() {
        assertSecondDraftRejected(BillingPriceVersionStatus.PROVISION_FAILED);
    }

    private void assertSecondDraftRejected(BillingPriceVersionStatus existingStatus) {
        String productKey = "SFIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        versionRepository.saveAndFlush(revision(productKey, 1L, existingStatus));

        assertThatThrownBy(() -> versionRepository.saveAndFlush(
                revision(productKey, 2L, BillingPriceVersionStatus.DRAFT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(rootMessage(e))
                        .as("単一 future 制限の UNIQUE（uk_bpv_single_future）で拒否されること"
                                + "（別の制約違反での偶然の赤ではないこと）")
                        .contains("uk_bpv_single_future"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = BillingPriceVersionStatus.class,
            names = {"ACTIVE", "RETIRED", "CANCELLED"})
    @DisplayName("陰性対照: ACTIVE / RETIRED / CANCELLED の revision は future 枠を占有せず、新規 DRAFT を保存できる")
    void nonFutureRevisionDoesNotOccupyFutureSlot(BillingPriceVersionStatus existingStatus) {
        assertSecondDraftAccepted(existingStatus);
    }

    private void assertSecondDraftAccepted(BillingPriceVersionStatus existingStatus) {
        String productKey = "SFIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        versionRepository.saveAndFlush(revision(productKey, 1L, existingStatus));

        versionRepository.saveAndFlush(revision(productKey, 2L, BillingPriceVersionStatus.DRAFT));

        assertThat(versionRepository.findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
                BillingProductKind.PLAN, productKey, EntitlementScopeKind.TEAM)).hasSize(2);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    private static BillingPriceVersionEntity revision(String productKey, long revisionNo,
            BillingPriceVersionStatus status) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey(productKey)
                .scopeKind(EntitlementScopeKind.TEAM)
                .catalogRevision("REV-" + UuidV7.generate())
                .revisionNo(revisionNo)
                .status(status)
                .effectiveFrom(Instant.now().plusSeconds(3600 * revisionNo))
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UuidV7.generate());
        return entity;
    }
}
