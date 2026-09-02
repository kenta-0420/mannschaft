package com.mannschaft.app.billing.api;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

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

    @Autowired
    private MockMvc mockMvc;

    // ═════════ 許可側: 未認証でも 401 にならず設計どおり 303 する ═════════

    @Test
    @DisplayName("未認証の checkout/success は 401 ではなく /login への 303 になる（state は消費しない）")
    void checkoutSuccess_未認証_loginへ303() throws Exception {
        mockMvc.perform(get("/billing/checkout/success").param("state", OPAQUE_STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling"))
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
                .andExpect(header().string("Location", "/login?next=%2Fbilling"));
    }

    @Test
    @DisplayName("未認証の portal/return は 401 ではなく /login への 303 になる")
    void portalReturn_未認証_loginへ303() throws Exception {
        mockMvc.perform(get("/billing/portal/return").param("state", OPAQUE_STATE))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/login?next=%2Fbilling"));
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
}
