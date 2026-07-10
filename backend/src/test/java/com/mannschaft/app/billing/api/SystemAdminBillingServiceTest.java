package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PagedContractResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F20.1: {@link SystemAdminBillingService} の契約横断検索 page size 上限テスト（試練）。
 *
 * <p>無制限な size で巨大クエリを発行されないよう {@code MAX_PAGE_SIZE}(=50) でキャップされることを検証する
 * （promotion 側 CRUD の max50 に揃える）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminBillingService page size 上限テスト")
class SystemAdminBillingServiceTest {

    @Mock
    private com.mannschaft.app.billing.PlanRepository planRepository;
    @Mock
    private com.mannschaft.app.billing.FeatureCatalogRepository featureCatalogRepository;
    @Mock
    private com.mannschaft.app.billing.PlanFeatureRepository planFeatureRepository;
    @Mock
    private com.mannschaft.app.billing.PlanPriceBandRepository planPriceBandRepository;
    @Mock
    private BillingContractRepository billingContractRepository;
    @Mock
    private com.mannschaft.app.billing.BillingContractService billingContractService;
    @Mock
    private com.mannschaft.app.team.service.TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    @InjectMocks
    private SystemAdminBillingService service;

    @Test
    @DisplayName("AC: size=1000 は MAX_PAGE_SIZE(50) でキャップされる")
    void searchContracts_capsPageSize() {
        Page<BillingContractEntity> empty = new PageImpl<>(List.of());
        given(billingContractRepository.searchContracts(any(), any(), any(), any(Pageable.class)))
                .willReturn(empty);

        PagedContractResponse resp = service.searchContracts(null, null, null, 0, 1000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(billingContractRepository).searchContracts(isNull(), isNull(), isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(SystemAdminBillingService.MAX_PAGE_SIZE);
        assertThat(resp.getSize()).isEqualTo(SystemAdminBillingService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("AC: 上限以内の size はそのまま使われる")
    void searchContracts_belowCapUnchanged() {
        given(billingContractRepository.searchContracts(any(), any(), any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        service.searchContracts("TEAM", 123L, "ACTIVE", 2, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(billingContractRepository).searchContracts(
                eq(EntitlementScopeKind.TEAM), eq(123L), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }
}
