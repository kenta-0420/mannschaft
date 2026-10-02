package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PaymentGateService} 単体テスト（F08.9 P4 ペイウォール・受益者キー判定）。
 *
 * <h3>テスト観点（設計書 02 §6 / 03_security §4）</h3>
 * <ul>
 *   <li>①全 payment 済 → accessible=true</li>
 *   <li>②1つ未払い → accessible=false ＋ requiredItems に satisfied=false が含まれる</li>
 *   <li>③ゲートなし → accessible=true（誰でも閲覧可）</li>
 *   <li>④titleHidden=true のゲート → titleHidden=true ＋ requiredItems は存在秘匿で空</li>
 *   <li>⑤fail-safe（payment_item 消失・payment_item_id 欠落 = 不整合）→ accessible=false</li>
 *   <li>受益者キー: viewer 自身の userId でのみ existsValidPaidPayment を評価する（他人で解錠しない）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentGateService 単体テスト（F08.9 P4 ペイウォール）")
class PaymentGateServiceTest {

    @Mock private ContentPaymentGateRepository contentPaymentGateRepository;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private PaymentItemRepository paymentItemRepository;

    @InjectMocks
    private PaymentGateService service;

    private static final String CONTENT_TYPE = "POST";
    private static final Long CONTENT_ID = 500L;
    private static final Long VIEWER_USER_ID = 42L;
    private static final Long OTHER_USER_ID = 999L;

    private static ContentPaymentGateEntity gate(Long id, Long paymentItemId, boolean titleHidden) {
        return gate(id, paymentItemId, CONTENT_ID, titleHidden);
    }

    private static ContentPaymentGateEntity gate(Long id, Long paymentItemId,
                                                  Long contentId, boolean titleHidden) {
        return ContentPaymentGateEntity.builder()
                .id(id)
                .paymentItemId(paymentItemId)
                .contentType(CONTENT_TYPE)
                .contentId(contentId)
                .isTitleHidden(titleHidden)
                .build();
    }

    private static PaymentItemEntity item(String name, BigDecimal amount) {
        return PaymentItemEntity.builder()
                .name(name)
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(amount)
                .currency("JPY")
                .build();
    }

    private static PaymentItemEntity scopedItem(Long id, String name, BigDecimal amount) {
        return item(name, amount).toBuilder().id(id).teamId(77L).build();
    }

    private void givenBound(List<ContentPaymentGateEntity> gates,
                            List<PaymentItemEntity> items, List<Long> paidItemIds) {
        given(contentPaymentGateRepository.findByContentTypeAndContentIdIn(eq(CONTENT_TYPE), any()))
                .willReturn(gates);
        given(paymentItemRepository.findAllById(any())).willReturn(items);
        if (!gates.stream().map(ContentPaymentGateEntity::getPaymentItemId)
                .filter(java.util.Objects::nonNull).toList().isEmpty()) {
            given(memberPaymentRepository.findValidPaidPaymentItemIds(eq(VIEWER_USER_ID), anyList()))
                    .willReturn(paidItemIds);
        }
    }

    private static ContentGateTarget target(Long id) {
        return new ContentGateTarget(id, 77L, null);
    }

    private GateCheckResponse checkBound() {
        return service.checkAccess(CONTENT_TYPE, CONTENT_ID, VIEWER_USER_ID, target(CONTENT_ID));
    }

    @Nested
    @DisplayName("① 全 payment 済 → accessible=true")
    class AllPaid {

