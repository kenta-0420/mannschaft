package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 価格改定の取り消し（{@code POST /price-revisions/{id}/cancel}・御裁可 2026-09-24）の試練。
 *
 * <p>PROVISION_FAILED を単一 future 制限に含めたことで、修復できない失敗を抱えた revision が商品の future 枠を
 * 永久に塞ぎうる。その出口として DRAFT / READY / PROVISION_FAILED を CANCELLED にできることを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("価格改定の取り消し（cancel）")
class PriceRevisionCancelServiceTest {

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;
    @Mock private AuditLogService auditLogService;

    private PriceRevisionCancelService service() {
        return new PriceRevisionCancelService(versionRepository, bandRepository, auditLogService);
    }

    @ParameterizedTest
    @EnumSource(value = BillingPriceVersionStatus.class, names = {"DRAFT", "READY", "PROVISION_FAILED"})
    @DisplayName("DRAFT / READY / PROVISION_FAILED は取り消せ、revision と全 band が CANCELLED になり監査が残る")
    void cancellableStatusesBecomeCancelled(BillingPriceVersionStatus status) {
        BillingPriceVersionEntity revision = PriceRevisionProvisionServiceTest.revision(status);
        BillingPriceBandVersionEntity b1 = PriceRevisionProvisionServiceTest.band(revision, 1, "txcd_99999999");
        BillingPriceBandVersionEntity b2 = PriceRevisionProvisionServiceTest.band(revision, 2, "txcd_99999999");
        b1.setStatus(status == BillingPriceVersionStatus.READY ? BillingPriceVersionStatus.READY : status);
        b2.setStatus(BillingPriceVersionStatus.READY);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findAllByPriceVersionIdForUpdate(revision.getId())).willReturn(List.of(b1, b2));

        PriceRevisionResponse response = service().cancel(revision.getId(), revision.getLockVersion(), 700_001L);

        assertThat(response.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED);
        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED);
        assertThat(List.of(b1, b2)).allSatisfy(b ->
                assertThat(b.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED));
        verify(auditLogService).record(eq("PRICE_REVISION_CANCELLED"), eq(700_001L), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), contains(revision.getId().toString()));
    }

    @ParameterizedTest
    @EnumSource(value = BillingPriceVersionStatus.class,
            names = {"PROVISIONING", "SCHEDULED", "ACTIVE", "RETIRED", "CANCELLED"})
    @DisplayName("PROVISIONING（実行中）・SCHEDULED/ACTIVE/RETIRED（販売に関与済み）・CANCELLED は409で何も変えない")
    void nonCancellableStatusesAreConflict(BillingPriceVersionStatus status) {
        BillingPriceVersionEntity revision = PriceRevisionProvisionServiceTest.revision(status);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().cancel(revision.getId(), revision.getLockVersion(), 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.STATE_CONFLICT);
        assertThat(revision.getStatus()).isEqualTo(status);
        verify(bandRepository, never()).findAllByPriceVersionIdForUpdate(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("lockVersion 不一致は409（CAS）")
    void lockVersionMismatchIsConflict() {
        BillingPriceVersionEntity revision = PriceRevisionProvisionServiceTest.revision(BillingPriceVersionStatus.DRAFT);
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));

        assertThatThrownBy(() -> service().cancel(revision.getId(), revision.getLockVersion() + 1, 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        assertThat(revision.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("存在しない id は404")
    void unknownIdIsNotFound() {
        UUID missing = UUID.randomUUID();
        given(versionRepository.findByIdAndDeletedAtIsNull(missing)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancel(missing, 0L, 700_001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.REVISION_NOT_FOUND);
    }
}
