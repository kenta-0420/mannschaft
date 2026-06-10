package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.CreatePaymentItemRequest;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentItemRequest;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentItemService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentItemService 単体テスト")
class PaymentItemServiceTest {

    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private TeamAccessRequirementRepository teamAccessRequirementRepository;
    @Mock private OrganizationAccessRequirementRepository organizationAccessRequirementRepository;
    @Mock private ContentPaymentGateRepository contentPaymentGateRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private PaymentMapper paymentMapper;

    @InjectMocks
    private PaymentItemService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("createTeamPaymentItem")
    class CreateTeamPaymentItem {

        @Test
        @DisplayName("正常系: チーム支払い項目が正常に作成される")
        void 正常作成() {
            CreatePaymentItemRequest request = new CreatePaymentItemRequest(
                    "年会費", "2026年度", "ANNUAL_FEE", new BigDecimal("5000"), "JPY",
                    true, (short) 0, (short) 0, null, null, null);

            PaymentItemEntity saved = PaymentItemEntity.builder()
                    .teamId(TEAM_ID).name("年会費").type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).currency("JPY").isActive(true).build();
            given(paymentItemRepository.save(any())).willReturn(saved);
            given(stripePaymentProvider.createProduct(any(), any())).willReturn("prod_xxx");
            given(stripePaymentProvider.createPrice(any(), any(), any())).willReturn("price_xxx");
            PaymentItemResponse response = PaymentItemResponse.builder()
                    .id(ITEM_ID)
                    .meta(new PaymentItemResponse.PaymentItemMetaDto("年会費", "2026年度", "ANNUAL_FEE", (short) 0, (short) 0))
                    .money(new PaymentItemResponse.PaymentMoneyDto(new BigDecimal("5000"), "JPY"))
                    .stripe(new PaymentItemResponse.StripeIntegrationDto(null, null))
                    .audit(new PaymentItemResponse.PaymentItemAuditDto(true, null, null))
                    .build();
            given(paymentMapper.toPaymentItemResponse(any())).willReturn(response);

            PaymentItemResponse result = service.createTeamPaymentItem(TEAM_ID, USER_ID, request);

            assertThat(result.getMeta().name()).isEqualTo("年会費");
        }
    }

    @Nested
    @DisplayName("updateTeamPaymentItem")
    class UpdateTeamPaymentItem {

        @Test
        @DisplayName("異常系: 支払い項目が見つからない")
        void 項目不存在() {
            given(paymentItemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateTeamPaymentItem(TEAM_ID, ITEM_ID,
                    new UpdatePaymentItemRequest(null, null, null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: type変更は拒否される")
        void type変更拒否() {
            PaymentItemEntity entity = PaymentItemEntity.builder()
                    .teamId(TEAM_ID).name("年会費").type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).currency("JPY").isActive(true).build();
            given(paymentItemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));

            UpdatePaymentItemRequest request = new UpdatePaymentItemRequest(
                    null, null, null, null, null, null, null, null, "MONTHLY_FEE");

            assertThatThrownBy(() -> service.updateTeamPaymentItem(TEAM_ID, ITEM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.TYPE_IMMUTABLE);
        }
    }

    @Nested
    @DisplayName("deleteTeamPaymentItem")
    class DeleteTeamPaymentItem {

        @Test
        @DisplayName("正常系: 論理削除で関連テーブルもクリーンアップされる")
        void 論理削除成功() {
            PaymentItemEntity entity = PaymentItemEntity.builder()
                    .teamId(TEAM_ID).name("年会費").type(PaymentItemType.ANNUAL_FEE)
                    .amount(new BigDecimal("5000")).build();
            given(paymentItemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(paymentItemRepository.save(any())).willReturn(entity);

            service.deleteTeamPaymentItem(TEAM_ID, ITEM_ID);

            verify(teamAccessRequirementRepository).deleteByPaymentItemId(any());
            verify(organizationAccessRequirementRepository).deleteByPaymentItemId(any());
            verify(contentPaymentGateRepository).deleteByPaymentItemId(any());
        }
    }

    @Nested
    @DisplayName("F08.9 P6: TERM 型バリデーション")
    class TermTypeValidation {

        private static final LocalDate TERM_ENDS_ON = LocalDate.of(2026, 12, 31);

        @Test
        @DisplayName("異常系: TERM 型で termEndsOn なしは TERM_END_DATE_REQUIRED エラー")
        void createPaymentItem_termType_withoutTermEndsOn_throws() {
            CreatePaymentItemRequest request = new CreatePaymentItemRequest(
                    "2026年度 春季期", "期別会費", "TERM", new BigDecimal("5000"), "JPY",
                    true, (short) 0, (short) 0, null,
                    LocalDate.of(2026, 4, 1), null); // termEndsOn = null

            assertThatThrownBy(() -> service.createTeamPaymentItem(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(PaymentErrorCode.TERM_END_DATE_REQUIRED);
        }

        @Test
        @DisplayName("正常系: TERM 型で termEndsOn ありは正常作成（Stripe 自動作成はスキップ）")
        void createPaymentItem_termType_withDates_succeeds() {
            CreatePaymentItemRequest request = new CreatePaymentItemRequest(
                    "2026年度 春季期", "期別会費", "TERM", new BigDecimal("5000"), "JPY",
                    true, (short) 0, (short) 0, null,
                    LocalDate.of(2026, 4, 1), TERM_ENDS_ON);

            PaymentItemEntity saved = PaymentItemEntity.builder()
                    .teamId(TEAM_ID).name("2026年度 春季期").type(PaymentItemType.TERM)
                    .amount(new BigDecimal("5000")).currency("JPY").isActive(true)
                    .termStartsOn(LocalDate.of(2026, 4, 1)).termEndsOn(TERM_ENDS_ON).build();
            given(paymentItemRepository.save(any())).willReturn(saved);

            PaymentItemResponse response = PaymentItemResponse.builder()
                    .id(ITEM_ID)
                    .meta(new PaymentItemResponse.PaymentItemMetaDto("2026年度 春季期", "期別会費", "TERM", (short) 0, (short) 0))
                    .money(new PaymentItemResponse.PaymentMoneyDto(new BigDecimal("5000"), "JPY"))
                    .stripe(new PaymentItemResponse.StripeIntegrationDto(null, null))
                    .audit(new PaymentItemResponse.PaymentItemAuditDto(true, null, null))
                    .term(new PaymentItemResponse.TermPeriodDto(LocalDate.of(2026, 4, 1), TERM_ENDS_ON))
                    .build();
            given(paymentMapper.toPaymentItemResponse(any())).willReturn(response);

            PaymentItemResponse result = service.createTeamPaymentItem(TEAM_ID, USER_ID, request);

            assertThat(result.getMeta().type()).isEqualTo("TERM");
            assertThat(result.getTerm().termEndsOn()).isEqualTo(TERM_ENDS_ON);
            // Stripe Price 自動作成はスキップされる（verifyNoInteractions は stripePaymentProvider に対して）
            // TERM 型で手動 stripePriceId 指定もないため、stripePaymentProvider は呼ばれない
            org.mockito.Mockito.verifyNoInteractions(stripePaymentProvider);
        }
    }
}