        @Test
        @DisplayName("batch判定はvalidなPAID項目集合を一括取得し、複数コンテンツを同じ基準で判定する")
        void batchUsesValidPaidItemsForMultipleContents() {
            ContentPaymentGateEntity first = gate(1L, 100L, false);
            ContentPaymentGateEntity second = gate(2L, 200L, 501L, true);
            given(contentPaymentGateRepository.findByContentTypeAndContentIdIn(eq(CONTENT_TYPE), any()))
                    .willReturn(List.of(first, second));
            PaymentItemEntity item100 = scopedItem(100L, "item100", new BigDecimal("100"));
            PaymentItemEntity item200 = scopedItem(200L, "item200", new BigDecimal("200"));
            given(paymentItemRepository.findAllById(any())).willReturn(List.of(item100, item200));
            given(memberPaymentRepository.findValidPaidPaymentItemIds(eq(VIEWER_USER_ID), anyList()))
                    .willReturn(List.of(100L, 200L));

            Map<Long, GateCheckResponse> result = service.checkAccessBatch(
                    CONTENT_TYPE, List.of(CONTENT_ID, 501L), VIEWER_USER_ID,
                    Map.of(CONTENT_ID, target(CONTENT_ID), 501L, target(501L)));

            assertThat(result.get(CONTENT_ID).isAccessible()).isTrue();
            assertThat(result.get(501L).isAccessible()).isTrue();
            verify(memberPaymentRepository).findValidPaidPaymentItemIds(eq(VIEWER_USER_ID), anyList());
        }

