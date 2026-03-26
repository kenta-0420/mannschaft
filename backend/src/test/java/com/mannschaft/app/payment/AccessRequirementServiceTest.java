package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.AccessRequirementsRequest;
import com.mannschaft.app.payment.dto.AccessRequirementsResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.payment.service.AccessRequirementService;
import com.mannschaft.app.payment.service.PaymentItemService;
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
import static org.mockito.BDDMockito.given;

/**
 * {@link AccessRequirementService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessRequirementService 単体テスト")
class AccessRequirementServiceTest {

    @Mock private TeamAccessRequirementRepository teamAccessRequirementRepository;
    @Mock private OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    @Mock private PaymentItemService paymentItemService;

    @InjectMocks
    private AccessRequirementService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("setTeamAccessRequirements")
    class SetTeamAccessRequirements {

        @Test
        @DisplayName("異常系: DONATION はアクセス制御に設定不可")
        void DONATION設定不可() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .teamId(TEAM_ID).type(PaymentItemType.DONATION)
                    .amount(BigDecimal.ZERO).build();
            given(paymentItemService.findByIdOrThrow(10L)).willReturn(item);

            AccessRequirementsRequest request = new AccessRequirementsRequest(List.of(10L));

            assertThatThrownBy(() -> service.setTeamAccessRequirements(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.DONATION_NOT_ALLOWED_FOR_ACCESS);
        }

        @Test
        @DisplayName("異常系: スコープ外の支払い項目はエラー")
        void スコープ外エラー() {
            PaymentItemEntity item = PaymentItemEntity.builder()
                    .teamId(999L).type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).build();
            given(paymentItemService.findByIdOrThrow(10L)).willReturn(item);

            AccessRequirementsRequest request = new AccessRequirementsRequest(List.of(10L));

            assertThatThrownBy(() -> service.setTeamAccessRequirements(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.PAYMENT_ITEM_SCOPE_MISMATCH);
        }
    }
}
