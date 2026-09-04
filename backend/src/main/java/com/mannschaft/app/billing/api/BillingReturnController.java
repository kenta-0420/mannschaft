package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
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
    private static final String GENERIC_ERROR_REDIRECT = "/billing?scopeKind=USER&tab=plan&error=return";

    /**
     * 未認証時の再ログイン先。{@code next} は<b>その callback 自身</b>のパスを指す。
     *
     * <p>設計正本（05_billing_center.md BC-16 / BC-28）は「再認証後 callback 自身が
     * HMAC/purpose/expiry → actor/Guard → nonce CAS の順で一回消費し clean URL へ 303 する」
     * と定める。{@code next=/billing} では callback が二度と呼ばれず nonce が消費されないため、
     * 退避 Cookie（path=/billing）が届く callback 自身へ戻す。</p>
     */
    private static String loginRedirect(String callbackPath) {
        return "/login?next=" + URLEncoder.encode(callbackPath, StandardCharsets.UTF_8);
    }

    private final BillingReturnStateService returnStateService;
    private final BillingCheckoutAccessGuard scopeGuard;

    /**
     * Checkout 成功 callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "Stripe Checkout からの top-level GET 復帰。決済完了後の唯一の復帰導線であり feature flag で遮断すると決済済み利用者が宙に浮くため常時到達とする")
    @GetMapping("/checkout/success")
    public ResponseEntity<Void> checkoutSuccess(
            @RequestParam(name = "state", required = false) String state,
            @CookieValue(name = RETURN_STATE_COOKIE, required = false) String cookieState,
            Principal principal, HttpServletResponse response) {
        return handle(state, cookieState, principal,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, "/billing/checkout/success");
    }

    /**
     * Checkout キャンセル callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "Stripe Checkout からの top-level GET 復帰。中断後の復帰導線であり遮断すると利用者が Stripe 側に取り残されるため常時到達とする")
    @GetMapping("/checkout/cancel")
    public ResponseEntity<Void> checkoutCancel(
            @RequestParam(name = "state", required = false) String state,
            @CookieValue(name = RETURN_STATE_COOKIE, required = false) String cookieState,
            Principal principal, HttpServletResponse response) {
        return handle(state, cookieState, principal,
                BillingReturnStateService.Purpose.CHECKOUT_CANCEL, "/billing/checkout/cancel");
    }

    /**
     * Customer Portal からの復帰 callback。
     *
     * @param state 署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "Stripe Customer Portal からの top-level GET 復帰。外部サイトからの戻り導線であり遮断できないため常時到達とする")
    @GetMapping("/portal/return")
    public ResponseEntity<Void> portalReturn(
            @RequestParam(name = "state", required = false) String state,
            @CookieValue(name = RETURN_STATE_COOKIE, required = false) String cookieState,
            Principal principal, HttpServletResponse response) {
        return handle(state, cookieState, principal,
                BillingReturnStateService.Purpose.PORTAL_RETURN, "/billing/portal/return");
    }

    /**
     * 3DS などの payment action からの復帰 callback。state は URL では受け取らず
     * HttpOnly Cookie のみから読み、消費後に Cookie を失効させる。
     *
     * @param state Cookie から読んだ署名済み return state
     * @param principal 認証済み actor（未認証なら null）
     * @return clean URL / login / generic error への 303
     */
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "3DS など payment action 完了後の top-level GET 復帰。認証済み決済の完了導線であり遮断できないため常時到達とする")
    @GetMapping("/payment-action/return")
    public ResponseEntity<Void> paymentActionReturn(
            @CookieValue(name = RETURN_STATE_COOKIE, required = false) String state,
            Principal principal, HttpServletRequest request, HttpServletResponse response) {
        // URL 由来の state は受け取らない（Cookie 専用入口）。
        return handle(null, state, principal,
                BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN, "/billing/payment-action/return");
    }

    /**
     * callback 共通処理。失敗理由は一切外へ出さず generic な hub へ畳む。
     *
     * <p><b>state の優先順位</b>: query param &gt; Cookie。Stripe / issuer からの直接復帰
     * （param 有り）が正で、Cookie は「未認証で一度落ちた要求を再ログイン後に運ぶ」ための
     * 退避経路に過ぎない。両方来た場合に Cookie を優先すると、古い退避 state が新しい復帰を
     * 上書きしうるため param を採る。PAYMENT_ACTION_RETURN だけは param を一切読まない
     * （短命 client secret 経路のため URL へ出さない設計）。</p>
     *
     * <p>Cookie が存在した要求では、消費に成功したか否かに関わらず必ず失効させる
     * （nonce は CAS で一回しか通らないため、残しても再利用できない使い捨てを持ち回るだけになる）。</p>
     *
     * @param paramState   query param 由来の state（PAYMENT_ACTION_RETURN では常に null）
     * @param cookieState  退避 Cookie 由来の state
     * @param callbackPath 未認証時に再ログイン後へ戻す callback 自身のパス
     */
    private ResponseEntity<Void> handle(String paramState, String cookieState, Principal principal,
                                        BillingReturnStateService.Purpose purpose,
                                        String callbackPath) {
        boolean cookiePresent = cookieState != null && !cookieState.isBlank();
        String state = (paramState != null && !paramState.isBlank()) ? paramState : cookieState;
        boolean clearCookie = cookiePresent
                || purpose == BillingReturnStateService.Purpose.PAYMENT_ACTION_RETURN;
        if (state == null || state.isBlank()) {
            return redirect(GENERIC_ERROR_REDIRECT, clearCookie ? expiredCookie() : null);
        }
        if (principal == null) {
            // 未認証: nonce は消費せず state を HttpOnly Cookie へ退避し、
            // 再ログイン後に「この callback 自身」へ戻して消費させる。
            return redirect(loginRedirect(callbackPath), stateCookie(state));
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