        @Test
        @DisplayName("複数ゲートすべて支払い済 → accessible=true / requiredItems 全 satisfied=true")
        void allSatisfied_accessibleTrue() {
            givenBound(
                    List.of(gate(1L, 100L, false), gate(2L, 200L, false)),
                    List.of(scopedItem(100L, "月会費", new BigDecimal("3000")),
                            scopedItem(200L, "施設利用料", new BigDecimal("1500"))),
                    List.of(100L, 200L));

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isTrue();
            assertThat(res.isTitleHidden()).isFalse();
            assertThat(res.getRequiredItems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("② 1つ未払い → accessible=false")
    class OneUnpaid {

        @Test
        @DisplayName("一部未払い → accessible=false / 未払い項目が satisfied=false で requiredItems に含まれる")
        void oneUnpaid_accessibleFalse() {
            givenBound(
                    List.of(gate(1L, 100L, false), gate(2L, 200L, false)),
                    List.of(scopedItem(100L, "月会費", new BigDecimal("3000")),
                            scopedItem(200L, "施設利用料", new BigDecimal("1500"))),
                    List.of(100L));

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isFalse();
            assertThat(res.getRequiredItems()).hasSize(1);
            assertThat(res.getRequiredItems().get(0).getPaymentItemId()).isEqualTo(200L);
            assertThat(res.getRequiredItems().get(0).isSatisfied()).isFalse();
        }
    }

    @Nested
    @DisplayName("③ ゲートなし → accessible=true（誰でも閲覧可）")
    class NoGate {

        @Test
        @DisplayName("ゲート未設定 → accessible=true / requiredItems 空 / 支払い判定を呼ばない")
        void noGate_accessibleTrue() {
            given(contentPaymentGateRepository.findByContentTypeAndContentIdIn(eq(CONTENT_TYPE), any()))
                    .willReturn(List.of());
            given(paymentItemRepository.findAllById(any())).willReturn(List.of());

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isTrue();
            assertThat(res.isTitleHidden()).isFalse();
            assertThat(res.getRequiredItems()).isEmpty();
            verify(memberPaymentRepository, never()).existsValidPaidPayment(eq(VIEWER_USER_ID), eq(100L));
        }
    }

    @Nested
    @DisplayName("④ titleHidden=true → 存在秘匿")
    class TitleHidden {

        @Test
        @DisplayName("titleHidden=true のゲートを含む → titleHidden=true / requiredItems は空（名称・金額を露出させない）")
        void titleHidden_suppressesRequiredItems() {
            givenBound(
                    List.of(gate(1L, 100L, true)),
                    List.of(scopedItem(100L, "極秘プラン", new BigDecimal("9800"))),
                    List.of());

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isFalse();
            // 支払済みなら titleHidden 設定に関係なく本文へ到達できるため FULL 扱い。
            assertThat(res.isTitleHidden()).isTrue();
            // 存在秘匿: 名称・金額を含む requiredItems は出さない
            assertThat(res.getRequiredItems()).isEmpty();
        }

        @Test
        @DisplayName("titleHidden=true で支払い済 → accessible=true（titleHidden は秘匿フラグであって解錠条件ではない）")
        void titleHidden_paid_accessibleTrue() {
            givenBound(
                    List.of(gate(1L, 100L, true)),
                    List.of(scopedItem(100L, "極秘プラン", new BigDecimal("9800"))),
                    List.of(100L));

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isTrue();
            assertThat(res.isTitleHidden()).isFalse();
            assertThat(res.getRequiredItems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("⑤ fail-safe（不整合 → 閲覧拒否側に倒す）")
    class FailSafe {

        @Test
        @DisplayName("gate が参照する payment_item が消失 → accessible=false（漏洩より過剰遮断）")
        void missingPaymentItem_failsafeDeny() {
            givenBound(List.of(gate(1L, 100L, false)), List.of(), List.of());

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isFalse();
            // 消失項目は requiredItems に積まない（名称・金額を引けないため）
            assertThat(res.getRequiredItems()).isEmpty();
            // 不整合ゲートは支払い判定すら行わない（fail-safe で即拒否）
            verify(memberPaymentRepository, never()).existsValidPaidPayment(eq(VIEWER_USER_ID), eq(100L));
        }

        @Test
        @DisplayName("payment_item_id 欠落（NULL）→ accessible=false")
        void nullPaymentItemId_failsafeDeny() {
            givenBound(List.of(gate(1L, null, false)), List.of(), List.of());

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isFalse();
            assertThat(res.getRequiredItems()).isEmpty();
        }

        @Test
        @DisplayName("正常ゲート＋不整合ゲート混在 → 全体 accessible=false（1つでも不整合なら拒否）")
        void mixedValidAndBroken_deny() {
            givenBound(
                    List.of(gate(1L, 100L, false), gate(2L, 200L, false)),
                    List.of(scopedItem(100L, "月会費", new BigDecimal("3000"))),
                    List.of(100L));

            GateCheckResponse res = checkBound();

            assertThat(res.isAccessible()).isFalse();
            // 正常項目のみ requiredItems に積まれる（消失項目は積まない）
            assertThat(res.isTitleHidden()).isTrue();
            assertThat(res.getRequiredItems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("受益者キー判定（IDOR 防止）")
    class BeneficiaryKey {

        @Test
        @DisplayName("viewer 自身の userId でのみ支払い判定する（他人 userId では呼ばない）")
        void evaluatesViewerOnly() {
            givenBound(
                    List.of(gate(1L, 100L, false)),
                    List.of(scopedItem(100L, "月会費", new BigDecimal("3000"))),
                    List.of(100L));

            checkBound();

            verify(memberPaymentRepository).findValidPaidPaymentItemIds(eq(VIEWER_USER_ID), anyList());
            verify(memberPaymentRepository, never()).findValidPaidPaymentItemIds(eq(OTHER_USER_ID), anyList());
        }
    }

    @Nested
    @DisplayName("scope束縛のfail-closed")
    class ScopeBindingFailClosed {

        @Test
        @DisplayName("POSTの旧3引数batch入口はscope不明のためHIDDEN")
        void legacyBatchIsHidden() {
            Map<Long, GateCheckResponse> result = service.checkAccessBatch(
                    CONTENT_TYPE, List.of(CONTENT_ID), VIEWER_USER_ID);

            assertThat(result.get(CONTENT_ID).isAccessible()).isFalse();
            assertThat(result.get(CONTENT_ID).isTitleHidden()).isTrue();
        }

        @Test
        @DisplayName("4引数入口でもtarget欠落はHIDDEN")
        void nullTargetIsHidden() {
            GateCheckResponse result = service.checkAccess(
                    CONTENT_TYPE, CONTENT_ID, VIEWER_USER_ID, null);

            assertThat(result.isAccessible()).isFalse();
            assertThat(result.isTitleHidden()).isTrue();
        }
    }
}
