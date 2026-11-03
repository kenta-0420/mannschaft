package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.payment.service.PaymentRequirementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentRequirementService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequirementService 単体テスト")
class PaymentRequirementServiceTest {

    @Mock private TeamAccessRequirementRepository teamAccessRequirementRepository;
    @Mock private OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private MembershipScopeQueryService membershipScopeQueryService;

    @InjectMocks
    private PaymentRequirementService service;

    private PaymentItemEntity item(long id) {
        return PaymentItemEntity.builder()
                .id(id).name("年会費").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("5000")).currency("JPY").build();
    }

    @Nested
    @DisplayName("getTeamPaymentRequirements")
    class GetTeamPaymentRequirements {

        @Test
        @DisplayName("正常系: 未払い要件が返却される")
        void 未払い要件返却() {
            TeamAccessRequirementEntity req = TeamAccessRequirementEntity.builder()
                    .teamId(1L).paymentItemId(10L).build();
            given(teamAccessRequirementRepository.findByTeamId(1L)).willReturn(List.of(req));
            given(memberPaymentRepository.findValidPaidPaymentItemIds(100L, List.of(10L))).willReturn(List.of());
            given(paymentItemRepository.findAllById(any())).willReturn(List.of(item(10L)));

            List<PaymentRequirementResponse> result = service.getTeamPaymentRequirements(100L, 1L);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 支払い済みの場合は空リスト（項目本体は取得しない）")
        void 支払い済み空リスト() {
            TeamAccessRequirementEntity req = TeamAccessRequirementEntity.builder()
                    .teamId(1L).paymentItemId(10L).build();
            given(teamAccessRequirementRepository.findByTeamId(1L)).willReturn(List.of(req));
            given(memberPaymentRepository.findValidPaidPaymentItemIds(100L, List.of(10L))).willReturn(List.of(10L));

            List<PaymentRequirementResponse> result = service.getTeamPaymentRequirements(100L, 1L);

            assertThat(result).isEmpty();
            verify(paymentItemRepository, never()).findAllById(any());
        }
    }

    @Nested
    @DisplayName("getPaymentRequirements")
    class GetPaymentRequirements {

        @Test
        @DisplayName("所属なしは空 IN を発行せず空リスト")
        void 所属なし() {
            given(membershipScopeQueryService.findActiveTeamIds(100L)).willReturn(List.of());
            given(membershipScopeQueryService.findActiveOrganizationIds(100L)).willReturn(List.of());

            assertThat(service.getPaymentRequirements(100L)).isEmpty();
            verify(teamAccessRequirementRepository, never()).findByTeamIdIn(any());
            verify(organizationAccessRequirementRepository, never()).findByOrganizationIdIn(any());
            verify(memberPaymentRepository, never()).findValidPaidPaymentItemIds(any(), any());
        }

        @Test
        @DisplayName("チーム→組織の順・同一項目の重複参照でも例外なし")
        void 順序と重複() {
            given(membershipScopeQueryService.findActiveTeamIds(100L)).willReturn(List.of(2L, 1L));
            given(membershipScopeQueryService.findActiveOrganizationIds(100L)).willReturn(List.of(5L));
            given(teamAccessRequirementRepository.findByTeamIdIn(any())).willReturn(List.of(
                    TeamAccessRequirementEntity.builder().teamId(1L).paymentItemId(10L).build(),
                    TeamAccessRequirementEntity.builder().teamId(2L).paymentItemId(10L).build()));
            given(organizationAccessRequirementRepository.findByOrganizationIdIn(any())).willReturn(List.of(
                    OrganizationAccessRequirementEntity.builder().organizationId(5L).paymentItemId(10L).build()));
            given(memberPaymentRepository.findValidPaidPaymentItemIds(any(), any())).willReturn(List.of());
            given(paymentItemRepository.findAllById(any())).willReturn(List.of(item(10L)));

            List<PaymentRequirementResponse> result = service.getPaymentRequirements(100L);

            assertThat(result).extracting(r -> r.getRequirementType() + ":" + r.getScope().getId())
                    .containsExactly("TEAM_ACCESS:2", "TEAM_ACCESS:1", "ORGANIZATION_ACCESS:5");
        }

        @Test
        @DisplayName("項目が見つからない要件は従前どおり PAYMENT_ITEM_NOT_FOUND")
        void 項目不在() {
            given(membershipScopeQueryService.findActiveTeamIds(100L)).willReturn(List.of(1L));
            given(membershipScopeQueryService.findActiveOrganizationIds(100L)).willReturn(List.of());
            given(teamAccessRequirementRepository.findByTeamIdIn(any())).willReturn(List.of(
                    TeamAccessRequirementEntity.builder().teamId(1L).paymentItemId(10L).build()));
            given(memberPaymentRepository.findValidPaidPaymentItemIds(any(), any())).willReturn(List.of());
            given(paymentItemRepository.findAllById(any())).willReturn(List.of());

            assertThatThrownBy(() -> service.getPaymentRequirements(100L))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
