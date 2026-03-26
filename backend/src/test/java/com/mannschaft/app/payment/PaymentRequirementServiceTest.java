package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.service.PaymentRequirementService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
import static org.mockito.BDDMockito.given;

/**
 * {@link PaymentRequirementService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequirementService 単体テスト")
class PaymentRequirementServiceTest {

    @Mock private TeamAccessRequirementRepository teamAccessRequirementRepository;
    @Mock private OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private PaymentRequirementService service;

    @Nested
    @DisplayName("getTeamPaymentRequirements")
    class GetTeamPaymentRequirements {

        @Test
        @DisplayName("正常系: 未払い要件が返却される")
        void 未払い要件返却() {
            TeamAccessRequirementEntity req = TeamAccessRequirementEntity.builder()
                    .teamId(1L).paymentItemId(10L).build();
            given(teamAccessRequirementRepository.findByTeamId(1L)).willReturn(List.of(req));
            given(memberPaymentRepository.existsValidPaidPayment(100L, 10L)).willReturn(false);

            PaymentItemEntity item = PaymentItemEntity.builder()
                    .name("年会費").type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).currency("JPY").build();
            given(paymentItemService.findByIdOrThrow(10L)).willReturn(item);

            List<PaymentRequirementResponse> result = service.getTeamPaymentRequirements(100L, 1L);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 支払い済みの場合は空リスト")
        void 支払い済み空リスト() {
            TeamAccessRequirementEntity req = TeamAccessRequirementEntity.builder()
                    .teamId(1L).paymentItemId(10L).build();
            given(teamAccessRequirementRepository.findByTeamId(1L)).willReturn(List.of(req));
            given(memberPaymentRepository.existsValidPaidPayment(100L, 10L)).willReturn(true);

            List<PaymentRequirementResponse> result = service.getTeamPaymentRequirements(100L, 1L);

            assertThat(result).isEmpty();
        }
    }
}
