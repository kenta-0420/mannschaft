package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BC-16 / BC-28: Stripe 復帰 callback が <b>実フィルタ鎖を通して</b>未認証で到達できることの実証。
 *
 * <p><b>なぜ addFilters=false にしないのか</b>: 本 IT が測りたいのは「Spring Security の URL ルール層が
 * 未認証リクエストを controller の手前で落としていないこと」そのものである。フィルタを外した検証は
 * この欠陥を<b>原理的に検出できない</b>（実際 PR4 の単体テストは controller を直接叩いていたため、
 * SecurityConfig に permitAll が無く全 callback が 401 になる状態で緑だった）。
 * したがって {@code @AutoConfigureMockMvc} をフィルタ有効のまま用いる。</p>
 *
 * <p>許可側（4 入口が 401 にならず 303 になる）と拒否側（同じ prefix でも開けていない入口・メソッドは
 * deny-by-default のまま）を両方書く。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("BC-16/28 Stripe 復帰 callback の実フィルタ通し認可 IT")
class BillingReturnCallbackSecurityIT extends AbstractMySqlIntegrationTest {

    /** 署名検証は必ず失敗する検体。ここで測るのは「controller まで届くか」であり検証結果ではない。 */
    private static final String OPAQUE_STATE = "kid.payload.signature";

    /** USER scope の callback を踏む actor。USER scope は actorId==scopeId だけで許可される。 */
    private static final long ACTOR_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    // ═════════ 許可側: 未認証でも 401 にならず設計どおり 303 する ═════════

    @Test
    @DisplayName("未認証の checkout/success は 401 ではなく /login への 303 になる（state は消費しない）")
    void checkoutSuccess_未認証_loginへ303() throws Exception {
        mockMvc.perform(get("/billing/checkout/success").param("state", OPAQUE_STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling%2Fcheckout%2Fsuccess"))
                // 署名済み state は HttpOnly Cookie へ退避される（URL・body には出さない）。
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("billing_return_state=")))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.containsString("HttpOnly")));
    }

    @Test
    @DisplayName("未認証の checkout/cancel は 401 ではなく /login への 303 になる")
    void checkoutCancel_未認証_loginへ303() throws Exception {
        mockMvc.perform(get("/billing/checkout/cancel").param("state", OPAQUE_STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling%2Fcheckout%2Fcancel"));
    }

    @Test
    @DisplayName("未認証の portal/return は 401 ではなく /login への 303 になる")
    void portalReturn_未認証_loginへ303() throws Exception {
        mockMvc.perform(get("/billing/portal/return").param("state", OPAQUE_STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling%2Fportal%2Freturn"));
    }

    @Test
    @DisplayName("未認証の payment-action/return は 401 にならず（cookie 無しなので）generic hub へ 303 する")
    void paymentActionReturn_未認証_401にならない() throws Exception {
        // state は cookie のみから読むため、cookie 無しの到達は generic error 遷移になる。
        // ここで重要なのは「401 ではないこと」＝ フィルタ鎖を通り controller に届いていること。
        mockMvc.perform(get("/billing/payment-action/return"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));
    }

    // ═════════ 拒否側: ワイルドカードで開いていないこと ═════════

    @Test
    @DisplayName("開けていない /billing 配下の GET は未認証で 401（ワイルドカード公開ではない）")
    void billing配下の別パス_未認証_401() throws Exception {
        mockMvc.perform(get("/billing/checkout/success/extra"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("復帰入口でも POST は開けていない（GET だけを permitAll している）")
    void checkoutSuccess_POST_未認証_401() throws Exception {
        mockMvc.perform(post("/billing/checkout/success").param("state", OPAQUE_STATE))
                .andExpect(status().isUnauthorized());
    }

    // ═════════ P1-2: 未認証退避 → 再認証 → callback 自身が cookie を消費する一連 ═════════

    @Autowired
    private BillingReturnStateService returnStateService;

    /**
     * BC-16 の復帰導線が「未認証で落ちた要求 → login → 同じ callback → nonce 消費 → clean URL」で
     * 実際に閉じることを、実フィルタ鎖のまま一連で通して実証する。
     *
     * <p>回帰の的: 以前は checkout/success が {@code @RequestParam String state} しか読まず、
     * 退避 Cookie を受け取る実装が payment-action にしか無かった。その状態では
     * 第2脚（param 無し・Cookie のみ）は 400（必須 param 欠落）になり、nonce は永遠に消費されない。</p>
     */
    @Test
    @DisplayName("未認証 checkout/success の退避 Cookie を、再認証後の同じ callback が消費して clean URL へ 303 する")
    void checkoutSuccess_未認証退避cookieを再認証後の同callbackが消費する() throws Exception {
        String token = issueUserScopeState(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);

        // 第1脚: 未認証。nonce は消費せず Cookie へ退避し、callback 自身へ戻る login URL を返す。
        MvcResult unauthenticated = mockMvc.perform(
                        get("/billing/checkout/success").param("state", token))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling%2Fcheckout%2Fsuccess"))
                .andReturn();
        Cookie saved = unauthenticated.getResponse().getCookie("billing_return_state");
        org.assertj.core.api.Assertions.assertThat(saved).isNotNull();
        org.assertj.core.api.Assertions.assertThat(saved.getValue()).isEqualTo(token);

        // 第2脚: 再認証後、query param を伴わず Cookie だけで同じ callback を踏む。
        mockMvc.perform(get("/billing/checkout/success").cookie(saved).with(user(String.valueOf(ACTOR_ID))))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&scopeId=" + ACTOR_ID + "&tab=plan"))
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("state="))))
                // 消費後の Cookie は必ず失効させる。
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .cookie().maxAge("billing_return_state", 0));

        // 第3脚: 同じ Cookie の再利用は nonce CAS が一度しか通らないため generic hub へ落ちる。
        mockMvc.perform(get("/billing/checkout/success").cookie(saved).with(user(String.valueOf(ACTOR_ID))))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&tab=plan&error=return"));
    }

    @Test
    @DisplayName("query param と Cookie が両方来たら param を優先する（古い退避 state に上書きされない）")
    void checkoutSuccess_paramとcookie両方_paramを優先する() throws Exception {
        String cookieToken = issueUserScopeState(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);
        String paramToken = issueUserScopeState(BillingReturnStateService.Purpose.CHECKOUT_SUCCESS);

        mockMvc.perform(get("/billing/checkout/success")
                        .param("state", paramToken)
                        .cookie(new Cookie("billing_return_state", cookieToken))
                        .with(user(String.valueOf(ACTOR_ID))))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&scopeId=" + ACTOR_ID + "&tab=plan"));

        // param 側だけが消費されているので、cookie 側の state は今なお一度だけ消費できる。
        mockMvc.perform(get("/billing/checkout/success")
                        .cookie(new Cookie("billing_return_state", cookieToken))
                        .with(user(String.valueOf(ACTOR_ID))))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/billing?scopeKind=USER&scopeId=" + ACTOR_ID + "&tab=plan"));
    }

    /** USER scope（actor 自身）の state を実サービスで発行する。nonce 台帳にも実際に登録される。 */
    private String issueUserScopeState(BillingReturnStateService.Purpose purpose) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return returnStateService.issue(new BillingReturnStateService.ReturnState(
                purpose, EntitlementScopeKind.USER, ACTOR_ID, ACTOR_ID, "plan",
                null, null, null, now, now.plusSeconds(1800), UUID.randomUUID().toString()));
    }
}
