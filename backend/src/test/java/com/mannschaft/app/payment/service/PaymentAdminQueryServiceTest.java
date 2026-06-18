package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P1: {@link PaymentAdminQueryService} 単体テスト。
 * 番人テスト: 件数は issuer_scope_kind=ORG + issuer_scope_id で SENT/VIEWED/OVERDUE を集計し、
 * プレビューも同条件（StatusIn）で取得していること（テナント越境防止）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAdminQueryService 単体テスト")
class PaymentAdminQueryServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private PaymentAdminQueryService service;

    private static final Long ORG_ID = 20L;
    private static final String ORG_SLUG = "dev-org";

    @Test
    @DisplayName("preview_size=0 → 未収3ステータスを 1 COUNT(StatusIn) で集計・個別 COUNT は呼ばない")
    void countOnlyUsesSingleStatusInCount() {
        given(paymentRequestRepository.countByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(
                eq(ScopeKind.ORG), eq(ORG_ID), anyCollection())).willReturn(6L);

        PendingAggregate result = service.unsettledForOrg(ORG_ID, ORG_SLUG, 0);

        assertThat(result.pendingCount()).isEqualTo(6);
        assertThat(result.items()).isEmpty();
        // 1 COUNT 化（設計書 03 §4.5）: 個別ステータス COUNT は使わない
        verify(paymentRequestRepository, never())
                .countByIssuerScopeKindAndIssuerScopeIdAndStatusAndDeletedAtIsNull(any(), any(), any());
        verify(paymentRequestRepository, never())
                .findByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(any(), any(), anyCollection(), any());
    }

    @Test
    @DisplayName("preview_size>0 → ORG + StatusIn でプレビュー取得・発行者名バルク解決・detail_route は id を含む個別遷移先")
    void countAndPreview() {
        given(paymentRequestRepository.countByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(
                eq(ScopeKind.ORG), eq(ORG_ID), anyCollection())).willReturn(1L);
        PaymentRequestEntity p = PaymentRequestEntity.builder()
                .issuerScopeKind(ScopeKind.ORG).issuerScopeId(ORG_ID)
                .title("年会費請求").status(PaymentRequestStatus.SENT).createdBy(77L).build();
        UUID pid = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        p.setId(pid);
        given(paymentRequestRepository.findByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(
                eq(ScopeKind.ORG), eq(ORG_ID), anyCollection(), any()))
                .willReturn(new PageImpl<>(List.of(p), PageRequest.of(0, 3), 1));
        given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(77L, "経理担当"));

        PendingAggregate result = service.unsettledForOrg(ORG_ID, ORG_SLUG, 3);

        assertThat(result.items()).hasSize(1);
        PendingAggregate.Item item = result.items().get(0);
        assertThat(item.title()).isEqualTo("年会費請求");
        assertThat(item.requestedBy()).isEqualTo("経理担当");
        // id は主キー(UUID)の文字列・detail_route は id を含む個別遷移先（§3.1 / §3.3）
        assertThat(item.id()).isEqualTo(pid.toString());
        assertThat(item.detailRoute()).isEqualTo("/organizations/dev-org/admin/payments/" + pid);
    }
}
