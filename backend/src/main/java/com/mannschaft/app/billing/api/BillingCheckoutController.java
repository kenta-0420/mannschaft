package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.BillingQuoteResponse;
import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PR4 Billing Center: 見積り（quote）と Checkout Session の HTTP 入口（BC-03 / BC-13 / BC-23）。
 *
 * <p><b>認可</b>: 入口は認証必須（{@code isAuthenticated()}）に留め、実際の scope 認可は
 * application service が {@link BillingCheckoutScopeGuard} 経由で
 * {@link BillingAccessGuard#canManageByActorId} に委ねる。scope はパスではなく
 * <b>リクエスト本文</b>（quote）と <b>quote に焼き付いた値</b>（checkout）から来るため、
 * SpEL のパス引数では守れず、必ずサービス層で actor と突き合わせる必要がある
 * （checkout は quote の所有者一致も併せて検証し、他人の quote は失効と同じ 409 に畳む）。</p>
 *
 * <p><b>冪等性</b>: どちらも {@code Idempotency-Key} ヘッダ必須（欠落時は Spring が
 * {@code MissingRequestHeaderException} → 400）。Stripe 側の二重 Session 作成は
 * application service が渡す idempotency key で塞ぐ。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 見積り/Checkout", description = "PR4 quote 発行と Stripe Checkout Session 作成")
@RequiredArgsConstructor
public class BillingCheckoutController {

    private final BillingQuoteService quoteService;
    private final BillingCheckoutApplicationService checkoutApplicationService;

    /**
     * 見積り（quote）を発行する。有効期間は 10 分で、Checkout 直前に再照合される。
     *
     * @param request      対象 scope と商品
     * @param idempotencyKey 冪等キー（必須）
     * @return 発行した quote
     */
    @PostMapping("/me/billing/quotes")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "見積り発行",
            description = "scope は本文で指定し、操作者の課金管理権限をサービス層で検証する。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingQuoteResponse>> createQuote(
            @Valid @RequestBody CreateBillingQuoteRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        BillingQuoteResponse body = quoteService.create(actorId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    /**
     * quote を消費して Stripe Checkout Session を作成する。
     *
     * @param request        消費する quote
     * @param idempotencyKey 冪等キー（必須・Stripe への再送も同一キーに束縛する）
     * @return Checkout URL と Session 失効時刻
     */
    @PostMapping("/me/billing/checkout-sessions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Checkout Session 作成",
            description = "quote の所有者・scope・価格・月境界を再検証してから Stripe Checkout を作成する。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<BillingCheckoutApplicationService.CheckoutSessionResponse>>
            createCheckoutSession(
                    @Valid @RequestBody CreateBillingCheckoutSessionRequest request,
                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long actorId = SecurityUtils.getCurrentUserId();
        BillingCheckoutApplicationService.CheckoutSessionResponse body =
                checkoutApplicationService.create(actorId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }
}
