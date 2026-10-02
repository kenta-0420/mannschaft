package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.BillingPaymentActionResponse;
import com.mannschaft.app.billing.api.dto.BillingPaymentActionResponse.PaymentActionDto;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Billing Center PR6b-1 C群: 3DS payment-action の HTTP 入口（AC-48〜54/AC-60〜62/AC-70/AC-71・第8隊）。
 *
 * <pre>
 * GET /api/v1/me/billing/contracts/{contractId}/changes/{changeId}/payment-action
 * </pre>
 *
 * <p>change が {@code REQUIRES_ACTION} のときだけ 200 で {@code paymentAction} を返し、同時に
 * リダイレクト型 3DS の戻りが読む HttpOnly cookie（{@code billing_payment_action_state}・
 * {@code Path=/billing/payment-action/return}）を発行する（E3'）。それ以外は 409（{@code ENTITLEMENT_021}）
 * か 404（他スコープ・同一スコープの別 actor・E5'）で、いずれも clientSecret・cookie を一切返さない。</p>
 *
 * <p>Idempotency-Key は要求しない（副作用として権利や課金状態を進めない読み取り専用の都度取得であり、
 * PR6a/第7隊の耐久冪等台帳の対象外）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/billing/contracts")
@Tag(name = "課金 - プラン変更", description = "Billing Center PR6b-1 3DS payment-action")
@RequiredArgsConstructor
public class BillingPlanChangePaymentActionController {

    private final BillingPaymentActionService paymentActionService;

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の進行中プラン変更の3DS追加認証を取得可能にするため。Gate状態にかかわらず到達させる")
    @GetMapping("/{contractId}/changes/{changeId}/payment-action")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "3DS payment-action の取得",
            description = "change が REQUIRES_ACTION のときだけ、Stripe から都度 clientSecret を取得して返す。"
                    + "DB には保存しない。")
    public ResponseEntity<ApiResponse<BillingPaymentActionResponse>> paymentAction(
            @PathVariable UUID contractId, @PathVariable UUID changeId) {
        long actorId = SecurityUtils.getCurrentUserId();
        BillingPaymentActionService.Result result = paymentActionService.retrieve(actorId, contractId, changeId);

        ResponseCookie cookie = BillingReturnController.paymentActionCookie(
                result.cookieToken(), Duration.ofSeconds(result.cookieMaxAgeSeconds()));

        BillingPaymentActionResponse body = new BillingPaymentActionResponse(
                new PaymentActionDto(result.type(), result.clientSecret(), result.expiresAt()));
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(body));
    }
}
