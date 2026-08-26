package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.dto.MembershipCheckoutRequest;
import com.mannschaft.app.payment.dto.PaymentItemResponse;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;

import java.util.UUID;

/**
 * 会費 Connect 即時チェックアウトコントローラー（F08.9 P1 Wave5）。
 *
 * <p>設計書 F08.9 02 §1.1 に基づき、払い手分離＋Connect Destination PaymentIntent 即時 charge を提供する。
 * 既存の素 Checkout（Stripe Checkout Session リダイレクト・{@link com.mannschaft.app.payment.service.MemberPaymentService#createCheckout}）
 * は Service 層に保持したまま、本 Controller は会費の新規決済を Connect 即時フローへ配線する。</p>
 *
 * <h3>共存/置換の判断根拠</h3>
 * <ul>
 *   <li>旧 Controller（F08.2 素 Checkout）はボディなしの {@code POST /checkout} だった。</li>
 *   <li>新 Controller（F08.9 Connect Checkout）は {@code beneficiaryUserId} を含む {@code @RequestBody} を必要とする。</li>
 *   <li>同一パス {@code POST /api/v1/payment-items/{itemId}/checkout} に新仕様を配置し、旧 Service メソッドは
 *       将来の廃止まで保持（最小破壊）。FE が旧エンドポイントを呼んでいた場合はリクエストボディを追加する改修が必要。</li>
 * </ul>
 *
 * <h3>認可方針</h3>
 * <ul>
 *   <li>ログイン必須：未認証の場合は {@code SecurityUtils.getCurrentUserId()} が {@code CommonErrorCode.COMMON_000} を投げる。</li>
 *   <li>受益者への権原はサービス層 {@code PaymentAuthorizationService.authorizePayment}（{@code manualRecordByAdmin=false}）が
 *       最終防衛する（二重防御）。P1 では SELF のみ通過。</li>
 * </ul>
 *
 * <p>エンドポイント数: 2（GET 支払い項目取得（Issue #2657） / POST checkout）</p>
 */
@RestController
@RequestMapping("/api/v1/payment-items/{itemId}")
@Tag(name = "会費 Connect チェックアウト", description = "F08.9 P1 払い手分離・Connect 即時 charge")
@RequiredArgsConstructor
public class PaymentCheckoutController {

    private final MemberPaymentService memberPaymentService;
    private final PaymentItemService paymentItemService;

    /**
     * 支払い項目を ID で取得する（Issue #2657: TERM 型の有効期間表示等・加入ページ用）。
     *
     * <p><b>認可方針</b>: {@code itemId} が属するチーム/組織のメンバーであることを
     * {@code PaymentItemService#getPaymentItemById} 内部で
     * {@code com.mannschaft.app.common.AccessControlService#checkMembership}
     * により検証する（{@link AuthorizedInService} ではなく白名簿クラス経由の通常認可）。</p>
     *
     * @param itemId 支払い項目 ID
     * @return 200 OK + {@link PaymentItemResponse}
     */
    @GetMapping
    @Operation(summary = "支払い項目取得（Issue #2657）")
    public ResponseEntity<ApiResponse<PaymentItemResponse>> getPaymentItem(@PathVariable Long itemId) {
        Long userId = SecurityUtils.getCurrentUserId();
        PaymentItemResponse response = paymentItemService.getPaymentItemById(itemId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 会費を払い手分離＋Connect 即時 charge でチェックアウトする（設計書 02 §1.1）。
     *
     * <p>払い手（{@code payerUserId}）は {@code SecurityUtils.getCurrentUserId()} で確定する。
     * 後見切替セッション中（{@code X-Proxy-For-User-Id} 付き）でも払い手はログインユーザーのまま。</p>
     *
     * <p><b>認可の所在</b>: ボディの {@code beneficiaryUserId} に対する払い手の権原は
     * {@code MemberPaymentService.createConnectCheckout}
     * （{@code payment/service/MemberPaymentService.java:392}）が
     * {@code PaymentAuthorizationService.authorizePayment}
     * （{@code payment/service/PaymentAuthorizationService.java:97}）で毎回実行時評価する。
     * SELF / 承認済み保護者 / 有効な代理払い grant のいずれも成立しなければ
     * {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）。<b>検証は重複判定・Connect 口座解決・
     * PaymentIntent 作成・member_payments 起票のすべてより前</b>にあり、権原なき要求では
     * 課金も起票も発生しない。</p>
     *
     * <p>冪等性：{@code Idempotency-Key} ヘッダが付いていればそれを優先し、
     * 省略時はリクエストボディの {@code idempotencyKey} を使い、どちらも無ければ UUID を生成する。</p>
     *
     * @param itemId             支払い対象の会費項目 ID
     * @param idempotencyKeyHeader {@code Idempotency-Key} ヘッダ（省略可）
     * @param request            受益者 ID・冪等キー
     * @return 201 Created + {@link ConnectCheckoutResponse}（clientSecret / memberPaymentId / escrowTransactionId）
     */
    @AuthorizedInService
    @PostMapping("/checkout")
    @Operation(summary = "会費 Connect 即時チェックアウト（F08.9 P1）")
    public ResponseEntity<ApiResponse<ConnectCheckoutResponse>> createConnectCheckout(
            @PathVariable Long itemId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody MembershipCheckoutRequest request) {

        Long payerUserId = SecurityUtils.getCurrentUserId();

        // 冪等キー解決: ヘッダ > ボディ > 自動生成（UUID）
        String idempotencyKey = idempotencyKeyHeader != null ? idempotencyKeyHeader
                : request.getIdempotencyKey() != null ? request.getIdempotencyKey()
                : UUID.randomUUID().toString();

        ConnectCheckoutResponse response = memberPaymentService.createConnectCheckout(
                itemId, request.getBeneficiaryUserId(), payerUserId, idempotencyKey);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
