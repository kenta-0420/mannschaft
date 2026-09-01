package com.mannschaft.app.billing.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** PR4 Stripe return callback の HTTP 契約をコンパイル可能にする未実装骨格。 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingReturnController {
    private final BillingReturnStateService returnStateService;
    private final BillingCheckoutScopeGuard scopeGuard;

    @GetMapping("/checkout/success")
    public ResponseEntity<Void> checkoutSuccess(@RequestParam String state, Principal principal,
                                                 HttpServletResponse response) {
        throw new UnsupportedOperationException("PR4 checkout success callback is not implemented");
    }

    @GetMapping("/checkout/cancel")
    public ResponseEntity<Void> checkoutCancel(@RequestParam String state, Principal principal,
                                                HttpServletResponse response) {
        throw new UnsupportedOperationException("PR4 checkout cancel callback is not implemented");
    }

    @GetMapping("/portal/return")
    public ResponseEntity<Void> portalReturn(@RequestParam String state, Principal principal,
                                              HttpServletResponse response) {
        throw new UnsupportedOperationException("PR4 portal callback is not implemented");
    }

    @GetMapping("/payment-action/return")
    public ResponseEntity<Void> paymentActionReturn(
            @CookieValue(name = "billing_return_state", required = false) String state,
            Principal principal, HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException("PR4 payment action callback is not implemented");
    }
}
