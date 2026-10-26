package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.dto.BlockedChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.dto.BulkCheckoutRequest;
import com.mannschaft.app.payment.dto.BulkCheckoutResponse;
import com.mannschaft.app.payment.dto.PayableDuesResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.dto.MyPaymentResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PayableDuesService} の単体テスト（純 Mockito・設計書 02_api_design §1.2）。
 *
 * <p>「本人のみ」「子あり」「権原なし除外」「alreadyPaid 整合」「bulk-checkout 部分成功」を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PayableDuesService 単体テスト")
class PayableDuesServiceTest {

    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private PaymentRequirementService paymentRequirementService;
    @Mock private PaymentItemService paymentItemService;
    @Mock private MemberPaymentService memberPaymentService;
    @Mock private GuardianshipSwitchService guardianshipSwitchService;
    @Mock private NameResolverService nameResolverService;

    @InjectMocks
    private PayableDuesService service;

    private static final Long PAYER_ID = 100L;
    private static final Long CHILD_ID = 200L;
    private static final Long TEAM_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final Long ITEM_ID_2 = 11L;

    @BeforeEach
    void setUp() {
        lenient().when(nameResolverService.resolveUserFullName(anyLong())).thenReturn("テスト ユーザー");
        lenient().when(nameResolverService.resolveScopeName(anyString(), anyLong())).thenReturn("テストチーム");
        lenient().when(memberPaymentRepository.findValidPaidPayments(anyLong(), anyLong()))
                .thenReturn(List.of());
    }

    private PaymentRequirementResponse requirement(Long itemId, String name, int amount) {
        return new PaymentRequirementResponse(
                new MyPaymentResponse.ScopeInfo("TEAM", TEAM_ID, null),
                "TEAM_ACCESS",
                new PaymentRequirementResponse.PaymentItemRequirement(
                        itemId, name, "ANNUAL_FEE",
                        BigDecimal.valueOf(amount), "JPY", "price_x", (short) 0),
                false, null);
    }

    private void noChildren() {
        when(guardianshipSwitchService.listSwitchableChildren(PAYER_ID))
                .thenReturn(new SwitchableChildrenResponse(List.of(), List.of()));
    }

