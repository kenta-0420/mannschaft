package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
import com.mannschaft.app.billing.BillingContractService.ContractResult;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F20.1: {@link BillingContractApplicationService} 単体テスト（試練）。
 *
 * <p>冪等キーの吸収（M-1・AC）とテナント organization_id 解決（USER=null / ORG=自身 /
 * TEAM=主所属組織）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingContractApplicationService 単体テスト")
class BillingContractApplicationServiceTest {

    @Mock
    private BillingContractService billingContractService;
    @Mock
    private BillingContractRepository billingContractRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;
    @Mock
    private BillingIdempotencyService idempotencyService;
    @Mock
    private com.mannschaft.app.billing.BillingPriceResolver priceResolver;
    @Mock
    private com.mannschaft.app.billing.BillingCheckoutService checkoutService;

    @InjectMocks
    private BillingContractApplicationService appService;

    private ContractResult planResult(EntitlementScopeKind kind, Long scopeId, UUID id) {
        // 無償フロー（priceResolver は既定で null を返す＝createContract 経路）を前提とした ACTIVE 結果。
        return new ContractResult(id, kind, scopeId, ContractKind.PLAN, "FULL", null,
                ContractStatus.ACTIVE, 34, (short) 2, null, LocalDateTime.now(), null, null,
                List.of("ads.hide"), List.of());
    }

    @Test
    @DisplayName("AC 冪等: 未知キーは createContract を呼び結果を保存する")
    void create_firstTime_delegatesAndStores() {
        UUID id = UUID.randomUUID();
        given(idempotencyService.findStoredContractId(9L, "idem-1")).willReturn(null);
        given(billingContractService.createContract(
                eq(EntitlementScopeKind.USER), eq(9L), isNull(), eq(ContractKind.PLAN),
                eq("FULL"), isNull(), eq(9L)))
                .willReturn(planResult(EntitlementScopeKind.USER, 9L, id));

        ContractResponse resp = appService.create(EntitlementScopeKind.USER, 9L, 9L,
                new CreateContractRequest("PLAN", "FULL", null), "idem-1");

        assertThat(resp.getContractId()).isEqualTo(id.toString());
        verify(idempotencyService).store(9L, "idem-1", id);
    }

    @Test
    @DisplayName("AC 冪等: 既知キーは createContract を呼ばず既存契約を返す（二重送信の吸収）")
    void create_replay_returnsExistingWithoutDelegating() {
        UUID id = UUID.randomUUID();
        given(idempotencyService.findStoredContractId(9L, "idem-dup")).willReturn(id);

        BillingContractEntity existing = mock(BillingContractEntity.class);
        given(existing.getId()).willReturn(id);
        given(existing.getScopeKind()).willReturn(EntitlementScopeKind.USER);
        given(existing.getScopeId()).willReturn(9L);
        given(existing.getContractKind()).willReturn(ContractKind.PLAN);
        given(existing.getStatus()).willReturn(ContractStatus.ACTIVE);
        given(existing.getContractedAt()).willReturn(LocalDateTime.now());
        given(billingContractRepository.findByIdAndDeletedAtIsNull(id)).willReturn(java.util.Optional.of(existing));
        given(entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(any(), eq(id)))
                .willReturn(List.of());

        ContractResponse resp = appService.create(EntitlementScopeKind.USER, 9L, 9L,
                new CreateContractRequest("PLAN", "FULL", null), "idem-dup");

        assertThat(resp.getContractId()).isEqualTo(id.toString());
        verify(billingContractService, never()).createContract(any(), any(), any(), any(), any(), any(), any());
        verify(idempotencyService, never()).store(any(), any(), any());
    }

    @Test
    @DisplayName("AC organizationId 解決: TEAM は主所属組織（ACTIVE 所属の先頭）を渡す")
    void create_team_resolvesPrimaryOrg() {
        UUID id = UUID.randomUUID();
        given(idempotencyService.findStoredContractId(9L, "idem-t")).willReturn(null);
        given(teamOrgMembershipQueryService.findActiveOrganizationIds(123L)).willReturn(List.of(77L, 88L));
        given(billingContractService.createContract(
                eq(EntitlementScopeKind.TEAM), eq(123L), eq(77L), eq(ContractKind.PLAN),
                eq("FULL"), isNull(), eq(9L)))
                .willReturn(planResult(EntitlementScopeKind.TEAM, 123L, id));

        appService.create(EntitlementScopeKind.TEAM, 123L, 9L,
                new CreateContractRequest("PLAN", "FULL", null), "idem-t");

        verify(billingContractService).createContract(
                eq(EntitlementScopeKind.TEAM), eq(123L), eq(77L), eq(ContractKind.PLAN),
                eq("FULL"), isNull(), eq(9L));
    }

    @Test
    @DisplayName("AC organizationId 解決: ORG は scopeId 自身を渡す")
    void create_org_resolvesSelf() {
        UUID id = UUID.randomUUID();
        given(idempotencyService.findStoredContractId(9L, "idem-o")).willReturn(null);
        given(billingContractService.createContract(
                eq(EntitlementScopeKind.ORG), eq(55L), eq(55L), eq(ContractKind.PLAN),
                eq("FULL"), isNull(), eq(9L)))
                .willReturn(planResult(EntitlementScopeKind.ORG, 55L, id));

        appService.create(EntitlementScopeKind.ORG, 55L, 9L,
                new CreateContractRequest("PLAN", "FULL", null), "idem-o");

        verify(billingContractService).createContract(
                eq(EntitlementScopeKind.ORG), eq(55L), eq(55L), eq(ContractKind.PLAN),
                eq("FULL"), isNull(), eq(9L));
    }
}
