package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.PaymentSummaryResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.service.PaymentSummaryService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link PaymentSummaryService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentSummaryService 単体テスト")
class PaymentSummaryServiceTest {

    @Mock private PaymentItemService paymentItemService;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private PaymentSummaryService service;

    @Nested
    @DisplayName("getTeamPaymentSummary")
    class GetTeamPaymentSummary {

        @Test
        @DisplayName("正常系: チーム支払いサマリが返却される")
        void サマリ返却() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .teamId(1L).name("年会費").type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).currency("JPY").isActive(true)
                    .displayOrder((short) 0).build();
            given(paymentItemService.findTeamPaymentItems(1L)).willReturn(List.of(item));
            given(userRoleRepository.countByTeamId(1L)).willReturn(10L);
            given(memberPaymentRepository.countByPaymentItemIdAndStatus(any(), any())).willReturn(5L);
            given(memberPaymentRepository.sumPaidAmountByPaymentItemId(any())).willReturn(new BigDecimal("25000"));

            PaymentSummaryResponse result = service.getTeamPaymentSummary(1L);

            assertThat(result.getTotalMembers()).isEqualTo(10);
            assertThat(result.getItems()).hasSize(1);
        }
    }
}