    @Test
    @DisplayName("本人のみ（子なし）: 本人の未払い1件が SELF 権原で含まれる")
    void getPayableDues_selfOnly() {
        noChildren();
        when(paymentRequirementService.getPaymentRequirements(PAYER_ID))
                .thenReturn(List.of(requirement(ITEM_ID, "年会費", 5000)));
        when(paymentAuthorizationService.authorizePayment(PAYER_ID, PAYER_ID, ITEM_ID, false))
                .thenReturn(PayerRelationship.SELF);

        PayableDuesResponse res = service.getPayableDues(PAYER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).beneficiaryUserId()).isEqualTo(PAYER_ID);
        assertThat(res.items().get(0).authorizationVia()).isEqualTo("SELF");
        assertThat(res.items().get(0).faceAmount()).isEqualTo(5000);
        assertThat(res.items().get(0).totalCharge()).isEqualTo(5000);
        assertThat(res.items().get(0).kind()).isEqualTo("TERM");
        assertThat(res.items().get(0).alreadyPaid()).isFalse();
    }

    @Test
    @DisplayName("子あり: 後見下の子の未払いが GUARDIAN 権原で含まれる")
    void getPayableDues_withChild() {
        when(guardianshipSwitchService.listSwitchableChildren(PAYER_ID))
                .thenReturn(new SwitchableChildrenResponse(
                        List.of(new SwitchableChildDto(CHILD_ID, "子", "elementary", true)),
                        List.of()));
        // 本人は未払いなし、子は未払い1件。
        when(paymentRequirementService.getPaymentRequirements(PAYER_ID)).thenReturn(List.of());
        when(paymentRequirementService.getPaymentRequirements(CHILD_ID))
                .thenReturn(List.of(requirement(ITEM_ID, "年会費", 3000)));
        when(paymentAuthorizationService.authorizePayment(PAYER_ID, CHILD_ID, ITEM_ID, false))
                .thenReturn(PayerRelationship.GUARDIAN);

        PayableDuesResponse res = service.getPayableDues(PAYER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).beneficiaryUserId()).isEqualTo(CHILD_ID);
        assertThat(res.items().get(0).authorizationVia()).isEqualTo("GUARDIAN");
    }

    @Test
    @DisplayName("封印された子は対象外: blockedChildren は集約されない")
    void getPayableDues_blockedChildExcluded() {
        when(guardianshipSwitchService.listSwitchableChildren(PAYER_ID))
                .thenReturn(new SwitchableChildrenResponse(
                        List.of(),
                        List.of(new BlockedChildDto(CHILD_ID, "中学生の子", "junior_high", false, "AGE_LOCKED"))));
        when(paymentRequirementService.getPaymentRequirements(PAYER_ID)).thenReturn(List.of());

        PayableDuesResponse res = service.getPayableDues(PAYER_ID);

        assertThat(res.items()).isEmpty();
        // 封印された子の要件は問い合わせない。
        verify(paymentRequirementService, never()).getPaymentRequirements(CHILD_ID);
    }

    @Test
    @DisplayName("権原なし: authorizePayment が例外を投げる項目は除外される（IDOR 防止）")
    void getPayableDues_notAuthorizedExcluded() {
        noChildren();
        when(paymentRequirementService.getPaymentRequirements(PAYER_ID))
                .thenReturn(List.of(requirement(ITEM_ID, "年会費", 5000)));
        when(paymentAuthorizationService.authorizePayment(PAYER_ID, PAYER_ID, ITEM_ID, false))
                .thenThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        PayableDuesResponse res = service.getPayableDues(PAYER_ID);

        assertThat(res.items()).isEmpty();
    }

    @Test
    @DisplayName("alreadyPaid=true: 有効 PAID があれば paidBy/paidAt が埋まる")
    void getPayableDues_alreadyPaid() {
        noChildren();
        when(paymentRequirementService.getPaymentRequirements(PAYER_ID))
                .thenReturn(List.of(requirement(ITEM_ID, "年会費", 5000)));
        when(paymentAuthorizationService.authorizePayment(PAYER_ID, PAYER_ID, ITEM_ID, false))
                .thenReturn(PayerRelationship.SELF);
        MemberPaymentEntity paid = MemberPaymentEntity.builder()
                .userId(PAYER_ID)
                .paymentItemId(ITEM_ID)
                .payerUserId(PAYER_ID)
                .amountPaid(BigDecimal.valueOf(5000))
                .paidAt(java.time.LocalDateTime.of(2026, 6, 1, 10, 0))
                .build();
        when(memberPaymentRepository.findValidPaidPayments(PAYER_ID, ITEM_ID))
                .thenReturn(List.of(paid));

        PayableDuesResponse res = service.getPayableDues(PAYER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).alreadyPaid()).isTrue();
        assertThat(res.items().get(0).paidByUserId()).isEqualTo(PAYER_ID);
        assertThat(res.items().get(0).paidAt()).isNotNull();
    }

    @Test
    @DisplayName("bulkCheckout 部分成功: 1件は CHECKED_OUT、1件は ALREADY_PAID でスキップ")
    void bulkCheckout_partialSuccess() {
        PaymentItemEntity item1 = PaymentItemEntity.builder().name("会費A").amount(BigDecimal.valueOf(3000)).build();
        PaymentItemEntity item2 = PaymentItemEntity.builder().name("会費B").amount(BigDecimal.valueOf(4000)).build();
        when(paymentItemService.findByIdOrThrow(ITEM_ID)).thenReturn(item1);
        when(paymentItemService.findByIdOrThrow(ITEM_ID_2)).thenReturn(item2);

        // ITEM_ID は起票成功。ITEM_ID_2 は ALREADY_PAID で 409。
        when(memberPaymentService.createConnectCheckout(eq(ITEM_ID), eq(CHILD_ID), eq(PAYER_ID), anyString()))
                .thenReturn(null);
        when(memberPaymentService.createConnectCheckout(eq(ITEM_ID_2), eq(CHILD_ID), eq(PAYER_ID), anyString()))
                .thenThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_ALREADY_PAID));

        BulkCheckoutResponse res = service.bulkCheckout(PAYER_ID,
                new BulkCheckoutRequest(CHILD_ID, List.of(ITEM_ID, ITEM_ID_2)));

        assertThat(res.results()).hasSize(2);
        assertThat(res.results().get(0).status()).isEqualTo("CHECKED_OUT");
        assertThat(res.results().get(0).skipReason()).isNull();
        assertThat(res.results().get(1).status()).isEqualTo("SKIPPED");
        assertThat(res.results().get(1).skipReason()).isEqualTo("ALREADY_PAID");
    }

    @Test
    @DisplayName("bulkCheckout: 権原失効は NOT_AUTHORIZED でスキップ")
    void bulkCheckout_notAuthorized() {
        PaymentItemEntity item1 = PaymentItemEntity.builder().name("会費A").amount(BigDecimal.valueOf(3000)).build();
        when(paymentItemService.findByIdOrThrow(ITEM_ID)).thenReturn(item1);
        when(memberPaymentService.createConnectCheckout(eq(ITEM_ID), eq(CHILD_ID), eq(PAYER_ID), anyString()))
                .thenThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

        BulkCheckoutResponse res = service.bulkCheckout(PAYER_ID,
                new BulkCheckoutRequest(CHILD_ID, List.of(ITEM_ID)));

        assertThat(res.results()).hasSize(1);
        assertThat(res.results().get(0).status()).isEqualTo("SKIPPED");
        assertThat(res.results().get(0).skipReason()).isEqualTo("NOT_AUTHORIZED");
    }
}
