package com.mannschaft.app.billing.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;

/**
 * PR4 Stripe return callback（BC-16 / BC-28）。
 *
 * <p>Stripe からの top-level GET は Origin / Referer を伴わないため、CSRF 防御は
 * 署名済み state（HMAC + purpose + expiry）と nonce の CAS 消費で行う。未認証なら
 * nonce を消費せず HttpOnly Cookie へ退避して再ログインへ送り、認証済みなら
 * 「HMAC/purpose/expiry 検証 → actor 解決 → scope guard → nonce CAS」の順で処理して
 * state を含まない clean URL へ 303 する。token / PII は Location にも body にも出さない。
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingReturnController {
    /** 未認証時に state を退避する HttpOnly Cookie 名。 */
    static final String RETURN_STATE_COOKIE = "billing_return_state";
    private static final String COOKIE_PATH = "/billing";
    private static final String SAME_SITE_LAX = "Lax";
    private static final Duration COOKIE_MAX_AGE = Duration.ofMinutes(30);
    private static final String LOGIN_REDIRECT = "/login?next="
            + URLEncoder.encode("/billing", StandardCharsets.UTF_8);
    private static final String GENERIC_ERROR_REDIRECT = "/billing?scopeKind=USER&tab=plan&error=return";

    private final BillingReturnStateService returnStateService;
    private final BillingCheckoutScopeGuard scopeGuard;

    /**
     * Checkout 成功 callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @GetMapping("/checkout/success")
    public ResponseEntity<Void> checkoutSuccess(@RequestParam String state, Principal principal,
                                                 HttpServletResponse response) {
        return handle(state, principal, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, false);
    }

    /**
     * Checkout キャンセル callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @GetMapping("/checkout/cancel")
    public ResponseEntity<Void> checkoutCancel(@RequestParam String state, Principal principal,
                                                HttpServletResponse response) {
        return handle(state, principal, BillingReturnStateService.Purpose.CHECKOUT_CANCEL, false);
    }

    /**
     * Customer Portal からの復帰 callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @GetMapping("/portal/return")
    public ResponseEntity<Void> portalReturn(@RequestParam String state, Principal principal,
                                              HttpServletResponse response) {
        return handle(state, principal, BillingReturnStateService.Purpose.PORTAL_RETURN, false);
    }

    /**
     * 3DS などの payment action からの復帰 callback。state は URL では受け取らず
     * HttpOnly Cookie のみから読み、消費後に Cookie を失効させる。
     *
     * @param state Cookie から読んだ署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @GetMapping("/payment-action/return")
    public ResponseEntity<Void> paymentActionReturn(
            @CookieValue(name = RETURN_STATE_COOKIE, required = false) String state,
            Principal principal, HttpServletRequest request, HttpServletResponse response) {
        return handle(state, principal, BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN, true);
    }

    /**
     * callback 共通処理。失敗理由は一切外へ出さず generic な hub へ畳む。
     *
     * @param clearCookie 処理後に退避 Cookie を失効させるか
     */
    private ResponseEntity<Void> handle(String state, Principal principal,
                                        BillingReturnStateService.Purpose purpose, boolean clearCookie) {
        if (state == null || state.isBlank()) {
            return redirect(GENERIC_ERROR_REDIRECT, clearCookie ? expiredCookie() : null);
        }
        if (principal == null) {
            // 未認証: nonce は消費せず state を HttpOnly Cookie へ退避してから再ログインさせる。
            return redirect(LOGIN_REDIRECT, stateCookie(state));
        }
        try {
            BillingReturnStateService.ReturnState verified = returnStateService.verify(state, purpose);
            long actorId = Long.parseLong(principal.getName());
            scopeGuard.check(actorId, verified.scopeKind(), verified.scopeId());
            returnStateService.consumeNonce(verified, actorId);
            return redirect(cleanRedirect(verified), clearCookie ? expiredCookie() : null);
        } catch (RuntimeException e) {
            // 改竄・期限切れ・再利用・権限不足はすべて同一の generic 遷移に畳む（詳細は返さない）。
            return redirect(GENERIC_ERROR_REDIRECT, clearCookie ? expiredCookie() : null);
        }
    }

    private String cleanRedirect(BillingReturnStateService.ReturnState state) {
        StringBuilder url = new StringBuilder("/billing?scopeKind=")
                .append(state.scopeKind().name())
                .append("&scopeId=").append(state.scopeId());
        if (state.tab() != null && !state.tab().isBlank()) {
            url.append("&tab=").append(URLEncoder.encode(state.tab(), StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private ResponseEntity<Void> redirect(String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location);
        if (cookie != null) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }

    private ResponseCookie stateCookie(String state) {
        return ResponseCookie.from(RETURN_STATE_COOKIE, state)
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE_LAX)
                .path(COOKIE_PATH)
                .maxAge(COOKIE_MAX_AGE)
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(RETURN_STATE_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite(SAME_SITE_LAX)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }
}
