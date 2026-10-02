package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.PaymentMethodConfirmRequest;
import com.mannschaft.app.payment.dto.PaymentMethodResponse;
import com.mannschaft.app.payment.dto.SetupIntentResponse;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 P5 第二波: 支払い方法（SetupIntent）基盤コントローラー（設計書 02 §4.1）。
 *
 * <p>継続課金（subscribe・案b）の前提となる off_session 用 PaymentMethod の保存導線を提供する。
 * 払い手は常に {@code SecurityUtils.getCurrentUserId()}（ログインユーザー本人）で解決する。</p>
 *
 * <h3>エンドポイント（2本）</h3>
 * <ul>
 *   <li>{@code POST /api/v1/me/payment-methods/setup-intent} — Customer を get-or-create し SetupIntent を作成。</li>
 *   <li>{@code POST /api/v1/me/payment-methods/confirm} — confirm 済み PM を attach＋既定設定。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/payment-methods")
@Tag(name = "支払い方法（SetupIntent）", description = "F08.9 P5 継続課金 off_session PM 保存")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    /**
     * off_session 用 SetupIntent を作成し client_secret を返す（設計書 02 §4.1）。
     *
     * @return 201 Created + {@link SetupIntentResponse}（setupIntentId / clientSecret / status）
     */
    @SelfScopedEndpoint("Customer の get-or-create は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（createSetupIntent メソッド本体）")
    @PostMapping("/setup-intent")
    @Operation(summary = "SetupIntent 作成（F08.9 P5・off_session PM 保存）")
    public ResponseEntity<ApiResponse<SetupIntentResponse>> createSetupIntent() {
        Long userId = SecurityUtils.getCurrentUserId();
        StripePaymentProvider.SetupIntentInfo info = paymentMethodService.createSetupIntent(userId);
        SetupIntentResponse response = SetupIntentResponse.builder()
                .setupIntentId(info.setupIntentId())
                .clientSecret(info.clientSecret())
                .status(info.status())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * confirm 済みの PaymentMethod を Customer へ attach＋既定設定する（設計書 02 §4.1）。
     *
     * @param request confirm 済みの payment_method_id
     * @return 200 OK + {@link PaymentMethodResponse}（defaultPaymentMethod / saved）
     */
    @SelfScopedEndpoint("attach 先の Customer は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストの payment_method_id は自分の Customer への attach 対象を指すのみで"
            + "他ユーザーの内部リソースを検索する識別子ではない（confirmPaymentMethod メソッド本体）")
    @PostMapping("/confirm")
    @Operation(summary = "支払い方法 confirm（F08.9 P5・attach＋既定設定）")
    public ResponseEntity<ApiResponse<PaymentMethodResponse>> confirmPaymentMethod(
            @Valid @RequestBody PaymentMethodConfirmRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        StripeCustomerEntity customer = paymentMethodService.confirmPaymentMethod(userId, request.getPaymentMethodId());
        PaymentMethodResponse response = PaymentMethodResponse.builder()
                .defaultPaymentMethod(customer.getDefaultPaymentMethod())
                .saved(customer.getDefaultPaymentMethod() != null)
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
